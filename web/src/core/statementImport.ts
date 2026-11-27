// Port of core/StatementImport.kt: stable keys per statement line, and matching against what is already stored.

import { LocalDate } from './dates';
import { nameUuid, sha1Hex } from './hash';
import { fxFind, SelfTransfers, sameForeign } from './rules';
import { Narration, ParsedStatement, PayeeNames, StatementRow } from './statementParser';
import { TxnType, before, digitsOnly, removePrefix } from './text';

export type DuplicateReason = 'SAME_STATEMENT' | 'REFERENCE' | 'MATCHED';
export const duplicateLabel: Record<DuplicateReason, string> = {
  SAME_STATEMENT: 'already imported',
  REFERENCE: 'same as an entry (reference), statement details used',
  MATCHED: 'same as an entry, statement details used',
};

export interface ImportCandidate {
  row: StatementRow;
  key: string;
  uid: string;
  timestamp: number;
  merchant: string | null;
  bank: string | null;
  accountTail: string | null;
  duplicateOf: DuplicateReason | null;
  matchedId: number | null;
  cardStatement: boolean;
  ownTransfer: boolean;
}

export function notCounted(c: ImportCandidate): boolean {
  return c.ownTransfer || SelfTransfers.isCardBillPayment(c.row.type, c.merchant, c.row.description, c.cardStatement);
}

export function updatesExisting(c: ImportCandidate): boolean {
  return c.matchedId != null && c.duplicateOf !== 'SAME_STATEMENT';
}

export interface ExistingTxn {
  amountMinor: number;
  type: TxnType;
  timestamp: number;
  accountTail: string | null;
  reference: string | null;
  key: string | null;
  id: number;
  fromStatement: boolean;
  text: string | null;
}

const tail4 = (s: string | null | undefined) => {
  if (s == null) return null;
  const d = digitsOnly(s).slice(-4);
  return d === '' ? null : d;
};

export function accountsIn(s: ParsedStatement): [string, number][] {
  const m = new Map<string, number>();
  for (const r of s.rows) if (r.cardTail != null) m.set(r.cardTail, (m.get(r.cardTail) ?? 0) + 1);
  return [...m.entries()];
}

export function candidates(
  st: ParsedStatement,
  bank: string | null = st.bank,
  accountTail: string | null = st.accountTail,
  renames: Map<string, string> = new Map(),
): ImportCandidate[] {
  const tail = tail4(accountTail);
  const seen = new Map<string, number>();
  const several = accountsIn(st).length > 1;
  return st.rows.map((r) => {
    const printed = r.cardTail != null ? renames.get(r.cardTail) ?? r.cardTail : null;
    const rowTail = several || st.kind === 'CARD' ? printed ?? tail : tail ?? printed;
    const base = [
      rowTail ?? bank ?? '', r.date.toString(), String(r.amountMinor), r.type,
      r.description.toLowerCase().replace(/[^a-z0-9]+/g, ''),
    ].join('|');
    const n = (seen.get(base) ?? 0) + 1;
    seen.set(base, n);
    const hash = sha1Hex(`${base}|${n}`).slice(0, 24);
    const t = r.time;
    const ts = r.date.atTime(t ? t.hour : 12, t ? t.minute : 0, t ? t.second : 0);
    return {
      row: r, key: `STMT|${hash}`, uid: nameUuid(`stmt|${hash}`), timestamp: ts,
      merchant: Narration.merchant(r.description), bank, accountTail: rowTail,
      duplicateOf: null, matchedId: null, cardStatement: st.kind === 'CARD', ownTransfer: false,
    };
  });
}

/** Two account numbers (last digits) can be the same account: either unknown, equal, or one ends the other. */
export function sameAccount(a: string | null | undefined, b: string | null | undefined): boolean {
  const x = a != null ? digitsOnly(a) : '';
  const y = b != null ? digitsOnly(b) : '';
  if (x === '' || y === '') return true;
  if (x === y) return true;
  const [s, l] = x.length <= y.length ? [x, y] : [y, x];
  return s.length >= 3 && l.endsWith(s);
}

