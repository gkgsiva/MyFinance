// The app's data: every entry and setting, kept in memory and written through to IndexedDB on this device.
// The import and merge steps follow the Android app's FinanceRepository so both read a statement the same way.

import { useEffect, useState } from 'preact/hooks';
import { LocalDate, YearMonth } from '../core/dates';
import { randomUuid } from '../core/hash';
import { Cat, CheckRow, DEFAULT_CATEGORIES, Duplicates, Reversals, SelfTransfers, categorize, detectMode, fxFind, merchantKey, ReversalKind } from '../core/rules';
import { ImportCandidate, ExistingTxn, keptTail, lineForSms, sameAccount, sameLine, updatesExisting } from '../core/statementImport';
import { ParsedSms, duplicateOf, fxNote, isBillerReceipt, parseSms, smsKey } from '../core/smsParser';
import { MonthCycle } from '../core/ledger';
import { SharedTxn, decode, encode, newOnly, owners } from '../core/familyCsv';
import { TxnType, digitsOnly } from '../core/text';
import * as db from './db';

export type Source = 'SMS' | 'MANUAL' | 'STATEMENT';

export interface Txn {
  id: number;
  uid: string;
  amountMinor: number;
  type: TxnType;
  merchant: string | null;
  category: string;
  accountTail: string | null;
  bank: string | null;
  timestamp: number;
  note: string | null;
  source: Source;
  smsKey: string | null;
  rawText: string | null;
  reference: string | null;
  balanceMinor: number | null;
  userEdited: boolean;
  excluded: boolean;
  owner: string | null;
  mode: string | null;
  labels: string[];
  hidden: boolean;
}

export interface Account { tail: string; name: string; kind: 'BANK' | 'CARD'; bank: string | null; owner: string | null }

export interface CardBillRec { tail: string; bank: string | null; due: string; arrived: string; totalMinor: number; minMinor: number | null; markedPaid: boolean }

export interface ImportBatch { id: number; at: number; files: string[]; added: number; updated: number; uids: string[]; from: string | null; to: string | null }

/** A pasted message and the time it is dated (pasted text carries no date of its own). */
export interface SmsItem { body: string; timestamp: number; sender?: string | null }
export type SmsStatus = 'NEW' | 'MATCHES_STATEMENT' | 'ALREADY_HERE' | 'REPEATS_SMS' | 'DELETED_BEFORE' | 'NOT_A_TRANSACTION';
export interface SmsPlan { body: string; timestamp: number; key: string; status: SmsStatus; parsed: ParsedSms | null; txn: Txn | null; lineId: number | null }

export interface TrashItem { txn: Txn; at: number }

/** The private bucket the processed statements are read from, and the read-only key. Kept on this device only (not in backups). */
export interface CloudState {
  /** AWS S3 or Google Cloud Storage; missing means AWS (set up before Google Cloud was offered). */
  provider?: 'aws' | 'gcp';
  bucket: string;
  region: string;
  prefix: string;
  accessKeyId: string;
  secretAccessKey: string;
  lastSync: number;
  /** Statement key in the bucket → the version (ETag) already imported. */
  imported: Record<string, string>;
}

export interface Settings {
  ownerName: string;
  monthStartDay: number;
  salaryFromDay: number | null;
  categories: string[];
  budgets: Record<string, number>;
  rules: Record<string, string>;
  lastBackup: number;
  lastExport: number;
}

const DEFAULT_SETTINGS: Settings = {
  ownerName: '', monthStartDay: 1, salaryFromDay: null, categories: [...DEFAULT_CATEGORIES], budgets: {}, rules: {}, lastBackup: 0, lastExport: 0,
};

const TRASH_DAYS = 60;
const DAY = 86_400_000;

const tail4 = (s: string | null | undefined): string | null => {
  if (s == null) return null;
  const d = digitsOnly(s).slice(-4);
  return d.length === 4 ? d : null;
};

class Store {
  txns: Txn[] = [];
  byId = new Map<number, Txn>();
  settings: Settings = { ...DEFAULT_SETTINGS };
  accounts: Account[] = [];
  bills: CardBillRec[] = [];
  batches: ImportBatch[] = [];
  trash: TrashItem[] = [];
  deletedUids = new Set<string>();
  deletedKeys = new Set<string>();
  /** Review answers, as on Android: "dup:a+b" → "no", "rev:a+b" → "pair:KIND" or "not". */
  answers: Record<string, string> = {};
  cloud: CloudState | null = null;
  nextId = 1;
  version = 0;
  loaded = false;
  private listeners = new Set<() => void>();

