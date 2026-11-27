// Reading the processed statements from the user's private bucket, straight from the phone: each request is signed
// (AWS Signature Version 4) with a read-only key kept on this device. No server in between. Google Cloud Storage
// accepts the same signature through its S3-compatible XML API with an HMAC key, so one signer serves both.

export type Provider = 'aws' | 'gcp';

export interface S3Config { provider?: Provider; bucket: string; region: string; accessKeyId: string; secretAccessKey: string; sessionToken?: string }

const enc = new TextEncoder();
const EMPTY_SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

function hex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function sha256Hex(s: string): Promise<string> {
  return hex(await crypto.subtle.digest('SHA-256', enc.encode(s)));
}

async function hmac(key: ArrayBuffer | Uint8Array, data: string): Promise<ArrayBuffer> {
  const k = await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return crypto.subtle.sign('HMAC', k, enc.encode(data));
}

/** S3's URI encoding of a key: every byte except unreserved characters, "/" kept between segments. */
export function encodeKey(key: string): string {
  return key.split('/').map((s) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())).join('/');
}

export function endpoint(c: S3Config, key: string): { url: string; host: string; path: string } {
  if (c.provider === 'gcp') {
    const host = 'storage.googleapis.com';
    const path = `/${c.bucket}/${encodeKey(key)}`;
    return { url: `https://${host}${path}`, host, path };
  }
  // Buckets with dots don't match the wildcard certificate as a subdomain, so they use path-style addresses.
  const path = c.bucket.includes('.') ? `/${c.bucket}/${encodeKey(key)}` : `/${encodeKey(key)}`;
  const host = c.bucket.includes('.') ? `s3.${c.region}.amazonaws.com` : `${c.bucket}.s3.${c.region}.amazonaws.com`;
  return { url: `https://${host}${path}`, host, path };
}

/** The headers that sign a GET of [path] on [host] at [now]; [extra] headers are signed too. */
export async function signGet(c: S3Config, host: string, path: string, now: Date, extra: Record<string, string> = {}): Promise<Record<string, string>> {
  const amzDate = now.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const day = amzDate.slice(0, 8);
  const headers: Record<string, string> = { host, 'x-amz-content-sha256': EMPTY_SHA256, 'x-amz-date': amzDate, ...extra };
  if (c.sessionToken) headers['x-amz-security-token'] = c.sessionToken;
  const names = Object.keys(headers).map((h) => h.toLowerCase()).sort();
  const lower = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v.trim()]));
  const canonical = ['GET', path, '', ...names.map((n) => `${n}:${lower[n]}`), '', names.join(';'), EMPTY_SHA256].join('\n');
  const scope = `${day}/${c.region}/s3/aws4_request`;
  const toSign = ['AWS4-HMAC-SHA256', amzDate, scope, await sha256Hex(canonical)].join('\n');
  let k = await hmac(enc.encode('AWS4' + c.secretAccessKey), day);
  k = await hmac(k, c.region);
  k = await hmac(k, 's3');
  k = await hmac(k, 'aws4_request');
  const signature = hex(await hmac(k, toSign));
  const out: Record<string, string> = { ...headers, Authorization: `AWS4-HMAC-SHA256 Credential=${c.accessKeyId}/${scope}, SignedHeaders=${names.join(';')}, Signature=${signature}` };
  delete out.host;
  return out;
}

export class S3Error extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

export async function getText(c: S3Config, key: string): Promise<string> {
  const { url, host, path } = endpoint(c, key);
  let res: Response;
  try {
    res = await fetch(url, { headers: await signGet(c, host, path, new Date()), cache: 'no-store' });
  } catch {
    throw new S3Error(0, 'Could not reach the bucket. Check the internet connection, the bucket name' + (c.provider === 'gcp' ? '' : ' and region') + ', and that the bucket allows this site (CORS).');
  }
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    const code = /<Code>([^<]+)<\/Code>/.exec(body)?.[1] ?? '';
    const why: Record<string, string> = {
      AccessDenied: c.provider === 'gcp'
        ? 'Access denied. Either nothing is processed yet (run the "Process statements" workflow on GitHub first), or the HMAC key\'s service account may not read processed/.'
        : 'Access denied. Either nothing is processed yet (run the "Process statements" workflow on GitHub first), or the key\'s policy does not allow s3:GetObject on processed/*.',
      InvalidAccessKeyId: c.provider === 'gcp' ? 'This HMAC access ID is not known to Google Cloud.' : 'This access key ID is not known to AWS.',
      SignatureDoesNotMatch: 'The secret access key does not match the key ID.',
      NoSuchKey: 'Nothing processed yet. Run the "Process statements" workflow on GitHub first.',
      NoSuchBucket: 'No bucket with this name.',
      PermanentRedirect: 'The bucket is in another region. Check the region.',
      AuthorizationHeaderMalformed: 'The region does not match the bucket. Check the region.',
      RequestTimeTooSkewed: "The phone's clock is off. Set the time automatically and try again.",
    };
    throw new S3Error(res.status, why[code] ?? `${c.provider === 'gcp' ? 'Google Cloud' : 'S3'} said ${res.status}${code ? ` (${code})` : ''}.`);
  }
  return res.text();
}