const debitCardWords = /\bdebit\s*card\b/i;
const creditCardWords = /\bcredit\s*card\b/i;
const debitCardNarration = /^(DEBIT CARD\b|ICICI ATM\b|OTHER ATMS\b)|\b(VPS|IPS|MPS|PCD|POS|ATM|NFS|VIN|DCARD)\/|\bPOS\s|\bATM\s?WDL|CASH WDL/i;

function debitCardSms(text: string | null) {
  return text != null && debitCardWords.test(text) && !creditCardWords.test(text);
}
function debitCardLine(n: string) {
  return debitCardNarration.test(n.trim());
}

/** The account to keep when a statement line and its SMS name different ones. */
export function keptTail(lineTail: string | null, smsTail: string | null, smsText: string | null): string | null {
  if (lineTail != null && smsTail != null && !sameAccount(lineTail, smsTail) && !debitCardSms(smsText) && !SelfTransfers.isCardPayment(null, smsText)) return smsTail;
  return lineTail ?? smsTail;
}

function words(s: string): string {
  return before(removePrefix(s, 'Statement: '), '\n\nSMS: ').toLowerCase().split(/[^a-z]+/).filter((w) => w.length >= 2).join(' ');
}

/** True when two narrations are the same statement line, ignoring numbers, reward points and spacing. */
export function sameLine(a: string, b: string | null): boolean {
  if (b == null) return false;
  const x = words(a);
  const y = words(b);
  if (x === '' || y === '') return false;
  if (x.includes(y) || y.includes(x)) return true;
  const xs = new Set(x.split(' '));
  const ys = new Set(y.split(' '));
  let inter = 0;
  for (const w of xs) if (ys.has(w)) inter++;
  return inter * 10 >= Math.min(xs.size, ys.size) * 7;
}

export function samePayeeCode(narration: string | null, smsText: string | null): boolean {
  if (narration == null || smsText == null) return false;
  const sms = smsText.toLowerCase();
  return before(removePrefix(narration, 'Statement: '), '\n\nSMS: ').split(/[^A-Za-z0-9]+/)
    .filter((w) => w.length >= 12 && PayeeNames.isCode(w))
    .some((w) => sms.includes(w.toLowerCase().slice(0, 12)));
}

const FX_TOLERANCE_PCT = 15;
const dayOf = (ts: number) => LocalDate.fromMillis(ts).toEpochDay();

function linked(c: ImportCandidate, e: ExistingTxn, reason: DuplicateReason): ImportCandidate {
  return e.id > 0 && !e.fromStatement ? { ...c, duplicateOf: reason, matchedId: e.id } : { ...c, duplicateOf: 'SAME_STATEMENT' };
}

/**
 * Marks the candidates already present: imported before (same key), the same UPI / IMPS reference as a stored
 * entry, or the same amount and direction on the same account within [windowDays] days, one to one.
 */