  async load() {
    const [rows, kv] = await Promise.all([db.getAll<Txn>('txns'), db.allKv()]);
    this.txns = rows;
    this.byId = new Map(rows.map((t) => [t.id, t]));
    this.nextId = rows.reduce((m, t) => Math.max(m, t.id), 0) + 1;
    this.settings = { ...DEFAULT_SETTINGS, ...((kv.get('settings') as Partial<Settings>) ?? {}) };
    this.accounts = (kv.get('accounts') as Account[]) ?? [];
    this.bills = (kv.get('bills') as CardBillRec[]) ?? [];
    this.batches = (kv.get('batches') as ImportBatch[]) ?? [];
    const cutoff = Date.now() - TRASH_DAYS * DAY;
    this.trash = ((kv.get('trash') as TrashItem[]) ?? []).filter((t) => t.at >= cutoff);
    const del = (kv.get('deleted') as [string, string | null][]) ?? [];
    this.deletedUids = new Set(del.map((d) => d[0]));
    this.deletedKeys = new Set(del.map((d) => d[1]).filter((k): k is string => k != null));
    this.answers = (kv.get('answers') as Record<string, string>) ?? {};
    this.cloud = (kv.get('cloud') as CloudState) ?? null;
    this.loaded = true;
    this.changed();
  }

  subscribe(f: () => void) {
    this.listeners.add(f);
    return () => { this.listeners.delete(f); };
  }

  private changed() {
    this.version++;
    this._cache.clear();
    for (const f of this.listeners) f();
  }

  private _cache = new Map<string, unknown>();
  /** Values worked out from the data, kept until the data changes. */
  memo<T>(key: string, f: () => T): T {
    if (this._cache.has(key)) return this._cache.get(key) as T;
    const v = f();
    this._cache.set(key, v);
    return v;
  }

