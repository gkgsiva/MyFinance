import { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { formatInr } from '../core/text';
import { formatDateMs } from '../core/dates';
import { Txn, store } from '../store/store';

// ---- Navigation: the screen lives in the URL hash, so the iPhone's back swipe works ----

export function useRoute(): string {
  const [r, setR] = useState(location.hash.slice(1) || 'home');
  useEffect(() => {
    const f = () => { setR(location.hash.slice(1) || 'home'); window.scrollTo(0, 0); };
    addEventListener('hashchange', f);
    return () => removeEventListener('hashchange', f);
  }, []);
  return r;
}

export function go(route: string) { location.hash = route; }
export function back(fallback = 'home') { if (history.length > 1) history.back(); else go(fallback); }

// ---- Toast with an optional action (Undo) ----

type ToastMsg = { text: string; action?: { label: string; run: () => void } } | null;
let toastSet: ((t: ToastMsg) => void) | null = null;
let toastTimer: number | undefined;

export function toast(text: string, action?: { label: string; run: () => void }) {
  toastSet?.({ text, action });
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toastSet?.(null), action ? 6000 : 3000);
}

export function ToastHost() {
  const [t, setT] = useState<ToastMsg>(null);
  useEffect(() => { toastSet = setT; return () => { toastSet = null; }; }, []);
  if (!t) return null;
  return (
    <div class="toast" role="status">
      <span class="grow">{t.text}</span>
      {t.action && <button onClick={() => { t.action!.run(); setT(null); }}>{t.action.label}</button>}
    </div>
  );
}

// ---- Bottom sheet ----

export function Sheet({ onClose, children, title }: { onClose: () => void; children: ComponentChildren; title?: string }) {
  useEffect(() => {
    const f = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    addEventListener('keydown', f);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { removeEventListener('keydown', f); document.body.style.overflow = prev; };
  }, []);
  return (
    <div class="sheet-back" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div class="sheet" role="dialog" aria-modal="true" aria-label={title}>
        <div class="grab" />
        {title && <h2>{title}</h2>}
        {children}
      </div>
    </div>
  );
}

// ---- Bits ----

export function Money({ minor, type, signed = false, class: cls = '' }: { minor: number; type?: 'DEBIT' | 'CREDIT'; signed?: boolean; class?: string }) {
  const sign = signed && type ? (type === 'CREDIT' ? '+' : '−') : '';
  const c = type === 'CREDIT' ? 'in' : '';
  return <span class={`num ${c} ${cls}`}>{sign}{formatInr(Math.abs(minor))}</span>;
}

export function inr(minor: number) { return formatInr(minor); }

const AVATAR_COLORS = ['#257de0', '#f47230', '#00a888', '#d28e05', '#e24d8b', '#0aac23', '#6a4ce2', '#ec4647'];

export function Avatar({ name }: { name: string }) {
  const letter = (name.trim()[0] ?? '?').toUpperCase();
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  const c = AVATAR_COLORS[h % AVATAR_COLORS.length];
  return <div class="avatar" style={{ background: `color-mix(in srgb, ${c} 18%, var(--surface))`, color: c }} aria-hidden="true">{letter}</div>;
}

export function displayName(t: Txn): string {
  return (t.merchant && t.merchant.trim()) || t.category || (t.type === 'CREDIT' ? 'Money in' : 'Payment');
}

/** One entry in a list: name, category and account, amount. */
export function TxnRow({ t, balance, onClick, showDate = false }: { t: Txn; balance?: number; onClick?: () => void; showDate?: boolean }) {
  const name = displayName(t);
  const bits = [t.category, store.accountName(t.accountTail, t.bank)];
  if (t.owner) bits.unshift(t.owner);
  if (showDate) bits.unshift(formatDateMs(t.timestamp, true));
  return (
    <button class={`txn ${t.excluded ? 'left-out' : ''}`} onClick={onClick}>
      <Avatar name={name} />
      <div class="grow">
        <div class="ellipsis">{name}</div>
        <div class="tiny ellipsis">
          {t.excluded && <span class="tag">not counted</span>}
          {t.hidden && <span class="tag">hidden</span>}
          {t.labels.map((l) => <span class="tag" key={l}>{l}</span>)}
          {bits.join(' · ')}
        </div>
      </div>
      <div class="right">
        <div class="amt"><Money minor={t.amountMinor} type={t.type} signed /></div>
        {balance != null && <div class="bal num">{formatInr(balance)}</div>}
      </div>
    </button>
  );
}

export function Empty({ icon, title, children }: { icon: string; title: string; children?: ComponentChildren }) {
  return (
    <div class="empty">
      <div class="big" aria-hidden="true">{icon}</div>
      <p><b>{title}</b></p>
      {children}
    </div>
  );
}

/** Lets the user save or share a file: the share sheet on iPhone (WhatsApp, Files, Mail), else a download. */
export async function shareFile(name: string, data: BlobPart, type: string): Promise<boolean> {
  const file = new File([data], name, { type });
  const nav = navigator as Navigator & { canShare?: (d: { files: File[] }) => boolean };
  if (nav.canShare?.({ files: [file] })) {
    try {
      await nav.share({ files: [file], title: name });
      return true;
    } catch (e) {
      if ((e as Error).name === 'AbortError') return false;
    }
  }
  const url = URL.createObjectURL(file);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
  return true;
}

export function pickFiles(accept: string, multiple = true): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.multiple = multiple;
    input.style.display = 'none';
    input.onchange = () => { resolve([...(input.files ?? [])]); input.remove(); };
    document.body.appendChild(input);
    input.click();
  });
}

export const Icons = {
  home: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z" /></svg>,
  list: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01" /></svg>,
  chart: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 20V10M10 20V4M16 20v-7M22 20H2" /></svg>,
  upload: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 16V4M7 9l5-5 5 5M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" /></svg>,
  more: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9" /><path d="M8 12h.01M12 12h.01M16 12h.01" /></svg>,
};