export function markDuplicates(cands: ImportCandidate[], existing: ExistingTxn[], windowDays = 2): ImportCandidate[] {
  const keys = new Set(existing.map((e) => e.key).filter((k): k is string => k != null));
  const used = existing.map(() => false);
  const byAmount = new Map<string, number[]>();
  existing.forEach((e, i) => { const k = `${e.amountMinor}|${e.type}`; const a = byAmount.get(k); if (a) a.push(i); else byAmount.set(k, [i]); });

  const firstTail = new Map<string, (string | null)[]>();
  const repeatOnOtherCard = cands.map(() => false);
  cands.forEach((c, i) => {
    const k = JSON.stringify([c.row.date.toString(), c.row.amountMinor, c.row.type, words(c.row.description)]);
    let tails = firstTail.get(k);
    if (!tails) { tails = []; firstTail.set(k, tails); }
    if (c.accountTail != null && tails.some((t) => t != null && t !== c.accountTail) && !tails.includes(c.accountTail)) repeatOnOtherCard[i] = true;
    else tails.push(c.accountTail);
  });

  const result = cands.map((c, ci) => {
    if (repeatOnOtherCard[ci]) return { ...c, duplicateOf: 'SAME_STATEMENT' as DuplicateReason };
    if (keys.has(c.key)) return { ...c, duplicateOf: 'SAME_STATEMENT' as DuplicateReason };
    const ref = c.row.reference != null && c.row.reference.length >= 6 ? c.row.reference : null;
    if (ref != null) {
      const hit = (byAmount.get(`${c.row.amountMinor}|${c.row.type}`) ?? []).find((i) => {
        if (used[i]) return false;
        const r = existing[i].reference;
        return r != null && (r === ref || (ref.length >= 10 && r.endsWith(ref)) || (r.length >= 10 && ref.endsWith(r)));
      });
      if (hit != null) { used[hit] = true; return linked(c, existing[hit], 'REFERENCE'); }
    }
    return c;
  });

  const foreignSms: [number, ReturnType<typeof fxFind>][] = [];
  existing.forEach((e, i) => {
    if (e.fromStatement || e.key?.startsWith('STMT|') || e.text == null) return;
    const f = fxFind(e.text);
    if (f) foreignSms.push([i, f]);
  });
  const pairs: [number, number, number][] = [];
  result.forEach((c, ci) => {
    if (c.duplicateOf != null) return;
    const cd = c.row.date.toEpochDay();
    for (const i of byAmount.get(`${c.row.amountMinor}|${c.row.type}`) ?? []) {
      if (used[i]) continue;
      const e = existing[i];
      const et = tail4(e.accountTail);
      const gap = Math.abs(dayOf(e.timestamp) - cd);
      if (!sameAccount(c.accountTail, et) && !(e.fromStatement && sameLine(c.row.description, e.text)) &&
        !(!e.fromStatement && !c.cardStatement && debitCardSms(e.text) && debitCardLine(c.row.description)) &&
        !(!e.fromStatement && gap <= 1 && samePayeeCode(c.row.description, e.text))) continue;
      if (gap <= windowDays) pairs.push([ci, i, gap * 10 + (et != null && c.accountTail != null && sameAccount(et, c.accountTail) ? 0 : 1)]);
    }
    for (const [i, fx] of foreignSms) {
      if (used[i]) continue;
      const e = existing[i];
      if (e.type !== c.row.type || e.amountMinor === c.row.amountMinor) continue;
      const rf = c.row.foreign != null ? fxFind(c.row.foreign) : null;
      const exact = rf != null && fx != null && sameForeign(rf[0], fx[0]);
      const close = c.row.foreign == null && Math.abs(e.amountMinor - c.row.amountMinor) * 100 <= c.row.amountMinor * FX_TOLERANCE_PCT;
      const et = tail4(e.accountTail);
      if (!(exact || close) || c.accountTail == null || et == null || !sameAccount(c.accountTail, et)) continue;
      const gap = Math.abs(dayOf(e.timestamp) - cd);
      if (gap <= windowDays) pairs.push([ci, i, gap * 10 + (exact ? 2 : 5)]);
    }
  });
  const taken = result.map(() => false);
  for (const [ci, i] of pairs.sort((a, b) => a[2] - b[2])) {
    if (taken[ci] || used[i]) continue;
    taken[ci] = true;
    used[i] = true;
    result[ci] = linked(result[ci], existing[i], 'MATCHED');
  }
  return result;
}

function names(text: string, tail: string | null): boolean {
  return tail != null && new RegExp(`(?:[Xx*•]|\\d)${tail}(?!\\d)`).test(text);
}

