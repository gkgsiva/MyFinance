// Port of FamilySync.kt (CsvCodec, Dedupe): the export file the Android app shares and merges, so the iPhone and
// Android phones can swap data both ways.

import { merchantKey, Cat } from './rules';
import { TxnType, minorToPlain, parseAmountToMinor } from './text';

export interface SharedTxn {
  uid: string;
  amountMinor: number;
  type: TxnType;
  merchant: string | null;
  category: string;
  accountTail: string | null;
  bank: string | null;
  timestamp: number;
  note: string | null;
  owner: string | null;
  source: string;
  smsKey: string | null;
  reference: string | null;
  excluded: boolean;
  mode: string | null;
  labels: string[];
}

const SMS_WINDOW_MS = 15 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

export function isDuplicate(a: SharedTxn, b: SharedTxn): boolean {
  if (a.uid === b.uid) return true;
  if (a.amountMinor !== b.amountMinor || a.type !== b.type) return false;
  if (a.smsKey != null && a.smsKey === b.smsKey) return true;
  if (a.reference != null && a.reference.trim() !== '' && a.reference.length >= 6 && a.reference === b.reference) return true;
  const gap = Math.abs(a.timestamp - b.timestamp);
  if (a.accountTail != null && a.accountTail === b.accountTail && gap <= SMS_WINDOW_MS) return true;
  if ((a.source === 'STATEMENT') !== (b.source === 'STATEMENT') && a.accountTail != null && a.accountTail === b.accountTail && gap <= 2 * DAY_MS) return true;
  if (a.source === 'MANUAL' || b.source === 'MANUAL') {
    const ma = a.merchant != null ? merchantKey(a.merchant) : null;
    const mb = b.merchant != null ? merchantKey(b.merchant) : null;
    if (ma != null && ma === mb && gap <= DAY_MS) return true;
  }
  return false;
}

/** The incoming rows that are not already present in [existing] (nor repeated within [incoming]). */
export function newOnly(existing: SharedTxn[], incoming: SharedTxn[]): SharedTxn[] {
  const uids = new Set(existing.map((t) => t.uid));
  const smsKeys = new Set(existing.map((t) => t.smsKey).filter((k): k is string => k != null));
  const all = [...existing].sort((a, b) => a.timestamp - b.timestamp);
  const near = (ts: number) => {
    // Binary search the first row within 2 days before.
    let lo = 0, hi = all.length;
    while (lo < hi) { const m = (lo + hi) >> 1; if (all[m].timestamp < ts - 2 * DAY_MS) lo = m + 1; else hi = m; }
    const out: SharedTxn[] = [];
    for (let i = lo; i < all.length && all[i].timestamp <= ts + 2 * DAY_MS; i++) out.push(all[i]);
    return out;
  };
  const kept: SharedTxn[] = [];
  for (const t of incoming) {
    const clash = uids.has(t.uid) || (t.smsKey != null && smsKeys.has(t.smsKey)) || near(t.timestamp).some((e) => isDuplicate(e, t));
    if (!clash) {
      kept.push(t);
      uids.add(t.uid);
      if (t.smsKey != null) smsKeys.add(t.smsKey);
      let i = all.findIndex((e) => e.timestamp > t.timestamp);
      if (i < 0) i = all.length;
      all.splice(i, 0, t);
    }
  }
  return kept;
}

export const HEADER = ['uid', 'date', 'amount', 'type', 'merchant', 'category', 'account', 'bank', 'note', 'owner', 'source', 'sms_key', 'reference', 'excluded', 'mode', 'labels'];

function quote(v: string | null | undefined): string {
  if (v == null) return '';
  return /[,"\n\r]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
}

export function encode(rows: SharedTxn[]): string {
  const out = [HEADER.join(',')];
  for (const r of rows) {
    out.push([
      r.uid, new Date(r.timestamp).toISOString(), minorToPlain(r.amountMinor), r.type, r.merchant, r.category, r.accountTail, r.bank,
      r.note, r.owner, r.source, r.smsKey, r.reference, String(r.excluded), r.mode, r.labels.length ? r.labels.join('|') : null,
    ].map((v) => quote(v)).join(','));
  }
  return out.join('\n') + '\n';
}

export function splitRecords(text: string): string[][] {
  const records: string[][] = [];
  let field = '';
  let record: string[] = [];
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else inQuotes = false;
      } else field += ch;
    } else if (ch === '"') inQuotes = true;
    else if (ch === ',') { record.push(field); field = ''; }
    else if (ch === '\r') { /* skip */ }
    else if (ch === '\n') { record.push(field); records.push(record); record = []; field = ''; }
    else field += ch;
  }
  if (field !== '' || record.length) { record.push(field); records.push(record); }
  return records;
}

/** Java's Instant.parse: "2026-11-27T05:48:53Z", with or without fractions. */
function parseInstant(s: string): number {
  const t = Date.parse(s);
  if (Number.isNaN(t)) throw new Error('date');
  return t;
}

export function decode(text: string): [SharedTxn[], number] {
  const records = splitRecords(text.replace(/^﻿/, ''));
  if (records.length === 0) return [[], 0];
  const cols = records[0].map((c) => c.trim().toLowerCase());
  const idx = (n: string) => cols.indexOf(n);
  const out: SharedTxn[] = [];
  let bad = 0;
  for (const rec of records.slice(1)) {
    if (rec.every((c) => c.trim() === '')) continue;
    const get = (n: string): string | null => {
      const i = idx(n);
      if (i < 0 || i >= rec.length) return null;
      return rec[i].trim() === '' ? null : rec[i];
    };
    try {
      const uid = get('uid');
      const amount = get('amount');
      const type = get('type')?.toUpperCase();
      const date = get('date');
      if (!uid || !amount || !date || (type !== 'DEBIT' && type !== 'CREDIT')) throw new Error('row');
      const amountMinor = parseAmountToMinor(amount);
      if (amountMinor == null) throw new Error('amount');
      const ex = get('excluded');
      out.push({
        uid, amountMinor, type, merchant: get('merchant'), category: get('category') ?? Cat.OTHER, accountTail: get('account'),
        bank: get('bank'), timestamp: parseInstant(date), note: get('note'), owner: get('owner'), source: get('source') ?? 'MANUAL',
        smsKey: get('sms_key'), reference: get('reference'), excluded: ex === 'true',
        mode: get('mode'), labels: (get('labels') ?? '').split('|').map((l) => l.trim()).filter((l) => l !== ''),
      });
    } catch {
      bad++;
    }
  }
  return [out, bad];
}

/** True when [text] starts with a MyFinance export header. */
export function isExport(text: string): boolean {
  const first = text.replace(/^﻿/, '').split(/\r?\n/)[0] ?? '';
  const cols = first.split(',').map((c) => c.trim().replace(/^"|"$/g, '').toLowerCase());
  return ['uid', 'date', 'amount', 'type'].every((c) => cols.includes(c));
}

export function owners(rows: SharedTxn[]): string[] {
  return [...new Set(rows.map((r) => r.owner?.trim()).filter((o): o is string => !!o))].sort();
}
