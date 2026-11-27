// Sign-in for the hosted web app: OpenID Connect (authorization code flow with PKCE), using only Node's built-ins.
// Works with any OIDC provider: Okta, Auth0, Keycloak, Google, Microsoft Entra ID…
//
// Config (Heroku config vars). Sign-in is on when OIDC_ISSUER is set; then the others are required:
//   OIDC_ISSUER         e.g. https://dev-123456.okta.com/oauth2/default, https://your-tenant.auth0.com/,
//                       https://keycloak.example.com/realms/family
//   OIDC_CLIENT_ID      the app's client ID at the provider
//   OIDC_CLIENT_SECRET  its client secret
//   ALLOWED_EMAILS      who may use the app, comma separated (e.g. siva@example.com,asha@example.com)
//   SESSION_SECRET      a long random string that signs the sign-in cookie (openssl rand -hex 32)
// Optional:
//   APP_URL             the app's address, e.g. https://myfinance-siva.herokuapp.com (default: from the request)
//   SESSION_DAYS        how long a sign-in lasts (default 30)
//   OIDC_SCOPES         default "openid email profile"
//   OIDC_PROMPT         e.g. "select_account" (Google: lets each family member pick their own account)
//
// What it protects: the app's files on this server. It can't reach data already stored on a phone.

import crypto from 'node:crypto';

const COOKIE = '__Host-mf_session';
const FLOW_COOKIE = '__Host-mf_signin';
const CLOCK_SKEW = 120; // seconds

// Files the browser or iOS fetches on its own when installing the app, sometimes without cookies.
// None of them hold anything private.
export const PUBLIC_FILES = new Set([
  '/manifest.webmanifest', '/icon.svg', '/icon-192.png', '/icon-512.png', '/apple-touch-icon.png',
  '/apple-touch-icon-precomposed.png', '/favicon.ico', '/robots.txt',
]);

const b64url = (buf) => Buffer.from(buf).toString('base64url');
const sha256 = (s) => crypto.createHash('sha256').update(s).digest();

export function authConfig(env = process.env) {
  if (!env.OIDC_ISSUER) return null;
  const missing = ['OIDC_CLIENT_ID', 'OIDC_CLIENT_SECRET', 'ALLOWED_EMAILS', 'SESSION_SECRET'].filter((k) => !env[k]);
  if (missing.length) throw new Error(`Sign-in is on (OIDC_ISSUER is set) but these config vars are missing: ${missing.join(', ')}`);
  if (env.SESSION_SECRET.length < 32) throw new Error('SESSION_SECRET must be at least 32 characters (try: openssl rand -hex 32)');
  const allowed = new Set(env.ALLOWED_EMAILS.split(/[,\s]+/).map((e) => e.trim().toLowerCase()).filter(Boolean));
  if (!allowed.size) throw new Error('ALLOWED_EMAILS has no addresses');
  return {
    issuer: env.OIDC_ISSUER.trim(),
    clientId: env.OIDC_CLIENT_ID.trim(),
    clientSecret: env.OIDC_CLIENT_SECRET.trim(),
    allowed,
    secret: env.SESSION_SECRET,
    appUrl: env.APP_URL ? env.APP_URL.trim().replace(/\/+$/, '') : null,
    sessionSeconds: Math.round(Number(env.SESSION_DAYS ?? 30) * 86400) || 30 * 86400,
    scopes: env.OIDC_SCOPES?.trim() || 'openid email profile',
    prompt: env.OIDC_PROMPT?.trim() || null,
  };
}

// ---- Signed cookies -------------------------------------------------------------------------------------------

function sign(cfg, payload) {
  const body = b64url(JSON.stringify(payload));
  const mac = b64url(crypto.createHmac('sha256', cfg.secret).update(body).digest());
  return `${body}.${mac}`;
}