  private kvOf(...keys: ('settings' | 'accounts' | 'bills' | 'batches' | 'trash' | 'deleted' | 'answers')[]): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const k of keys) {
      if (k === 'settings') out.settings = this.settings;
      if (k === 'accounts') out.accounts = this.accounts;
      if (k === 'bills') out.bills = this.bills;
      if (k === 'batches') out.batches = this.batches;
      if (k === 'trash') out.trash = this.trash;
      if (k === 'answers') out.answers = this.answers;
      if (k === 'deleted') {
        const m = new Map<string, string | null>();
        for (const u of this.deletedUids) m.set(u, null);
        for (const t of this.trash) m.set(t.txn.uid, t.txn.smsKey);
        const keys = new Set(this.trash.map((t) => t.txn.smsKey));
        const extra = [...this.deletedKeys].filter((k2) => !keys.has(k2)).map((k2) => ['', k2] as [string, string]);
        out.deleted = [...m.entries(), ...extra];
      }
    }
    return out;
  }

  /** Saves changed entries, removed ids and values in one go, then refreshes the screens. */
  private async commit(put: Txn[], del: number[] = [], kv: Record<string, unknown> = {}) {
    for (const t of put) {
      if (!this.byId.has(t.id)) this.txns.push(t);
      else {
        const i = this.txns.findIndex((x) => x.id === t.id);
        this.txns[i] = t;
      }
      this.byId.set(t.id, t);
    }
    if (del.length) {
      const gone = new Set(del);
      this.txns = this.txns.filter((t) => !gone.has(t.id));
      for (const d of del) this.byId.delete(d);
    }
    this.changed();
    await db.write(put, del, kv);
  }

  private bulkReplace(put: Txn[]) {
    // Faster than one findIndex per row for big imports.
    const idx = new Map(this.txns.map((t, i) => [t.id, i]));
    for (const t of put) {
      const i = idx.get(t.id);
      if (i == null) { idx.set(t.id, this.txns.length); this.txns.push(t); } else this.txns[i] = t;
      this.byId.set(t.id, t);
    }
  }

  // ---- Reading ----

  get me(): string { return this.settings.ownerName.trim(); }

  get cycle(): MonthCycle { return new MonthCycle(this.settings.monthStartDay, this.settings.salaryFromDay); }

  get categories(): string[] {
    const set = new Set(this.settings.categories);
    for (const t of this.txns) set.add(t.category);
    return [...set];
  }

  ruleMap(): Map<string, string> { return new Map(Object.entries(this.settings.rules)); }

  /** The family's credit cards: card accounts, card bills, and tails with credit card payments in. */
  cardTails(): Set<string> {
    return this.memo('cardTails', () => {
      const cards = new Set(this.accounts.filter((a) => a.kind === 'CARD').map((a) => a.tail));
      for (const b of this.bills) cards.add(b.tail);
      for (const t of this.txns) {
        if (t.type === 'CREDIT' && t.rawText != null && /credit\s*card/i.test(t.rawText)) {
          const tl = tail4(t.accountTail);
          if (tl) cards.add(tl);
        }
      }
      for (const a of this.accounts) if (a.kind === 'BANK') cards.delete(a.tail);
      return cards;
    });
  }

  /** Counted entries (not left out of totals). */
  counted(): Txn[] { return this.memo('counted', () => this.txns.filter((t) => !t.excluded)); }

  /** Card bills paid from a bank account: left out of totals, but they lower the bank balance on Home. */
  cardBillPayments(): Txn[] {
    return this.memo('cardBillPayments', () => {
      const cards = this.cardTails();
      return this.txns.filter((t) => t.excluded && t.type === 'DEBIT' && !cards.has(tail4(t.accountTail) ?? '') &&
        SelfTransfers.isCardPayment(t.merchant, t.rawText));
    });
  }

  hiddenIds(): Set<number> { return this.memo('hidden', () => new Set(this.txns.filter((t) => t.hidden).map((t) => t.id))); }

  ownerOf(tail: string | null, bank: string | null): string | null {
    const t = tail4(tail);
    const a = t ? this.accounts.find((x) => x.tail === t) : this.accounts.find((x) => x.bank != null && bank != null && x.bank.toLowerCase() === bank.toLowerCase());
    const o = a?.owner?.trim();
    return o && o !== this.me ? o : null;
  }

  accountName(tail: string | null, bank: string | null): string {
    const t = tail4(tail);
    const a = t ? this.accounts.find((x) => x.tail === t) : undefined;
    if (a?.name) return a.name;
    const b = bank ?? a?.bank;
    return [b, t ? `••${t}` : null].filter(Boolean).join(' ') || 'Cash / other';
  }

  checkRow(t: Txn): CheckRow {
    return {
      id: t.id, uid: t.uid, type: t.type, amountMinor: t.amountMinor, account: t.accountTail, timestamp: t.timestamp,
      merchant: t.merchant, text: t.rawText, category: t.category, owner: t.owner ?? '', manual: t.source === 'MANUAL',
      statement: t.source === 'STATEMENT', reference: t.reference, edited: t.userEdited,
    };
  }

  existingForImport(): ExistingTxn[] {
    return this.txns.map((t) => ({
      amountMinor: t.amountMinor, type: t.type, timestamp: t.timestamp, accountTail: t.accountTail, reference: t.reference,
      key: t.smsKey, id: t.id, fromStatement: t.source === 'STATEMENT', text: t.rawText,
    }));
  }

  // ---- Changing entries ----

  newTxn(p: Partial<Txn> & Pick<Txn, 'amountMinor' | 'type' | 'timestamp'>): Txn {
    return {
      id: this.nextId++, uid: randomUuid(), merchant: null, category: Cat.OTHER, accountTail: null, bank: null, note: null,
      source: 'MANUAL', smsKey: null, rawText: null, reference: null, balanceMinor: null, userEdited: false, excluded: false,
      owner: null, mode: null, labels: [], hidden: false, ...p,
    };
  }

  async addManual(p: { amountMinor: number; type: TxnType; merchant: string | null; category: string | null; timestamp: number; note: string | null; accountTail: string | null; mode: string | null; labels: string[] }) {
    const category = p.category ?? categorize(p.merchant, p.type, null, this.ruleMap());
    const t = this.newTxn({ ...p, category, source: 'MANUAL', userEdited: true, owner: this.ownerOf(p.accountTail, null), bank: null });
    await this.commit([t]);
    return t;
  }

  /** Saves an edited entry. A new category for a merchant is learnt, and earlier unedited entries follow it. */
  async update(t: Txn, learn = true) {
    const before = this.byId.get(t.id);
    const put: Txn[] = [{ ...t, userEdited: true }];
    let kv: Record<string, unknown> = {};
    if (learn && before && before.category !== t.category && t.merchant) {
      const key = merchantKey(t.merchant);
      if (key) {
        this.settings = { ...this.settings, rules: { ...this.settings.rules, [key]: t.category } };
        kv = this.kvOf('settings');
        for (const o of this.txns) {
          if (o.id !== t.id && !o.userEdited && !o.excluded && o.merchant && merchantKey(o.merchant) === key && o.type === t.type) put.push({ ...o, category: t.category });
        }
      }
    }
    if (t.category && !this.settings.categories.includes(t.category)) {
      this.settings = { ...this.settings, categories: [...this.settings.categories, t.category] };
      kv = this.kvOf('settings');
    }
    await this.commit(put, [], kv);
    return put.length - 1;
  }

  async setMany(list: Txn[]) { await this.commit(list); }

  /** Moves entries to Recently deleted; an import or merge won't bring them back. */
  async remove(ids: number[]) {
    const now = Date.now();
    const gone = ids.map((i) => this.byId.get(i)).filter((t): t is Txn => !!t);
    this.trash = [...gone.map((txn) => ({ txn, at: now })), ...this.trash];
    for (const t of gone) { this.deletedUids.add(t.uid); if (t.smsKey) this.deletedKeys.add(t.smsKey); }
    await this.commit([], gone.map((t) => t.id), this.kvOf('trash', 'deleted'));
    return gone;
  }

  async restore(uids: string[]) {
    const back = this.trash.filter((t) => uids.includes(t.txn.uid));
    this.trash = this.trash.filter((t) => !uids.includes(t.txn.uid));
    for (const b of back) { this.deletedUids.delete(b.txn.uid); if (b.txn.smsKey) this.deletedKeys.delete(b.txn.smsKey); }
    const put = back.map((b) => (this.byId.has(b.txn.id) ? { ...b.txn, id: this.nextId++ } : b.txn));
    this.nextId = Math.max(this.nextId, ...put.map((t) => t.id + 1));
    await this.commit(put, [], this.kvOf('trash', 'deleted'));
  }

  async emptyTrash() {
    this.trash = [];
    await this.commit([], [], this.kvOf('trash', 'deleted'));
  }

  async setSettings(p: Partial<Settings>) {
    this.settings = { ...this.settings, ...p };
    await this.commit([], [], this.kvOf('settings'));
  }

  async setCloud(c: CloudState | null) {
    this.cloud = c;
    await this.commit([], [], { cloud: c ?? undefined });
  }

  async setAccounts(list: Account[]) {
    this.accounts = list;
    await this.commit([], [], this.kvOf('accounts'));
  }

  async setBills(list: CardBillRec[]) {
    this.bills = list;
    await this.commit([], [], this.kvOf('bills'));
  }

  async answer(key: string, value: string | undefined) {
    const a = { ...this.answers };
    if (value === undefined) delete a[key]; else a[key] = value;
    this.answers = a;
    await this.commit([], [], this.kvOf('answers'));
  }

  /** Applies a category to the given entries and learns it for their merchants. */
  async categoriseAll(ids: number[], category: string) {
    const put = ids.map((i) => this.byId.get(i)).filter((t): t is Txn => !!t).map((t) => ({ ...t, category, userEdited: true }));
    const rules = { ...this.settings.rules };
    for (const t of put) if (t.merchant) { const k = merchantKey(t.merchant); if (k) rules[k] = category; }
    this.settings = { ...this.settings, rules };
    await this.commit(put, [], this.kvOf('settings'));
  }

  // ---- Statement import (Android's addImported) ----

  async addImported(
    rows: ImportCandidate[], files: string[], billsFound: CardBillRec[],
    onProgress: (done: number, total: number) => void = () => {},
  ): Promise<{ added: number; updated: number; leftOut: number; removed: number }> {
    const rules = this.ruleMap();
    const put = new Map<number, Txn>();
    const get = (id: number) => put.get(id) ?? this.byId.get(id);
    const byKey = new Map(this.txns.filter((t) => t.smsKey).map((t) => [t.smsKey!, t.id]));
    let added = 0, updated = 0;
    const addedUids: string[] = [];
    let from: LocalDate | null = null, to: LocalDate | null = null;
    rows.forEach((c, i) => {
      if (i % 500 === 0) onProgress(i, rows.length);
      const r = c.row;
      if (!from || r.date.isBefore(from)) from = r.date;
      if (!to || r.date.isAfter(to)) to = r.date;
      if (updatesExisting(c)) {
        const t = get(c.matchedId!);
        if (!t) return;
        const official = c.merchant ?? t.merchant;
        const tl = keptTail(c.accountTail, t.accountTail, t.rawText);
        const bank = c.bank ?? t.bank;
        put.set(t.id, {
          ...t, amountMinor: r.amountMinor, type: r.type, timestamp: statementTime(r.date, t.timestamp),
          merchant: t.userEdited && t.merchant ? t.merchant : official, accountTail: tl, bank,
          owner: this.ownerOf(tl, bank) ?? t.owner, reference: t.reference ?? r.reference, balanceMinor: r.balanceMinor ?? t.balanceMinor,
          source: 'STATEMENT', rawText: statementText(r.description, t.rawText),
          excluded: t.excluded || (!t.userEdited && (c.ownTransfer || SelfTransfers.isCardBillPayment(r.type, official, r.description, c.cardStatement))),
        });
        updated++;
        return;
      }
      if (c.duplicateOf === 'SAME_STATEMENT' && r.reference != null) {
        const id = byKey.get(c.key);
        const t = id != null ? get(id) : undefined;
        if (t && t.reference == null) put.set(t.id, { ...t, reference: r.reference });
      }
      if (c.duplicateOf != null || this.deletedUids.has(c.uid) || this.deletedKeys.has(c.key)) return;
      const bill = c.ownTransfer || SelfTransfers.isCardBillPayment(r.type, c.merchant, r.description, c.cardStatement);
      const t = this.newTxn({
        uid: c.uid, excluded: bill, amountMinor: r.amountMinor, type: r.type, merchant: c.merchant,
        category: bill ? Cat.TRANSFERS : categorize(c.merchant, r.type, r.description, rules),
        accountTail: c.accountTail, bank: c.bank, timestamp: c.timestamp, source: 'STATEMENT', smsKey: c.key,
        rawText: r.description, reference: r.reference, balanceMinor: r.balanceMinor, owner: this.ownerOf(c.accountTail, c.bank),
        mode: detectMode(r.description, false, null),
      });
      put.set(t.id, t);
      byKey.set(c.key, t.id);
      added++;
      addedUids.push(t.uid);
    });
    onProgress(rows.length, rows.length);
    // Bills: one per card and due date, the latest reading wins.
    const billMap = new Map(this.bills.map((b) => [`${b.tail}|${b.due}`, b]));
    for (const b of billsFound) billMap.set(`${b.tail}|${b.due}`, { ...b, markedPaid: billMap.get(`${b.tail}|${b.due}`)?.markedPaid ?? false });
    this.bills = [...billMap.values()];
    const batch: ImportBatch = {
      id: Date.now(), at: Date.now(), files, added, updated, uids: addedUids,
      from: from ? (from as LocalDate).toString() : null, to: to ? (to as LocalDate).toString() : null,
    };
    if (added + updated > 0) this.batches = [batch, ...this.batches].slice(0, 100);
    const list = [...put.values()];
    this.bulkReplace(list);
    this.changed();
    await db.write(list, [], this.kvOf('bills', 'batches'));
    const leftOut = (await this.leaveOutCardPaymentPairs()) + (await this.leaveOutReversals());
    const removed = await this.removeSameIdDuplicates();
    return { added, updated, leftOut, removed };
  }

  // ---- Pasted SMS (Android's ingestSms) ----

  /**
   * What each pasted message would do, without saving: read with the Android SMS reader, then checked against
   * what is stored and against the messages pasted before it, as Android checks a new SMS.
   */
  planSms(items: SmsItem[]): SmsPlan[] {
    const rules = this.ruleMap();
    const DAYS3 = 3 * DAY;
    const plans: SmsPlan[] = [];
    const keys = new Set(this.txns.map((t) => t.smsKey).filter((k): k is string => k != null));
    // A message the Android app read has its sender in the key ("ICICIT|<hash>"); a pasted one has none.
    const hashes = new Set([...keys].map((k) => k.slice(k.indexOf('|') + 1)));
    const deletedHashes = new Set([...this.deletedKeys].map((k) => k.slice(k.indexOf('|') + 1)));
    const taken = new Set<number>();
    const pending: Txn[] = [];
    for (const item of items) {
      const body = item.body.trim();
      const sender = item.sender ?? '';
      const key = smsKey(sender, body);
      const hash = key.slice(key.indexOf('|') + 1);
      const base = { body, timestamp: item.timestamp, key };
      if (isBillerReceipt(sender, body)) { plans.push({ ...base, status: 'NOT_A_TRANSACTION', parsed: null, txn: null, lineId: null }); continue; }
      const parsed = parseSms(sender, body, item.timestamp);
      if (!parsed) { plans.push({ ...base, status: 'NOT_A_TRANSACTION', parsed: null, txn: null, lineId: null }); continue; }
      if (deletedHashes.has(hash)) { plans.push({ ...base, status: 'DELETED_BEFORE', parsed, txn: null, lineId: null }); continue; }
      if (hashes.has(hash) || pending.some((t) => t.smsKey != null && t.smsKey.endsWith('|' + hash))) { plans.push({ ...base, status: 'ALREADY_HERE', parsed, txn: null, lineId: null }); continue; }
      const own = (parsed.counterTail != null && this.isOwnAccount(parsed.counterTail)) ||
        SelfTransfers.isCardBillPayment(parsed.type, parsed.merchant, body);
      const txn = this.newTxn({
        excluded: own, amountMinor: parsed.amountMinor, type: parsed.type, merchant: parsed.merchant,
        category: own ? Cat.TRANSFERS : categorize(parsed.merchant, parsed.type, body, rules),
        accountTail: parsed.accountTail, bank: parsed.bank, timestamp: parsed.timestamp, source: 'SMS', smsKey: key,
        rawText: body, reference: parsed.reference, balanceMinor: parsed.balanceMinor,
        note: parsed.foreign && parsed.fxRate != null ? fxNote(parsed.foreign, parsed.fxRate) : null,
        owner: this.ownerOf(parsed.accountTail, parsed.bank), mode: detectMode(body, false, null),
      });
      this.nextId--; // only a plan: the id is given again when it is saved
      // Already imported from a statement: the SMS joins that line.
      const foreign = fxFind(body) != null;
      const lines = this.txns.filter((t) => t.source === 'STATEMENT' && !taken.has(t.id) && (t.smsKey == null || t.smsKey.startsWith('STMT|')) &&
        (t.amountMinor === txn.amountMinor || foreign) && t.type === txn.type && Math.abs(t.timestamp - txn.timestamp) <= DAYS3);
      const i = lines.length ? lineForSms(this.existing(txn), lines.map((l) => this.existing(l))) : null;
      if (i != null) {
        taken.add(lines[i].id);
        plans.push({ ...base, status: 'MATCHES_STATEMENT', parsed, txn, lineId: lines[i].id });
        continue;
      }
      // The bank's alert and the UPI / wallet app's message for one payment: one entry.
      const near = [...this.txns, ...pending].filter((t) => t.source === 'SMS' && t.smsKey !== key && t.amountMinor === txn.amountMinor &&
        t.type === txn.type && Math.abs(t.timestamp - txn.timestamp) <= DAYS3);
      if (near.length && duplicateOf(txn, near) != null) { plans.push({ ...base, status: 'REPEATS_SMS', parsed, txn: null, lineId: null }); continue; }
      pending.push(txn);
      plans.push({ ...base, status: 'NEW', parsed, txn, lineId: null });
    }
    return plans;
  }

  /** Saves what [planSms] found: new entries, and SMS details on the statement lines they match. */
  async addSms(plans: SmsPlan[]): Promise<{ added: number; linked: number; leftOut: number }> {
    const put: Txn[] = [];
    const addedUids: string[] = [];
    let added = 0, linked = 0;
    let from: LocalDate | null = null, to: LocalDate | null = null;
    for (const p of plans) {
      const s = p.txn;
      if (!s) continue;
      if (p.status === 'MATCHES_STATEMENT' && p.lineId != null) {
        const line = this.byId.get(p.lineId);
        if (!line) continue;
        put.push({
          ...line, smsKey: s.smsKey, rawText: statementText(line.rawText ?? '', s.rawText),
          category: mergedCategory(line.category, s.category, line.userEdited),
          reference: line.reference ?? s.reference, accountTail: keptTail(line.accountTail, s.accountTail, s.rawText), bank: line.bank ?? s.bank,
        });
        linked++;
      } else if (p.status === 'NEW') {
        const t = { ...s, id: this.nextId++ };
        put.push(t);
        addedUids.push(t.uid);
        added++;
        const d = LocalDate.fromMillis(t.timestamp);
        if (!from || d.isBefore(from)) from = d;
        if (!to || d.isAfter(to)) to = d;
      }
    }
    if (!put.length) return { added, linked, leftOut: 0 };
    if (added) {
      this.batches = [{
        id: Date.now(), at: Date.now(), files: [`Pasted SMS (${added})`], added, updated: linked, uids: addedUids,
        from: from ? (from as LocalDate).toString() : null, to: to ? (to as LocalDate).toString() : null,
      }, ...this.batches].slice(0, 100);
    }
    await this.commit(put, [], this.kvOf('batches'));
    const leftOut = (await this.leaveOutCardPaymentPairs()) + (await this.leaveOutReversals());
    return { added, linked, leftOut };
  }

  /** A transfer's other account is one of the family's: registered, or one an SMS has named before. */
  private isOwnAccount(tail: string): boolean {
    if (this.accounts.some((a) => sameAccount(a.tail, tail) && a.tail != null && tail.length >= 3)) return true;
    return this.txns.some((t) => t.source === 'SMS' && t.accountTail != null && t.accountTail.endsWith(tail));
  }

  private existing(t: Txn): ExistingTxn {
    return {
      amountMinor: t.amountMinor, type: t.type, timestamp: t.timestamp, accountTail: t.accountTail, reference: t.reference,
      key: t.smsKey, id: t.id, fromStatement: t.source === 'STATEMENT', text: t.rawText,
    };
  }

  /** Takes out every entry an import added, so the same files can be imported again cleanly. */
  async undoImport(batchId: number) {
    const b = this.batches.find((x) => x.id === batchId);
    if (!b) return 0;
    const uids = new Set(b.uids);
    const ids = this.txns.filter((t) => uids.has(t.uid)).map((t) => t.id);
    this.batches = this.batches.filter((x) => x.id !== batchId);
    // Not remembered as deleted: importing the file again should bring them back.
    await this.commit([], ids, this.kvOf('batches'));
    return ids.length;
  }

  /** Card bill paid from a bank account and the same money arriving on the card: both left out of totals. */
  async leaveOutCardPaymentPairs(): Promise<number> {
    const cards = this.cardTails();
    if (cards.size === 0) return 0;
    const pairs = SelfTransfers.cardPaymentPairs(
      this.txns.filter((t) => t.source !== 'MANUAL').map((t) => ({
        id: t.id, type: t.type, amountMinor: t.amountMinor, timestamp: t.timestamp, tail: t.accountTail,
        text: [t.merchant, t.rawText].filter(Boolean).join(' '), edited: t.userEdited,
      })),
      cards,
    );
    const ids = new Set(pairs.flat());
    const changed = this.txns.filter((t) => ids.has(t.id) && (!t.excluded || t.category !== Cat.TRANSFERS))
      .map((t) => ({ ...t, excluded: true, category: Cat.TRANSFERS }));
    if (changed.length) await this.commit(changed);
    return changed.length;
  }

  async leaveOutReversals(): Promise<number> {
    const dismissed = new Set(Object.entries(this.answers).filter(([k, v]) => k.startsWith('rev:') && v === 'not').map(([k]) => k));
    const pairs = Reversals.find(this.txns.filter((t) => !t.excluded).map((t) => this.checkRow(t)), dismissed);
    if (pairs.length === 0) return 0;
    const ids = new Set(pairs.flatMap((p) => [p.debit.id, p.credit.id]));
    const changed = this.txns.filter((t) => ids.has(t.id)).map((t) => ({ ...t, excluded: true }));
    const a = { ...this.answers };
    for (const p of pairs) a[p.key] = `pair:${p.kind}`;
    this.answers = a;
    await this.commit(changed, [], this.kvOf('answers'));
    return pairs.length;
  }

  reversalPairs(): { key: string; kind: ReversalKind; debit: Txn | undefined; credit: Txn | undefined }[] {
    const byUid = new Map(this.txns.map((t) => [t.uid, t]));
    return Object.entries(this.answers).filter(([k, v]) => k.startsWith('rev:') && v.startsWith('pair')).map(([k, v]) => {
      const [d, c] = k.slice(4).split('+');
      return { key: k, kind: (v.split(':')[1] ?? 'REFUND') as ReversalKind, debit: byUid.get(d), credit: byUid.get(c) };
    }).filter((p) => p.debit || p.credit);
  }

  async undoReversal(key: string) {
    const [d, c] = key.slice(4).split('+');
    const changed = this.txns.filter((t) => (t.uid === d || t.uid === c) && t.excluded).map((t) => ({ ...t, excluded: false }));
    this.answers = { ...this.answers, [key]: 'not' };
    await this.commit(changed, [], this.kvOf('answers'));
  }

  duplicateGroups() {
    return this.memo('dups', () => {
      const dismissed = new Set(Object.entries(this.answers).filter(([k, v]) => k.startsWith('dup:') && v === 'no').map(([k]) => k));
      return Duplicates.find(this.txns.filter((t) => !t.excluded).map((t) => this.checkRow(t)), dismissed, sameLine);
    });
  }

  async removeSameIdDuplicates(): Promise<number> {
    const groups = this.duplicateGroups().filter((g) => g.sameId);
    return this.removeDuplicateGroups(groups);
  }

  /** Keeps one entry of each group (taking over what the user changed on the others) and deletes the rest. */
  async removeDuplicateGroups(groups: { rows: CheckRow[]; keep: CheckRow }[]): Promise<number> {
    const updated: Txn[] = [];
    const gone: number[] = [];
    for (const g of groups) {
      let keep = this.byId.get(g.keep.id);
      if (!keep) continue;
      const drop = g.rows.filter((r) => r.id !== g.keep.id).map((r) => this.byId.get(r.id)).filter((t): t is Txn => !!t);
      if (!drop.length) continue;
      const before = keep;
      const edited = drop.find((d) => d.userEdited);
      if (edited && !keep.userEdited) keep = { ...keep, category: edited.category, note: keep.note ?? edited.note, userEdited: true };
      if (keep.reference == null) { const r = drop.find((d) => d.reference)?.reference; if (r) keep = { ...keep, reference: r }; }
      if (keep.note == null) { const n = drop.find((d) => d.note)?.note; if (n) keep = { ...keep, note: n }; }
      const labels = new Set([...keep.labels, ...drop.flatMap((d) => d.labels)]);
      if (labels.size !== keep.labels.length) keep = { ...keep, labels: [...labels] };
      if (keep !== before) updated.push(keep);
      gone.push(...drop.map((d) => d.id));
    }
    if (!gone.length) return 0;
    if (updated.length) await this.commit(updated);
    await this.remove(gone);
    return gone.length;
  }

  async notDuplicates(key: string) { await this.answer(key, 'no'); }

  // ---- Family sharing (same file as the Android app) ----

  toShared(t: Txn): SharedTxn {
    return {
      uid: t.uid, amountMinor: t.amountMinor, type: t.type, merchant: t.merchant, category: t.category, accountTail: t.accountTail,
      bank: t.bank, timestamp: t.timestamp, note: t.note, owner: t.owner ?? (this.me || null), source: t.source, smsKey: t.smsKey,
      reference: t.reference, excluded: t.excluded, mode: t.mode ?? detectMode(t.rawText, t.source === 'MANUAL', t.category),
      labels: [...t.labels].sort(),
    };
  }

  exportCsv(): string {
    return encode(this.txns.map((t) => this.toShared(t)));
  }

  async markExported() { await this.setSettings({ lastExport: Date.now() }); }

  async mergeCsv(text: string): Promise<{ added: number; duplicates: number; unreadable: number; from: string[] }> {
    const [incoming, bad] = decode(text);
    const me = this.me;
    const existing = this.txns.map((t) => this.toShared(t));
    const fresh = newOnly(existing, incoming).filter((t) => !this.deletedUids.has(t.uid) && (t.smsKey == null || !this.deletedKeys.has(t.smsKey)));
    const cats = new Set(this.settings.categories);
    const put: Txn[] = [];
    for (const s of fresh) {
      const owner = s.owner != null && s.owner === me ? null : s.owner;
      put.push(this.newTxn({
        uid: s.uid, amountMinor: s.amountMinor, type: s.type, merchant: s.merchant, category: s.category, accountTail: s.accountTail,
        bank: s.bank, timestamp: s.timestamp, note: s.note, source: (['SMS', 'MANUAL', 'STATEMENT'].includes(s.source) ? s.source : 'MANUAL') as Source,
        smsKey: s.smsKey, reference: s.reference, excluded: s.excluded, owner, mode: s.mode, labels: s.labels, userEdited: true,
      }));
      cats.add(s.category);
    }
    // Labels travel with rows already here.
    const byUid = new Map(this.txns.map((t) => [t.uid, t]));
    for (const s of incoming) {
      const t = byUid.get(s.uid);
      if (!t || !s.labels.length) continue;
      const l = new Set([...t.labels, ...s.labels]);
      if (l.size !== t.labels.length) put.push({ ...t, labels: [...l] });
    }
    this.settings = { ...this.settings, categories: [...cats] };
    this.bulkReplace(put);
    this.changed();
    await db.write(put, [], this.kvOf('settings'));
    return { added: fresh.length, duplicates: incoming.length - fresh.length, unreadable: bad, from: owners(incoming).filter((o) => o !== me) };
  }

  // ---- Backup of everything on this device ----

  backupJson(): string {
    return JSON.stringify({
      app: 'myfinance-web', version: 1, at: new Date().toISOString(), txns: this.txns, ...this.kvOf('settings', 'accounts', 'bills', 'batches', 'trash', 'deleted', 'answers'),
    });
  }

  async restoreJson(text: string) {
    const j = JSON.parse(text);
    if (j.app !== 'myfinance-web' || !Array.isArray(j.txns)) throw new Error('This is not a MyFinance web backup.');
    await db.clearAll();
    const kv: Record<string, unknown> = {};
    for (const k of ['settings', 'accounts', 'bills', 'batches', 'trash', 'deleted', 'answers']) if (j[k] !== undefined) kv[k] = j[k];
    await db.write(j.txns, [], kv);
    await this.load();
  }

  async clearEverything() {
    await db.clearAll();
    await this.load();
  }
}