/** Money moved between the user's own accounts when both sides are being imported. */
export function markOwnTransfers(list: ImportCandidate[], ownTails: Set<string>, windowDays = 2): ImportCandidate[] {
  const fresh = list.map((_, i) => i).filter((i) => list[i].duplicateOf == null && list[i].accountTail != null && ownTails.has(list[i].accountTail!));
  const credits = new Map<number, number[]>();
  for (const i of fresh) if (list[i].row.type === 'CREDIT') { const a = credits.get(list[i].row.amountMinor); if (a) a.push(i); else credits.set(list[i].row.amountMinor, [i]); }
  const used = new Set<number>();
  const out = list.slice();
  const debits = fresh.filter((i) => list[i].row.type === 'DEBIT').sort((a, b) => list[a].timestamp - list[b].timestamp);
  for (const d of debits) {
    const dc = list[d];
    const opts = (credits.get(dc.row.amountMinor) ?? []).filter((i) => !used.has(i) && list[i].accountTail !== dc.accountTail &&
      Math.abs(list[i].row.date.toEpochDay() - dc.row.date.toEpochDay()) <= windowDays)
      .filter((i) => {
        const cc = list[i];
        return (SelfTransfers.looksLikeTransfer(dc.row.description) && SelfTransfers.looksLikeTransfer(cc.row.description)) ||
          names(dc.row.description, cc.accountTail) || names(cc.row.description, dc.accountTail);
      });
    if (opts.length === 0) continue;
    const c = opts.reduce((b, i) => (Math.abs(list[i].timestamp - dc.timestamp) < Math.abs(list[b].timestamp - dc.timestamp) ? i : b), opts[0]);
    used.add(c);
    out[d] = { ...dc, ownTransfer: true };
    out[c] = { ...list[c], ownTransfer: true };
  }
  return out;
}

export interface ImportSummary {
  newCount: number;
  updateCount: number;
  duplicateCount: number;
  spentMinor: number;
  receivedMinor: number;
  notCountedMinor: number;
  notCountedCount: number;
  from: LocalDate | null;
  to: LocalDate | null;
}

export function summary(list: ImportCandidate[]): ImportSummary {
  const fresh = list.filter((c) => c.duplicateOf == null);
  const updates = list.filter(updatesExisting).length;
  let from: LocalDate | null = null;
  let to: LocalDate | null = null;
  for (const c of list) {
    if (!from || c.row.date.isBefore(from)) from = c.row.date;
    if (!to || c.row.date.isAfter(to)) to = c.row.date;
  }
  const sum = (l: ImportCandidate[]) => l.reduce((s, c) => s + c.row.amountMinor, 0);
  return {
    newCount: fresh.length,
    updateCount: updates,
    duplicateCount: list.length - fresh.length - updates,
    spentMinor: sum(fresh.filter((c) => c.row.type === 'DEBIT' && !notCounted(c))),
    receivedMinor: sum(fresh.filter((c) => c.row.type === 'CREDIT' && !notCounted(c))),
    notCountedMinor: sum(fresh.filter(notCounted)),
    notCountedCount: fresh.filter(notCounted).length,
    from, to,
  };
}

/** Statement lines stored twice under two card numbers: the ids to remove (never an edited one). */
export interface StoredLine { id: number; day: number; amountMinor: number; type: TxnType; text: string | null; tail: string | null; edited: boolean }

export function storedRepeats(lines: StoredLine[]): number[] {
  const out: number[] = [];
  const groups = new Map<string, StoredLine[]>();
  for (const l of lines) {
    if (l.tail == null || l.text == null || l.text.trim() === '') continue;
    const k = JSON.stringify([l.day, l.amountMinor, l.type, words(l.text)]);
    const a = groups.get(k); if (a) a.push(l); else groups.set(k, [l]);
  }
  for (const gr of groups.values()) {
    const byTail = new Map<string, StoredLine[]>();
    for (const l of gr) { const a = byTail.get(l.tail!); if (a) a.push(l); else byTail.set(l.tail!, [l]); }
    if (byTail.size < 2) continue;
    let keep: string | null = null;
    let best: [number, number] = [-1, -1];
    for (const [t, l] of byTail) {
      const s: [number, number] = [l.some((x) => x.edited) ? 1 : 0, Math.max(...l.map((x) => x.id))];
      if (s[0] > best[0] || (s[0] === best[0] && s[1] > best[1])) { best = s; keep = t; }
    }
    for (const [t, l] of byTail) if (t !== keep) for (const x of l) if (!x.edited) out.push(x.id);
  }
  return out;
}