function unsign(cfg, value) {
  if (!value) return null;
  const [body, mac] = value.split('.');
  if (!body || !mac) return null;
  const want = crypto.createHmac('sha256', cfg.secret).update(body).digest();
  const got = Buffer.from(mac, 'base64url');
  if (got.length !== want.length || !crypto.timingSafeEqual(got, want)) return null;
  try {
    const p = JSON.parse(Buffer.from(body, 'base64url').toString());
    return p.exp && p.exp > Date.now() / 1000 ? p : null;
  } catch { return null; }
}

function cookies(req) {
  const out = {};
  for (const part of (req.headers.cookie ?? '').split(';')) {
    const i = part.indexOf('=');
    if (i <= 0) continue;
    try { out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim()); } catch { /* not ours */ }
  }
  return out;
}

// Lax, not Strict: the provider sends the browser back to /auth/callback from its own site.
const setCookie = (name, value, maxAge) =>
  `${name}=${encodeURIComponent(value)}; Path=/; Max-Age=${maxAge}; HttpOnly; Secure; SameSite=Lax`;

export function session(cfg, req) {
  return unsign(cfg, cookies(req)[COOKIE]);
}

// ---- Provider metadata and keys -------------------------------------------------------------------------------

let meta = null;
let metaAt = 0;
let jwks = null;
let jwksAt = 0;

async function getJson(url, init) {
  const r = await fetch(url, { ...init, signal: AbortSignal.timeout(10_000) });
  const text = await r.text();
  let json;
  try { json = JSON.parse(text); } catch { throw new Error(`${new URL(url).host} answered ${r.status} without JSON`); }
  if (!r.ok) throw new Error(`${new URL(url).host} answered ${r.status}: ${json.error ?? ''} ${json.error_description ?? ''}`.trim());
  return json;
}

/** Forget the provider's settings and keys (after changing provider; used by tests). */
export function resetProviderCache() { meta = null; jwks = null; }

async function discover(cfg) {
  if (meta && Date.now() - metaAt < 3600_000) return meta;
  const m = await getJson(`${cfg.issuer.replace(/\/+$/, '')}/.well-known/openid-configuration`);
  for (const k of ['issuer', 'authorization_endpoint', 'token_endpoint', 'jwks_uri']) {
    if (!m[k]) throw new Error(`The provider's settings have no ${k}`);
  }
  meta = m;
  metaAt = Date.now();
  return m;
}

async function keyFor(m, kid, refresh = false) {
  if (!jwks || refresh || Date.now() - jwksAt > 3600_000) {
    jwks = (await getJson(m.jwks_uri)).keys ?? [];
    jwksAt = Date.now();
  }
  const jwk = jwks.find((k) => (kid ? k.kid === kid : true) && (k.use ?? 'sig') === 'sig');
  if (!jwk && !refresh) return keyFor(m, kid, true); // the provider may have rotated its keys
  if (!jwk) throw new Error('No matching signing key from the provider');
  return crypto.createPublicKey({ key: jwk, format: 'jwk' });
}

const ALGS = {
  RS256: ['sha256', {}], RS384: ['sha384', {}], RS512: ['sha512', {}],
  PS256: ['sha256', { padding: crypto.constants.RSA_PKCS1_PSS_PADDING, saltLength: 32 }],
  ES256: ['sha256', { dsaEncoding: 'ieee-p1363' }], ES384: ['sha384', { dsaEncoding: 'ieee-p1363' }],
};

// Google may write its issuer without "https://" (its documentation allows both forms).
export function issuerMatches(iss, expected) {
  if (iss === expected) return true;
  return expected === 'https://accounts.google.com' && iss === 'accounts.google.com';
}