/** The statement's date with the SMS's time of day, so the order within a day stays as it was. */
function statementTime(date: LocalDate, smsTs: number): number {
  const sms = new Date(smsTs);
  if (LocalDate.fromMillis(smsTs).equals(date)) return smsTs;
  return date.atTime(sms.getHours(), sms.getMinutes(), sms.getSeconds());
}

const GENERIC = new Set<string>([Cat.TRANSFERS, Cat.OTHER, Cat.INCOME, 'Other', 'Uncategorized', 'Uncategorised']);
/** Port of SalaryRepair.mergedCategory: the category a statement line keeps when its SMS joins it. */
function mergedCategory(line: string, sms: string, lineEdited: boolean): string {
  if (lineEdited) return line;
  if (line.toLowerCase() === Cat.SALARY.toLowerCase()) return line;
  if (sms.toLowerCase() === Cat.SALARY.toLowerCase()) return sms;
  if (GENERIC.has(line) && !GENERIC.has(sms)) return sms;
  return line;
}

function statementText(narration: string, existing: string | null): string {
  const narr = narration.replace(/^Statement: /, '').split('\n\nSMS: ')[0];
  let sms: string | null = null;
  if (existing != null) {
    if (existing.includes('\n\nSMS: ')) sms = existing.slice(existing.indexOf('\n\nSMS: ') + 7);
    else if (existing.startsWith('Statement: ') || existing === narration) sms = null;
    else sms = existing;
  }
  if (sms != null && sms.trim() === '') sms = null;
  return `Statement: ${narr}` + (sms ? `\n\nSMS: ${sms}` : '');
}

export const store = new Store();

/** Re-renders the component whenever the data changes. */
export function useStore(): Store {
  const [, setV] = useState(0);
  useEffect(() => store.subscribe(() => setV((v) => v + 1)), []);
  return store;
}

export function periodLabel(ym: YearMonth): string {
  return ym.label();
}
