// The sign-in server (auth.mjs) against a small fake OpenID Connect provider.
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import crypto from 'node:crypto';
import http from 'node:http';
import { authConfig, guard, issuerMatches, resetProviderCache } from '../auth.mjs';

const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'k1', use: 'sig', alg: 'RS256' };
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');

let idp, app, IDP, APP, cfg;
let noEndSession = false; // Google publishes no end_session_endpoint
const codes = new Map(); // code -> { nonce, challenge, email, verified }
const provider = { email: 'siva@example.com', verified: true, iss: null, aud: null, badSig: false };

function idToken(claims) {
  const h = b64({ alg: 'RS256', kid: 'k1', typ: 'JWT' });
  const p = b64(claims);
  let sig = crypto.sign('sha256', Buffer.from(`${h}.${p}`), privateKey).toString('base64url');
  if (provider.badSig) sig = sig.slice(0, -4) + 'AAAA';
  return `${h}.${p}.${sig}`;
}

const listen = (srv) => new Promise((r) => srv.listen(0, '127.0.0.1', () => r(`http://127.0.0.1:${srv.address().port}`)));

beforeAll(async () => {
  idp = http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    const json = (o, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
    if (u.pathname === '/.well-known/openid-configuration') {
      return json({ issuer: IDP, authorization_endpoint: `${IDP}/authorize`, token_endpoint: `${IDP}/token`, jwks_uri: `${IDP}/jwks`, ...(noEndSession ? {} : { end_session_endpoint: `${IDP}/logout` }) });
    }
    if (u.pathname === '/jwks') return json({ keys: [jwk] });
    if (u.pathname === '/authorize') {
      const code = crypto.randomBytes(8).toString('hex');
      codes.set(code, { nonce: u.searchParams.get('nonce'), challenge: u.searchParams.get('code_challenge'), redirect: u.searchParams.get('redirect_uri') });
      res.writeHead(302, { Location: `${u.searchParams.get('redirect_uri')}?code=${code}&state=${u.searchParams.get('state')}` });
      return res.end();
    }
    if (u.pathname === '/token') {
      let body = '';
      for await (const c of req) body += c;
      const f = new URLSearchParams(body);
      const c = codes.get(f.get('code'));
      codes.delete(f.get('code'));
      const pkce = c && crypto.createHash('sha256').update(f.get('code_verifier') ?? '').digest('base64url') === c.challenge;
      if (!c || !pkce || f.get('client_secret') !== 'secret' || f.get('redirect_uri') !== c.redirect) return json({ error: 'invalid_grant' }, 400);
      const now = Math.floor(Date.now() / 1000);
      return json({
        access_token: 'x', token_type: 'Bearer',
        id_token: idToken({ iss: provider.iss ?? IDP, aud: provider.aud ?? 'client', sub: 'u1', email: provider.email, email_verified: provider.verified, nonce: c.nonce, iat: now, exp: now + 300 }),
      });
    }
    res.writeHead(404); res.end();
  });
  IDP = await listen(idp);
  app = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    if (await guard(cfg, req, res, url)) return;
    res.writeHead(200, { 'Content-Type': 'text/plain' }); res.end(`file ${url.pathname}`);
  });
  APP = await listen(app);
  cfg = authConfig({
    OIDC_ISSUER: IDP, OIDC_CLIENT_ID: 'client', OIDC_CLIENT_SECRET: 'secret', APP_URL: APP,
    ALLOWED_EMAILS: 'Siva@Example.com, asha@example.com', SESSION_SECRET: 'x'.repeat(40),
  });
});

afterAll(() => { idp?.close(); app?.close(); });

const get = (url, headers = {}) => fetch(url, { redirect: 'manual', headers });
const cookieOf = (r) => (r.headers.getSetCookie() ?? []).map((c) => c.split(';')[0]).filter((c) => !c.endsWith('=')).join('; ');

async function signIn(path = '/') {
  const start = await get(`${APP}${path}`, { accept: 'text/html' });
  expect(start.status).toBe(302);
  const login = await get(new URL(start.headers.get('location'), APP));
  const flow = cookieOf(login);
  const auth = new URL(login.headers.get('location'));
  expect(auth.searchParams.get('code_challenge_method')).toBe('S256');
  const back = await get(auth);
  return get(back.headers.get('location'), { cookie: flow });
}

