// Serves the built app (web/dist) on Heroku or any Node host. Only the app's code is served: entries stay on the
// phone, and statements stay in the private bucket. No packages needed, so nothing is installed at run time.
//
//   PORT   the port to listen on (Heroku sets it)
//   DIST   the folder to serve (default: dist next to this file)
//   OIDC_* sign-in with Okta, Auth0, Keycloak or any OpenID Connect provider: see auth.mjs and README.md

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { authConfig, guard } from './auth.mjs';

const root = path.resolve(process.env.DIST ?? path.join(path.dirname(fileURLToPath(import.meta.url)), 'dist'));
const port = Number(process.env.PORT ?? 8080);
// Throws (and the app won't start) when sign-in is half set up, so a missing var never leaves it open.
const auth = authConfig();
if (!auth) console.warn('Sign-in is off: anyone with the address can open the app. Set OIDC_ISSUER and the other vars to turn it on.');

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json', '.wasm': 'application/wasm',
};

const SECURITY = {
  'Strict-Transport-Security': 'max-age=31536000',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'X-Frame-Options': 'DENY',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
};

function send(res, status, headers, body) {
  res.writeHead(status, { ...SECURITY, ...headers });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  for (const [k, v] of Object.entries(SECURITY)) res.setHeader(k, v);
  if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, { Allow: 'GET, HEAD' }, '');
  // Heroku ends HTTPS at its router: plain HTTP is sent on to HTTPS (a service worker needs it anyway).
  if (req.headers['x-forwarded-proto'] === 'http') {
    return send(res, 301, { Location: `https://${req.headers.host}${req.url}` }, '');
  }
  let url, pathname;
  try { url = new URL(req.url, 'http://x'); pathname = decodeURIComponent(url.pathname); } catch { return send(res, 400, {}, ''); }
  if (auth) {
    if (await guard(auth, req, res, url)) return;
  } else if (pathname === '/auth/me') {
    return send(res, 404, { 'Content-Type': 'text/plain' }, 'Sign-in is off');
  }
  let file = path.join(root, pathname);
  if (!file.startsWith(root + path.sep) && file !== root) return send(res, 404, {}, '');
  if (pathname.endsWith('/')) file = path.join(file, 'index.html');
  fs.stat(file, (err, st) => {
    // The app routes with "#…", so any other unknown path gets the app itself.
    if (err || !st.isFile()) {
      if (path.extname(pathname)) return send(res, 404, { 'Content-Type': 'text/plain' }, 'Not found');
      file = path.join(root, 'index.html');
    }
    const ext = path.extname(file);
    const name = path.basename(file);
    // Hashed files never change; the page, service worker and manifest must always be checked.
    // Behind sign-in, only the browser may keep a copy, never a shared cache.
    const scope = auth ? 'private' : 'public';
    const cache = /^(index\.html|sw\.js|registerSW\.js|manifest\.webmanifest)$/.test(name) || ext === '.html'
      ? `${auth ? 'private, ' : ''}no-cache`
      : pathname.startsWith('/assets/') ? `${scope}, max-age=31536000, immutable` : `${scope}, max-age=3600`;
    res.writeHead(200, { ...SECURITY, 'Content-Type': TYPES[ext] ?? 'application/octet-stream', 'Cache-Control': cache });
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file).on('error', () => res.destroy()).pipe(res);
  });
});

server.listen(port, () => console.log(`MyFinance web app on port ${port}`));