export async function verifyIdToken(cfg, m, token, nonce) {
  const [h, p, s] = token.split('.');
  if (!h || !p || !s) throw new Error('ID token is malformed');
  const header = JSON.parse(Buffer.from(h, 'base64url').toString());
  const alg = ALGS[header.alg];
  if (!alg) throw new Error(`ID token uses an unsupported algorithm (${header.alg})`);
  const key = await keyFor(m, header.kid);
  const ok = crypto.verify(alg[0], Buffer.from(`${h}.${p}`), { key, ...alg[1] }, Buffer.from(s, 'base64url'));
  if (!ok) throw new Error('ID token signature is not valid');
  const c = JSON.parse(Buffer.from(p, 'base64url').toString());
  const now = Date.now() / 1000;
  if (!issuerMatches(c.iss, m.issuer)) throw new Error('ID token is from another issuer');
  const aud = Array.isArray(c.aud) ? c.aud : [c.aud];
  if (!aud.includes(cfg.clientId)) throw new Error('ID token is for another app');
  if (aud.length > 1 && c.azp && c.azp !== cfg.clientId) throw new Error('ID token is for another app');
  if (!(c.exp > now - CLOCK_SKEW)) throw new Error('ID token has expired');
  if (c.iat && c.iat > now + CLOCK_SKEW) throw new Error('ID token is from the future');
  if (c.nonce !== nonce) throw new Error('ID token does not match this sign-in');
  return c;
}

// ---- Routes ---------------------------------------------------------------------------------------------------

function origin(cfg, req) {
  return cfg.appUrl ?? `https://${req.headers.host}`;
}

// Only paths on this site, never "//other.site" or "/\other.site".
function safeReturn(p) {
  return typeof p === 'string' && /^\/(?![/\\])/.test(p) && !/[\r\n]/.test(p) ? p : '/';
}