describe('sign-in', () => {
  it('needs every var once OIDC_ISSUER is set, and is off without it', () => {
    expect(authConfig({})).toBeNull();
    expect(() => authConfig({ OIDC_ISSUER: 'https://x' })).toThrow(/OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, ALLOWED_EMAILS, SESSION_SECRET/);
    expect(() => authConfig({ OIDC_ISSUER: 'https://x', OIDC_CLIENT_ID: 'a', OIDC_CLIENT_SECRET: 'b', ALLOWED_EMAILS: 'a@b', SESSION_SECRET: 'short' })).toThrow(/32/);
  });

  it('sends a page load to sign-in, refuses files, and keeps install files public', async () => {
    const page = await get(`${APP}/`, { accept: 'text/html' });
    expect(page.status).toBe(302);
    expect(page.headers.get('location')).toBe('/auth/login?return=%2F');
    expect((await get(`${APP}/sw.js`)).status).toBe(401);
    expect((await get(`${APP}/assets/index-abc.js`, { accept: 'text/html' })).status).toBe(401);
    expect((await get(`${APP}/auth/me`)).status).toBe(401);
    expect((await get(`${APP}/manifest.webmanifest`)).status).toBe(200);
    expect((await get(`${APP}/apple-touch-icon.png`)).status).toBe(200);
  });

  it('signs in an allowed account and returns to where it started', async () => {
    provider.email = 'asha@example.com';
    const done = await signIn('/entries?x=1');
    expect(done.status).toBe(302);
    expect(done.headers.get('location')).toBe('/entries?x=1');
    const setCookies = done.headers.getSetCookie().join('\n');
    expect(setCookies).toMatch(/__Host-mf_session=.+; Path=\/; Max-Age=2592000; HttpOnly; Secure; SameSite=Lax/);
    const cookie = cookieOf(done);
    expect(await (await get(`${APP}/sw.js`, { cookie })).text()).toBe('file /sw.js');
    const me = await (await get(`${APP}/auth/me`, { cookie })).json();
    expect(me.email).toBe('asha@example.com');
    // A changed cookie is not accepted.
    const forged = cookie.replace(/=(.)/, (m, ch) => `=${ch === 'a' ? 'b' : 'a'}`);
    expect((await get(`${APP}/sw.js`, { cookie: forged })).status).toBe(401);
  });

  it('refuses accounts not on the list, or with an unverified email', async () => {
    provider.email = 'stranger@example.com';
    expect((await signIn()).status).toBe(403);
    provider.email = 'siva@example.com';
    provider.verified = false;
    expect((await signIn()).status).toBe(403);
    provider.verified = true;
    expect((await signIn()).status).toBe(302);
  });

  it('refuses tokens with a bad signature, issuer or audience', async () => {
    provider.badSig = true;
    expect((await signIn()).status).toBe(502);
    provider.badSig = false;
    provider.iss = 'https://evil.example';
    expect((await signIn()).status).toBe(502);
    provider.iss = null;
    provider.aud = 'other-app';
    expect((await signIn()).status).toBe(502);
    provider.aud = null;
  });

  it('refuses a callback without the matching sign-in cookie', async () => {
    const login = await get(`${APP}/auth/login`);
    const back = await get(new URL(login.headers.get('location')));
    expect((await get(back.headers.get('location'))).status).toBe(400);
  });

  it("accepts Google's issuer with or without https://, and nothing else", () => {
    expect(issuerMatches('accounts.google.com', 'https://accounts.google.com')).toBe(true);
    expect(issuerMatches('https://accounts.google.com', 'https://accounts.google.com')).toBe(true);
    expect(issuerMatches('accounts.google.com.evil', 'https://accounts.google.com')).toBe(false);
    expect(issuerMatches('example.okta.com', 'https://example.okta.com')).toBe(false);
  });

  it('passes OIDC_PROMPT on, and signs out locally when the provider has no sign-out page (Google)', async () => {
    cfg.prompt = 'select_account';
    const login = await get(`${APP}/auth/login`);
    expect(new URL(login.headers.get('location')).searchParams.get('prompt')).toBe('select_account');
    cfg.prompt = null;
    expect(new URL((await get(`${APP}/auth/login`)).headers.get('location')).searchParams.has('prompt')).toBe(false);
    noEndSession = true;
    resetProviderCache();
    const out = await get(`${APP}/auth/logout`);
    noEndSession = false;
    resetProviderCache();
    expect(out.headers.get('location')).toBe('/auth/signed-out');
    expect(out.headers.getSetCookie()[0]).toMatch(/Max-Age=0/);
  });

  it('never returns to another site', async () => {
    for (const ret of ['//evil.example', '/\\evil.example', 'https://evil.example']) {
      const login = await get(`${APP}/auth/login?return=${encodeURIComponent(ret)}`);
      const flow = cookieOf(login);
      const back = await get(new URL(login.headers.get('location')));
      const done = await get(back.headers.get('location'), { cookie: flow });
      expect(done.headers.get('location')).toBe('/');
    }
  });

  it('signs out here and at the provider', async () => {
    const out = await get(`${APP}/auth/logout`);
    expect(out.headers.get('location')).toMatch(new RegExp(`^${IDP}/logout\\?client_id=client&post_logout_redirect_uri=`));
    expect(out.headers.getSetCookie()[0]).toMatch(/__Host-mf_session=; .*Max-Age=0/);
    provider.email = 'siva@example.com';
    const cookie = cookieOf(await signIn());
    const out2 = new URL((await get(`${APP}/auth/logout`, { cookie })).headers.get('location'));
    expect(out2.searchParams.get('id_token_hint')).toMatch(/^ey/);
    expect(JSON.stringify(await (await get(`${APP}/auth/me`, { cookie })).json())).not.toMatch(/ey/);
  });
});
