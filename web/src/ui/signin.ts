// Sign-in, when the server has it on (web/auth.mjs on Heroku). The offline copy of the app opens without the
// server, so when online the app asks the server whether this device is still signed in, and if not, goes to
// sign-in. Offline, or on a host without sign-in (GitHub Pages, local dev), nothing changes.

export interface SignedIn { email: string; name: string; until: number }

let current: SignedIn | null = null;
export const signedIn = () => current;

const base = import.meta.env.BASE_URL;

export async function checkSignIn(): Promise<void> {
  if (!navigator.onLine) return;
  let r: Response;
  try {
    r = await fetch(`${base}auth/me`, { cache: 'no-store', credentials: 'same-origin' });
  } catch {
    return; // offline or the server is asleep: keep using the app
  }
  if (r.status === 401) {
    location.replace(`${base}auth/login?return=${encodeURIComponent(location.pathname + location.search + location.hash)}`);
    return;
  }
  if (r.ok) {
    try { current = await r.json(); } catch { /* not the sign-in server */ }
  }
}

export const signOutUrl = () => `${base}auth/logout`;