function page(res, status, title, text, extraHeaders = {}) {
  const esc = (s) => String(s).replace(/[&<>"]/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[ch]);
  res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', ...extraHeaders });
  res.end(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title><style>body{font:16px/1.5 system-ui,sans-serif;max-width:28rem;margin:15vh auto;padding:0 16px;color:#1c2421;background:#f6f7f9}
@media (prefers-color-scheme:dark){body{color:#e6ece9;background:#0b1412}}a{color:#0f766e;font-weight:600}</style></head>
<body><h1 style="font-size:1.3rem">${esc(title)}</h1><p>${esc(text)}</p><p><a href="/auth/login">Sign in again</a></p></body></html>`);
}

async function login(cfg, req, res, url) {
  const m = await discover(cfg);
  const state = b64url(crypto.randomBytes(16));
  const nonce = b64url(crypto.randomBytes(16));
  const verifier = b64url(crypto.randomBytes(32));
  const ret = safeReturn(url.searchParams.get('return'));
  const q = new URLSearchParams({
    response_type: 'code',
    client_id: cfg.clientId,
    redirect_uri: `${origin(cfg, req)}/auth/callback`,
    scope: cfg.scopes,
    state,
    nonce,
    code_challenge: b64url(sha256(verifier)),
    code_challenge_method: 'S256',
  });
  if (cfg.prompt) q.set('prompt', cfg.prompt);
  const flow = sign(cfg, { state, nonce, verifier, ret, exp: Math.floor(Date.now() / 1000) + 600 });
  res.writeHead(302, { Location: `${m.authorization_endpoint}?${q}`, 'Set-Cookie': setCookie(FLOW_COOKIE, flow, 600), 'Cache-Control': 'no-store' });
  res.end();
}

async function callback(cfg, req, res, url) {
  const clearFlow = { 'Set-Cookie': setCookie(FLOW_COOKIE, '', 0) };
  const flow = unsign(cfg, cookies(req)[FLOW_COOKIE]);
  if (url.searchParams.get('error')) {
    return page(res, 401, 'Sign-in was not completed', url.searchParams.get('error_description') || url.searchParams.get('error'), clearFlow);
  }
  if (!flow || flow.state !== url.searchParams.get('state')) {
    return page(res, 400, 'Sign-in expired', 'That sign-in took too long or was started in another window. Please try again.', clearFlow);
  }
  const m = await discover(cfg);
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code: url.searchParams.get('code') ?? '',
    redirect_uri: `${origin(cfg, req)}/auth/callback`,
    code_verifier: flow.verifier,
    client_id: cfg.clientId,
    client_secret: cfg.clientSecret,
  });
  const tokens = await getJson(m.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body,
  });
  if (!tokens.id_token) throw new Error('The provider sent no ID token (is "openid" in the scopes?)');
  const c = await verifyIdToken(cfg, m, tokens.id_token, flow.nonce);
  const email = String(c.email ?? '').toLowerCase();
  if (!email || c.email_verified === false || !cfg.allowed.has(email)) {
    console.log(`sign-in refused for an account not on ALLOWED_EMAILS${c.email_verified === false ? ' (email not verified)' : ''}`);
    return page(res, 403, 'Not allowed', `${email || 'This account'} is not on this app's family list. Ask the app's owner to add it.`, clearFlow);
  }
  const exp = Math.floor(Date.now() / 1000) + cfg.sessionSeconds;
  // The ID token goes along only so that sign-out can name the session to end (Okta needs it).
  const cookie = sign(cfg, { email, name: c.name ?? c.given_name ?? '', exp, idt: tokens.id_token });
  res.writeHead(302, {
    Location: flow.ret,
    'Set-Cookie': [setCookie(COOKIE, cookie, cfg.sessionSeconds), clearFlow['Set-Cookie']],
    'Cache-Control': 'no-store',
  });
  res.end();
}

async function logout(cfg, req, res) {
  let to = '/auth/signed-out';
  try {
    const m = await discover(cfg);
    // Also end the provider's session, or "Sign in" would let the same account straight back in.
    if (m.end_session_endpoint) {
      const q = new URLSearchParams({ client_id: cfg.clientId, post_logout_redirect_uri: `${origin(cfg, req)}/auth/signed-out` });
      const idt = session(cfg, req)?.idt;
      if (idt) q.set('id_token_hint', idt);
      to = `${m.end_session_endpoint}?${q}`;
    }
  } catch (e) { console.warn('sign-out: provider not reachable', e.message); }
  res.writeHead(302, { Location: to, 'Set-Cookie': setCookie(COOKIE, '', 0), 'Cache-Control': 'no-store' });
  res.end();
}

/**
 * Handles /auth/* and refuses everything else without a sign-in.
 * Returns true when it answered the request, false when the request may go on to the files.
 */
export async function guard(cfg, req, res, url) {
  const p = url.pathname;
  try {
    if (p === '/auth/login') { await login(cfg, req, res, url); return true; }
    if (p === '/auth/callback') { await callback(cfg, req, res, url); return true; }
    if (p === '/auth/logout') { await logout(cfg, req, res); return true; }
  } catch (e) {
    console.error('sign-in error:', e.message);
    page(res, 502, 'Sign-in problem', `Could not finish signing in: ${e.message}`);
    return true;
  }
  if (p === '/auth/signed-out') { page(res, 200, 'Signed out', 'You are signed out of MyFinance on this device.'); return true; }
  const s = session(cfg, req);
  if (p === '/auth/me') {
    res.writeHead(s ? 200 : 401, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(s ? { email: s.email, name: s.name, until: s.exp * 1000 } : { signedIn: false }));
    return true;
  }
  if (s || PUBLIC_FILES.has(p)) return false;
  // A page load goes to sign-in; files (scripts, the service worker…) just get 401.
  const isPage = (req.headers['sec-fetch-mode'] === 'navigate' || (req.headers.accept ?? '').includes('text/html')) && !/\.(js|mjs|css|json|png|svg|woff2|map|wasm)$/.test(p);
  if (isPage) {
    res.writeHead(302, { Location: `/auth/login?return=${encodeURIComponent(p + url.search)}`, 'Cache-Control': 'no-store' });
  } else {
    res.writeHead(401, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
    res.write('Sign in first');
  }
  res.end();
  return true;
}
