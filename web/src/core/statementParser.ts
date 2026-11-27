// Port of core/StatementParser.kt (StatementParser, Narration), StatementCheck.kt, PayeeNames and
// CardBills.summaryFromText. Keep the two in step: a rule changed on Android should change here too.

import { LocalDate, LocalTime, findDate } from './dates';
import {
  TxnType, countDigits, countLetters, digitsOnly, end, findAll, findFrom, full, g, lines, parseAmountToMinor,
  removePrefix, title, trimChars, before, after, decimalToMinor,
} from './text';

export type StatementKind = 'BANK' | 'CARD';
export const kindLabel: Record<StatementKind, string> = { BANK: 'Bank statement', CARD: 'Credit card statement' };

export interface StatementRow {
  date: LocalDate;
  time: LocalTime | null;
  /** The narration / description exactly as the bank printed it. */
  description: string;
  amountMinor: number;
  type: TxnType;
  balanceMinor: number | null;
  reference: string | null;
  /** Statements covering several cards or accounts: the last 4 digits of this line's card / account. */
  cardTail: string | null;
  /** The serial / reference number printed at the start of a card statement line. */
  serial: string | null;
  /** Card statements: the amount in the currency spent ("USD 23.60"). */
  foreign: string | null;
}

export interface StatementCheck {
  expectedMinor: number;
  computedMinor: number;
}

export function checkDifference(c: StatementCheck): number {
  return c.computedMinor - c.expectedMinor;
}

export function checkOk(c: StatementCheck): boolean {
  return Math.abs(checkDifference(c)) <= 100;
}

export interface ParsedStatement {
  rows: StatementRow[];
  kind: StatementKind;
  bank: string | null;
  accountTail: string | null;
  billTotalMinor: number | null;
  billMinMinor: number | null;
  billDueDate: LocalDate | null;
  check: StatementCheck | null;
  numbersSeen: [string | null, string][];
}

function row(p: Partial<StatementRow> & Pick<StatementRow, 'date' | 'description' | 'amountMinor' | 'type'>): StatementRow {
  return {
    time: null, balanceMinor: null, reference: null, cardTail: null, serial: null, foreign: null, ...p,
  };
}

function statement(p: Partial<ParsedStatement> & Pick<ParsedStatement, 'rows' | 'kind'>): ParsedStatement {
  return {
    bank: null, accountTail: null, billTotalMinor: null, billMinMinor: null, billDueDate: null, check: null, numbersSeen: [], ...p,
  };
}

export function statementFrom(s: ParsedStatement): LocalDate | null {
  return s.rows.reduce<LocalDate | null>((a, r) => (a == null || r.date.isBefore(a) ? r.date : a), null);
}

export function statementTo(s: ParsedStatement): LocalDate | null {
  return s.rows.reduce<LocalDate | null>((a, r) => (a == null || r.date.isAfter(a) ? r.date : a), null);
}

const nbsp = (s: string) => s.replace(/ /g, ' ');

// ---------------------------------------------------------------- StatementCheck

function signed(r: StatementRow) {
  return r.type === 'CREDIT' ? r.amountMinor : -r.amountMinor;
}

function looksNewestFirst(rows: StatementRow[]): boolean {
  if (rows.length < 2) return false;
  const a = rows[0];
  const b = rows[1];
  if (a.balanceMinor == null || b.balanceMinor == null) return false;
  const ab = a.balanceMinor;
  const bb = b.balanceMinor;
  return bb + signed(a) === ab && ab + signed(b) !== bb;
}

export const StatementChecks = {
  bank(rows: StatementRow[], opening: number | null = null): StatementCheck | null {
    const withBal = rows.filter((r) => r.balanceMinor != null);
    if (withBal.length < 2 || withBal.length < rows.length * 0.8) return null;
    const first = rows[0];
    const last = rows[rows.length - 1];
    const newestFirst = first.date.isAfter(last.date) || (first.date.equals(last.date) && looksNewestFirst(rows));
    const ordered = newestFirst ? [...rows].reverse() : rows;
    const i = ordered.findIndex((r) => r.balanceMinor != null);
    const start = opening ?? ordered[i].balanceMinor! - ordered.slice(0, i + 1).reduce((s, r) => s + signed(r), 0);
    let closing = 0;
    for (let k = ordered.length - 1; k >= 0; k--) if (ordered[k].balanceMinor != null) { closing = ordered[k].balanceMinor!; break; }
    const net = ordered.reduce((s, r) => s + signed(r), 0);
    return { expectedMinor: closing, computedMinor: start + net };
  },
  card(rows: StatementRow[], previous: number, totalDue: number): StatementCheck | null {
    if (rows.length === 0) return null;
    const spends = rows.filter((r) => r.type === 'DEBIT').reduce((s, r) => s + r.amountMinor, 0);
    const credits = rows.filter((r) => r.type === 'CREDIT').reduce((s, r) => s + r.amountMinor, 0);
    return { expectedMinor: totalDue, computedMinor: previous + spends - credits };
  },
};

// ---------------------------------------------------------------- CardBills.summaryFromText

const billMoney = /(?:rs\.?|inr|₹)?\s*([0-9][0-9,]*\.[0-9]{2})/i;
const totalLabel = /total\s+(?:amount|amt\.?)\s+(?:due|payable)|total\s+dues?\b|total\s+payment\s+due|closing\s+balance/i;
const minLabel = /min(?:imum)?\.?\s+(?:amount|amt\.?)\s+(?:due|payable)|minimum\s+due|min\.?\s+due/i;
const dueLabel = /payment\s+due\s+date|due\s+date|pay\s+by|due\s+by/i;

export function billSummaryFromText(text: string, reference: LocalDate): [number, number | null, LocalDate] | null {
  const flat = nbsp(text).slice(0, 8000);
  const totalAt = totalLabel.exec(flat);
  if (!totalAt) return null;
  const tm = findFrom(billMoney, flat, end(totalAt));
  if (!tm || tm.index - (end(totalAt) - 1) >= 250) return null;
  const total = parseAmountToMinor(tm[1]);
  if (total == null || total <= 0) return null;
  const dueAt = dueLabel.exec(flat);
  if (!dueAt) return null;
  const due = findDate(flat.slice(end(dueAt)).slice(0, 250), reference);
  if (!due) return null;
  let min: number | null = null;
  const mm = minLabel.exec(flat);
  if (mm) {
    const re = new RegExp(billMoney.source, 'gi');
    re.lastIndex = end(mm);
    for (let k = 0; k < 4; k++) {
      const x = re.exec(flat);
      if (!x) break;
      const v = parseAmountToMinor(x[1]);
      if (v != null && v >= 1 && v < total) { min = v; break; }
    }
  }
  return [total, min, due];
}

// ---------------------------------------------------------------- StatementParser

type Col = 'DATE' | 'VALUE_DATE' | 'DESC' | 'DEBIT' | 'CREDIT' | 'AMOUNT' | 'DRCR' | 'BALANCE' | 'REF' | 'TIME';

function norm(s: string) {
  return s.toLowerCase().replace(/[^a-z/]+/g, ' ').trim();
}

function classify(header: string): Col | null {
  const h = norm(header);
  if (h === '') return null;
  const c = (x: string) => h.includes(x);
  if (c('value') && (c('dat') || c(' dt'))) return 'VALUE_DATE';
  if (h === 'time' || h === 'txn time' || h === 'transaction time') return 'TIME';
  if (c('balance')) return 'BALANCE';
  if (c('withdraw') || (c('debit') && !c('credit')) || h === 'dr' || h === 'dr amount' || c('paid out') || c('money out')) return 'DEBIT';
  if (c('deposit') || (c('credit') && !c('debit') && !c('card')) || h === 'cr' || h === 'cr amount' || c('paid in') || c('money in')) return 'CREDIT';
  if (['dr/cr', 'cr/dr', 'dr / cr', 'debit/credit', 'credit/debit', 'type', 'txn type', 'transaction type', 'd/c', 'c/d'].includes(h)) return 'DRCR';
  if (c('narration') || c('description') || c('particular') || c('detail') || c('remark') || h === 'merchant' || c('merchant name') || h === 'transaction') return 'DESC';
  if (c('chq') || c('cheque') || c('ref') || c('utr')) return 'REF';
  if (c('amount') || h === 'amt' || c('amount inr')) return 'AMOUNT';
  if (c('date') || h === 'txn dt' || h === 'tran dt') return 'DATE';
  return null;
}

function findHeader(table: string[][]): [number, Map<Col, number>] | null {
  for (let i = 0; i < Math.min(80, table.length); i++) {
    const map = new Map<Col, number>();
    table[i].forEach((cell, ci) => {
      if (cell.length > 40) return;
      const col = classify(cell);
      if (col == null) return;
      if (!map.has(col)) map.set(col, ci);
    });
    const hasDate = map.has('DATE') || map.has('VALUE_DATE');
    const hasAmount = (map.has('DEBIT') && map.has('CREDIT')) || map.has('AMOUNT') || map.has('DEBIT');
    if (hasDate && hasAmount && map.size >= 3) return [i, map];
  }
  return null;
}

function cleanDesc(s: string): string {
  return trimChars(s.replace(/\s+/g, ' ').trim(), '-|,').trim();
}

/** Parses a spreadsheet: any heading lines before the table (account number, bank) are used as hints. */
export function fromTable(table: string[][]): ParsedStatement | null {
  const h = findHeader(table);
  if (!h) return null;
  const [headerIdx, cols] = h;
  const preamble = table.slice(0, headerIdx).map((r) => r.join(' ')).join('\n');
  const rows: StatementRow[] = [];
  const pendingDirection: number[] = [];
  for (const rec of table.slice(headerIdx + 1)) {
    const cell = (c: Col): string | null => {
      const i = cols.get(c);
      if (i == null) return null;
      const v = rec[i];
      if (v == null) return null;
      const t = v.trim();
      return t === '' ? null : t;
    };
    const dateText = cell('DATE') ?? cell('VALUE_DATE');
    const desc = cell('DESC') ?? '';
    const parsedDate = dateText != null ? parseDateTime(dateText) : null;
    if (parsedDate == null) {
      if (dateText == null && desc !== '' && rows.length > 0 && amountsIn(rec, cols).length === 0 && !isFooter(desc)) {
        const last = rows.pop()!;
        rows.push({ ...last, description: (last.description + ' ' + desc).trim() });
      }
      continue;
    }
    if (isSummaryLine(desc)) continue;
    const [date, time0] = parsedDate;
    const timeCell = cell('TIME');
    const time = time0 ?? (timeCell != null ? parseTime(timeCell) : null);
    const balText = cell('BALANCE');
    const balance = balText != null ? parseMoney(balText)?.[0] ?? null : null;
    const refCell = cell('REF');
    const ref = refCell != null && /\d/.test(refCell) && trimChars(refCell, '0') !== '' ? refCell : null;
    const nz = (t: string | null) => {
      if (t == null) return null;
      const p = parseMoney(t);
      return p && p[0] !== 0 ? p : null;
    };
    const debit = nz(cell('DEBIT'));
    const credit = nz(cell('CREDIT'));
    const amount = nz(cell('AMOUNT'));
    const drcrCell = cell('DRCR');
    const drcr = drcrCell != null ? markerType(drcrCell) : null;
    let value: number;
    let type: TxnType;
    if (debit != null && credit == null) { value = Math.abs(debit[0]); type = 'DEBIT'; }
    else if (credit != null && debit == null) { value = Math.abs(credit[0]); type = 'CREDIT'; }
    else if (amount != null) {
      const t = drcr ?? amount[1] ?? (amount[0] < 0 ? 'DEBIT' : null);
      if (t == null) pendingDirection.push(rows.length);
      value = Math.abs(amount[0]);
      type = t ?? 'DEBIT';
    } else continue;
    rows.push(row({ date, time, description: cleanDesc(desc), amountMinor: value, type, balanceMinor: balance, reference: referenceIn(desc) ?? ref }));
  }
  if (rows.length === 0) return null;
  const hasBalance = rows.filter((r) => r.balanceMinor != null).length >= Math.floor(rows.length / 2);
  const kind: StatementKind = hasBalance && !looksLikeCard(preamble) ? 'BANK' : 'CARD';
  const fixed = pendingDirection.length === 0 ? rows : fixDirections(rows, new Set(pendingDirection), kind);
  return statement({
    rows: fixed, kind, bank: bankIn(preamble), accountTail: accountTailIn(preamble),
    check: kind === 'BANK' ? StatementChecks.bank(fixed) : null, numbersSeen: numbersIn(preamble),
  });
}

function amountsIn(rec: string[], cols: Map<Col, number>): number[] {
  const out: number[] = [];
  for (const c of ['DEBIT', 'CREDIT', 'AMOUNT'] as Col[]) {
    const i = cols.get(c);
    if (i == null || rec[i] == null) continue;
    const p = parseMoney(rec[i]);
    if (p) out.push(p[0]);
  }
  return out;
}

function fixDirections(rows: StatementRow[], unknown: Set<number>, kind: StatementKind): StatementRow[] {
  const out = rows.slice();
  const dirs = kind === 'BANK' ? directionsFromBalance(rows.map((r) => [r.amountMinor, r.balanceMinor])) : null;
  for (const i of unknown) {
    const t = dirs?.[i] ?? guessType(rows[i].description, kind);
    out[i] = { ...out[i], type: t };
  }
  return out;
}

// ---------------------------------------------------------------- PDF text

const DATE_RE =
  String.raw`(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}|\d{1,2}[\s\-/]?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*[\s\-/,]*\d{2,4}|\d{4}-\d{2}-\d{2})`;
const midStart = new RegExp(String.raw`(?<=^|\s)` + DATE_RE + String.raw`\s+(?=\d{8,}\s)`, 'i');
const lineStart = new RegExp(
  String.raw`^\s*(?:\d{1,4}\s+)?` + DATE_RE + String.raw`(?:\s*\|?\s*(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[ap]m)?))?\s*\|?\s*`, 'i',
);
const dateAnywhere = new RegExp(String.raw`(?<![\d/])` + DATE_RE + String.raw`(?![\d/])`, 'gi');
const moneyRe = new RegExp(
  String.raw`(?<![\w.,])(?:(?:rs\.?|inr|₹)\s*)?(\(?[+\-]?(?:\d{1,3}(?:,\d{2,3})+|\d+)\.\d{2}\)?)(?:\s*(cr|dr|c|d)\b)?(?![\w.]|,\d)|(?<![\w.,])(?:rs\.?|inr|₹)\s*((?:\d{1,3}(?:,\d{2,3})+|\d+))(?:\s*(cr|dr)\b)?(?![\w.]|,\d)`,
  'i',
);

/**
 * Parses statement text taken from a PDF, one transaction per line. Lines that start with a date and contain
 * amounts are transactions; lines without either that follow a transaction continue its description.
 */
export function fromText(text: string): ParsedStatement | null {
  const web = cardWebView(text);
  if (web) return web;
  const numbered = numberedBank(text);
  if (!numbered) return fromLines(text);
  const all = lines(text);
  const rest = fromLines(all.filter((_, i) => !numbered[1].has(i)).join('\n'));
  const n = numbered[0];
  if (rest == null || rest.rows.length === 0) return n;
  const joined = [...rest.rows, ...n.rows.map((r) => ({ ...r, cardTail: r.cardTail ?? n.accountTail }))];
  const counts = new Map<string, number>();
  for (const r of joined) if (r.cardTail != null) counts.set(r.cardTail, (counts.get(r.cardTail) ?? 0) + 1);
  let tail: string | null = null;
  let best = -1;
  for (const [k, v] of counts) if (v > best) { best = v; tail = k; }
  tail = tail ?? n.accountTail ?? rest.accountTail;
  return statement({
    rows: joined, kind: 'BANK', bank: rest.bank ?? n.bank, accountTail: tail,
    numbersSeen: [...rest.numbersSeen, ...n.numbersSeen],
  });
}

interface PLine {
  date: LocalDate;
  time: LocalTime | null;
  desc: string;
  amounts: [number, TxnType | null][];
  raw: string;
  cardDesc: string;
  card: string | null;
  acct: string | null;
  section: number;
  serial: string | null;
  foreign: string | null;
}

function fromLines(text: string): ParsedStatement | null {
  const ls = lines(text);
  const zeroTailFull = full(zeroTail);
  const zeroMarked = ls.filter((l) => zeroTailFull.test(l.trim()) && lineStart.exec(l.trim()) == null).length >= 3;
  let prefix: string | null = null;
  const nextIsTxn = (from: number): boolean => {
    for (let k = from; k < ls.length; k++) {
      const t = nbsp(ls[k]).trim();
      if (t === '') continue;
      const st = lineStart.exec(t);
      if (!st) return false;
      return parseDate(st[1]) != null && moneyRe.exec(t.slice(end(st))) != null;
    }
    return false;
  };
  const parsed: PLine[] = [];
  let openingBalance: number | null = null;
  let lastWasTxn = false;
  let continuation = 0;
  let headerText = '';
  let card: string | null = null;
  let acct: string | null = null;
  let section = 0;
  const openings = new Map<string | null, number>();
  for (let lineNo = 0; lineNo < ls.length; lineNo++) {
    let line = nbsp(ls[lineNo]).trim();
    if (line === '') { lastWasTxn = false; continue; }
    if (parsed.length === 0 && headerText.length < 4000) headerText += line + '\n';
    const low = line.toLowerCase();
    if (!openings.has(acct) && (low.includes('opening balance') || low.includes('balance b/f') || low.includes('brought forward') || /\sb\/f\s/.test(` ${low} `))) {
      const all = findAll(moneyRe, line);
      const m = all[all.length - 1];
      if (m) {
        const v = parseMoney(m[0]);
        if (v) { openings.set(acct, v[0]); if (openingBalance == null) openingBalance = v[0]; }
      }
    }
    let start = lineStart.exec(line);
    let date = start ? parseDate(start[1]) : null;
    if (start == null || date == null) {
      const m = midStart.exec(line);
      if (m) {
        const cut = line.slice(m.index).trim();
        const s2 = lineStart.exec(cut);
        const d2 = s2 ? parseDate(s2[1]) : null;
        if (s2 && d2) { line = cut; start = s2; date = d2; }
      }
    }
    if (start == null || date == null) {
      if (moneyRe.exec(line) == null) {
        const cardHit = cardNumberRe.exec(line);
        const acctHit = accountTailOfHeading(line);
        if (cardHit || acctHit) {
          if (cardHit) card = cardHit[1];
          if (acctHit) acct = acctHit;
          section++;
          lastWasTxn = false;
          continue;
        }
      }
      const extra = line.replace(percentRe, ' ').trim();
      if (extra === '') continue;
      if (zeroMarked && parsed.length > 0) {
        const z = zeroTailFull.exec(extra);
        if (z && moneyRe.exec(z[1]) == null && !isFooter(extra)) {
          parsed[parsed.length - 1].desc += ' ' + z[1];
          parsed[parsed.length - 1].cardDesc += ' ' + z[1];
          continue;
        }
        if (moneyRe.exec(line) == null && countLetters(extra) >= 3 && !isFooter(line) &&
          !notDescription.test(extra) && !headingWords.test(extra) && nextIsTxn(lineNo + 1)) {
          prefix = [prefix, extra].filter((x) => x != null).join(' ');
          lastWasTxn = false;
          continue;
        }
      }
      if (lastWasTxn && continuation < 2 && moneyRe.exec(line) == null && !isFooter(line) && !notDescription.test(extra) && parsed.length > 0) {
        parsed[parsed.length - 1].desc += ' ' + extra;
        parsed[parsed.length - 1].cardDesc += ' ' + extra;
        continuation++;
      } else lastWasTxn = false;
      continue;
    }
    const rest = line.slice(end(start));
    const amounts = findAll(moneyRe, rest);
    if (amounts.length === 0 || isSummaryLine(rest)) { lastWasTxn = false; continue; }
    const time = g(start, 2).trim() !== '' ? parseTime(start[2]) : null;
    const beforeAmount = rest.slice(0, amounts[0].index).replace(dateAnywhere, ' ').trim();
    const descText = beforeAmount.replace(currencyColumn, '').trim();
    let foreign: string | null = null;
    const cm = currencyColumn.exec(beforeAmount);
    if (cm && amounts.length >= 2) foreign = `${cm[1]} ${amounts[amounts.length - 2][0].replace(/,/g, '').trim()}`;
    const values = amounts.map((m) => parseMoney(m[0])).filter((v): v is [number, TxnType | null] => v != null);
    if (values.length === 0) { lastWasTxn = false; continue; }
    const cardText = descText.replace(serialFirst, '').replace(pointsLast, '').trim();
    const sm = serialFirst.exec(descText);
    const serial = sm && sm[0].trim().length >= 10 ? sm[0].trim() : null;
    const bfr = prefix != null ? `${prefix} ` : '';
    prefix = null;
    parsed.push({
      date, time, desc: bfr + descText, amounts: values, raw: line, cardDesc: bfr + cardText, card, acct, section, serial, foreign,
    });
    lastWasTxn = true;
    continuation = 0;
  }
  if (parsed.length === 0) return null;

  const multi = parsed.filter((p) => p.amounts.length >= 2).length;
  const asBank: [number, number | null][] = parsed.map((p) =>
    p.amounts.length >= 2 ? [p.amounts[p.amounts.length - 2][0], p.amounts[p.amounts.length - 1][0]] : [p.amounts[p.amounts.length - 1][0], null],
  );
  let bankDirs: (TxnType | null)[] | null = null;
  if (multi >= parsed.length * 0.6) {
    const out: (TxnType | null)[] = [];
    let i = 0;
    while (i < parsed.length) {
      let j = i;
      while (j < parsed.length && parsed[j].acct === parsed[i].acct) j++;
      out.push(...directionsFromBalance(asBank.slice(i, j), openings.get(parsed[i].acct) ?? (i === 0 ? openingBalance : null)));
      i = j;
    }
    bankDirs = out;
  }
  const chained = bankDirs ? bankDirs.filter((d) => d != null).length : 0;
  const isBank = bankDirs != null && chained >= (parsed.length - 1) * 0.6 && !looksLikeCard(headerText);

  const cardMarksCredits = parsed.filter((p) => p.amounts[p.amounts.length - 1][1] === 'CREDIT').length >= 1 &&
    !parsed.some((p) => p.amounts[p.amounts.length - 1][1] === 'DEBIT');
  const built = parsed.map((l, i) => {
    const desc = cleanDesc(l.desc);
    if (isBank) {
      const [amt, bal] = asBank[i];
      const marker = l.amounts.length >= 2 ? l.amounts[l.amounts.length - 2][1] : null;
      return row({
        date: l.date, time: l.time, description: desc, amountMinor: Math.abs(amt),
        type: bankDirs![i] ?? marker ?? guessType(desc, 'BANK'), balanceMinor: bal != null ? Math.abs(bal) : null,
        reference: referenceIn(l.raw), cardTail: l.acct,
      });
    }
    const [amt, marker] = l.amounts[l.amounts.length - 1];
    const rawCard = l.cardDesc;
    const plus = hdfcCredit.test(rawCard);
    const cardDesc = cleanDesc(rawCard.replace(hdfcCredit, '').replace(rupeeGlyph, '').replace(convertWord, ' ')) || desc;
    const type: TxnType = marker ?? (plus || amt < 0 ? 'CREDIT' : cardMarksCredits ? 'DEBIT' : guessType(cardDesc, 'CARD'));
    return row({
      date: l.date, time: l.time, description: cardDesc, amountMinor: Math.abs(amt), type,
      reference: referenceIn(l.raw) ?? l.serial, cardTail: l.card, serial: l.serial, foreign: l.foreign,
    });
  });
  const rows = withoutRepeats(built, parsed.map((p) => p.section), isBank).filter((r) => r.amountMinor > 0);
  if (rows.length === 0) return null;
  const head = headerText;
  const kind: StatementKind = isBank ? 'BANK' : 'CARD';
  const lastDate = rows.reduce((a, r) => (r.date.isAfter(a) ? r.date : a), rows[0].date);
  let bill: [number, number | null, LocalDate] | null = null;
  if (kind === 'CARD') {
    try {
      const b = billSummaryFromText(text, lastDate);
      if (b && !b[2].isBefore(lastDate.minusDays(3)) && !b[2].isAfter(lastDate.plusDays(60))) bill = b;
    } catch { bill = null; }
  }
  const accountsInFile = new Set(rows.map((r) => r.cardTail).filter((t): t is string => t != null));
  let check: StatementCheck | null = null;
  try {
    if (kind === 'BANK' && accountsInFile.size > 1) {
      const groups = new Map<string | null, StatementRow[]>();
      for (const r of rows) { const a = groups.get(r.cardTail); if (a) a.push(r); else groups.set(r.cardTail, [r]); }
      const parts = [...groups.values()].map((gr) => StatementChecks.bank(gr, null));
      check = parts.some((p) => p == null) ? null : {
        expectedMinor: parts.reduce((s, p) => s + p!.expectedMinor, 0),
        computedMinor: parts.reduce((s, p) => s + p!.computedMinor, 0),
      };
    } else if (kind === 'BANK') check = StatementChecks.bank(rows, openingBalance);
    else if (bill) {
      const prev = previousBalanceIn(text);
      check = prev != null ? StatementChecks.card(rows, prev, bill[0]) : null;
    }
  } catch { check = null; }
  const cardsSeen = [...new Set(parsed.map((p) => p.card).filter((c): c is string => c != null))].map((c) => [null, c] as [string | null, string]);
  return statement({
    rows, kind, bank: bankIn(head) ?? bankIn(text.slice(0, 6000)), accountTail: accountTailIn(head) ?? accountTailIn(text.slice(0, 6000)),
    billTotalMinor: bill?.[0] ?? null, billMinMinor: bill?.[1] ?? null, billDueDate: bill?.[2] ?? null, check,
    numbersSeen: [...numbersIn(text.slice(0, 8000)), ...cardsSeen],
  });
}

/**
 * For each row, the direction implied by the change in running balance from the row before (or after, for
 * statements listed newest first). Null where the balances do not tell.
 */
export function directionsFromBalance(rows: [number, number | null][], opening: number | null = null): (TxnType | null)[] {
  const dir = (prev: number | null | undefined, amt: number, bal: number | null): TxnType | null => {
    if (prev == null || bal == null) return null;
    const a = Math.abs(amt);
    const d = bal - prev;
    if (d === a) return 'CREDIT';
    if (d === -a) return 'DEBIT';
    return null;
  };
  const asc = rows.map(([amt, bal], i) => dir(i === 0 ? opening : rows[i - 1][1], amt, bal));
  const desc = rows.map(([amt, bal], i) => dir(rows[i + 1]?.[1], amt, bal));
  const n = (l: (TxnType | null)[]) => l.filter((x) => x != null).length;
  return n(desc) > n(asc) ? desc : asc;
}

const creditWords =
  /\b(neft\s*cr|imps\s*cr|upi\s*\/?\s*cr|by\s+transfer|by\s+clg|deposit|salary|sal\b|refund|reversal|cashback|interest\s+(?:paid|credit)|int\.?pd|credit\s+interest|payment\s+received|thank\s*you|received|\brev\b)/i;

function guessType(desc: string, _kind: StatementKind): TxnType {
  return creditWords.test(desc) ? 'CREDIT' : 'DEBIT';
}

// ---------------------------------------------------------------- values

function markerType(s: string): TxnType | null {
  let t = s.trim().toLowerCase();
  while (t.endsWith('.')) t = t.slice(0, -1);
  if (['cr', 'c', 'credit', '+', 'deposit'].includes(t)) return 'CREDIT';
  if (['dr', 'd', 'debit', '-', 'withdrawal'].includes(t)) return 'DEBIT';
  return null;
}

const markerEnd = /\s*\(?\b(cr|dr|c|d)\b\)?\.?$/i;

/** Reads "1,234.56", "1,234.56 Cr", "-450.00", "(450.00)", "₹ 450" or "INR 1,200" into paise, with the Dr / Cr marker. */
export function parseMoney(text: string): [number, TxnType | null] | null {
  let s = nbsp(text.trim());
  if (s === '') return null;
  let marker: TxnType | null = null;
  const m = markerEnd.exec(s);
  if (m) { marker = markerType(m[1]); s = s.slice(0, m.index); }
  s = s.replace(/^(rs\.?|inr|₹)\s*/i, '').replace(/\s*(rs\.?|inr|₹)$/i, '').trim();
  let negative = false;
  if (s.startsWith('(') && s.endsWith(')')) { negative = true; s = s.slice(1, -1); }
  if (s.startsWith('-')) { negative = true; s = s.slice(1); } else if (s.startsWith('+')) s = s.slice(1);
  if (s.endsWith('-')) { negative = true; s = s.slice(0, -1); }
  s = s.replace(/,/g, '').replace(/ /g, '');
  if (!/^(?:\d+(\.\d+)?|\.\d+)$/.test(s)) return null;
  const minor = decimalToMinor(s);
  if (minor == null) return null;
  return [negative ? -minor : minor, marker];
}

const MONTHS: Record<string, number> = {
  jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12,
};
const numericDate = /^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})$/;
const isoDate = /^(\d{4})-(\d{1,2})-(\d{1,2})$/;
const textDate = /^(\d{1,2})(?:st|nd|rd|th)?[\s\-/]?([a-z]{3,9})\.?[\s\-/,]*(\d{2,4})$/i;
const textDateUs = /^([a-z]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})$/i;
const dateTimeCell = /^(.+?)(?:[\sT]+(\d{1,2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:\s*[ap]\.?m\.?)?))?$/i;

/** A whole cell holding a date, optionally followed by a time ("05/04/2025 14:32:10"). */
export function parseDateTime(cell: string): [LocalDate, LocalTime | null] | null {
  const s = nbsp(cell.trim());
  if (/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(s)) {
    const n = parseFloat(s);
    if (n >= 30000 && n <= 80000) {
      const days = Math.floor(n);
      const secs = Math.floor((n - days) * 86400);
      const base = LocalDate.mustOf(1899, 12, 30).plusDays(days);
      return [base, secs > 0 ? LocalTime.ofSecondOfDay(Math.min(secs, 86399)) : null];
    }
    return null;
  }
  const m = dateTimeCell.exec(s);
  if (!m) return null;
  const date = parseDate(m[1].trim());
  if (!date) return null;
  return [date, g(m, 2).trim() !== '' ? parseTime(m[2]) : null];
}

export function parseDate(text: string): LocalDate | null {
  let s = text.trim();
  while (s.endsWith(',')) s = s.slice(0, -1);
  let m = numericDate.exec(s);
  if (m) return safe(yearOf(m[3]), +m[2], +m[1]);
  m = isoDate.exec(s);
  if (m) return safe(+m[1], +m[2], +m[3]);
  m = textDate.exec(s);
  if (m) {
    const month = MONTHS[m[2].toLowerCase().slice(0, 3)];
    if (!month) return null;
    return safe(yearOf(m[3]), month, +m[1]);
  }
  m = textDateUs.exec(s);
  if (m) {
    const month = MONTHS[m[1].toLowerCase().slice(0, 3)];
    if (!month) return null;
    return safe(+m[3], month, +m[2]);
  }
  return null;
}

function parseTime(text: string): LocalTime | null {
  const m = /(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?\s*([ap])?/i.exec(text);
  if (!m) return null;
  let h = +m[1];
  const min = +m[2];
  const sec = +(m[3] || '0');
  const ap = g(m, 4).toLowerCase();
  if (ap === 'p' && h < 12) h += 12;
  if (ap === 'a' && h === 12) h = 0;
  return LocalTime.of(h, min, sec);
}

function yearOf(s: string): number {
  const n = parseInt(s, 10);
  return s.length <= 2 ? 2000 + n : n;
}

function safe(y: number, m: number, d: number): LocalDate | null {
  if (y < 1990 || y > 2100) return null;
  return LocalDate.of(y, m, d);
}

// ---------------------------------------------------------------- text hints

const summaryWords =
  /^\W*(opening|closing)\s+balance|^\W*total\b|balance\s+(b\/f|c\/f|brought|carried)|^\W*(b\/f|c\/f|brought\s+forward|carried\s+forward)(\W|$)|^\W*(previous|last)\s+(statement\s+)?balance|^\W*(minimum|total)\s+(amount\s+)?due|^\W*statement\s+(date|period)|^\W*payment\s+due\s+date/i;

function isSummaryLine(desc: string) {
  return summaryWords.test(desc.trim());
}

const footerWords =
  /\bpage\s+\d|\bpage\b.*\bof\b|statement\s+of\s+account|computer\s+generated|does\s+not\s+require|registered\s+office|customer\s+care|call\s+us|www\.|https?:\/\/|\*{3,}|^-{3,}|^date\b.*\b(narration|description|particulars)/i;

function isFooter(line: string) {
  return footerWords.test(line);
}

const numberedStart = /^(\d{1,5})\s+(\d{2}[./-]\d{2}[./-]\d{4})\b\s*(.*)$/;
const amountAndBalance = /^(.*?)\s*(-?[\d,]+\.\d{2})\s+(-?[\d,]+\.\d{2})\s*$/;
const numberedAccount = /statement\s+of\s+transactions\s+in\s+\w+\s+account\s+(?:no\.?\s*)?[Xx*\d]*?(\d{4})\b/i;
const numberedHeaderLine = /^(s\s*no\.?\s+transaction.*|date\s+cheque\s+number.*|amount\s+\(inr\).*)$/i;
const numberedHeader = /S\s*No\.?\s+Transaction/i;
const pageNoise =
  /^(\d{1,4}|www\.\S+.*|please\s+call.*|never\s+share.*|s\s*no\.?\s+transaction.*|date\s+cheque.*|amount\s+\(inr\).*|.*legends?\s+used.*|.*this\s+is\s+a(n)?\s+(system|computer).*|.*end\s+of\s+statement.*)$/i;

/**
 * Bank statements where each transaction is numbered (ICICI savings, 2022 on): a line with the number, the date and
 * the cheque / UPI reference column, then the remarks over a few lines, one of which ends with the amount and the balance.
 */
export function numberedBank(text: string): [ParsedStatement, Set<number>] | null {
  if (!numberedHeader.test(text)) return null;
  const used = new Set<number>();
  interface N { date: LocalDate; refCol: string; desc: string; acct: string | null; amount: number | null; balance: number | null; open: boolean }
  let acct: string | null = null;
  const out: N[] = [];
  let expectNo = 1;
  const all = lines(text);
  for (let idx = 0; idx < all.length; idx++) {
    const t = nbsp(all[idx]).trim();
    if (t === '') continue;
    const am = numberedAccount.exec(t);
    if (am) acct = am[1];
    const st = numberedStart.exec(t);
    if (st && (parseInt(st[1], 10) === expectNo || (st[1] === '1' && out.length > 0))) {
      if (st[1] === '1') expectNo = 1;
      used.add(idx);
      const date = parseDate(st[2].replace(/\./g, '/'));
      if (!date) continue;
      expectNo++;
      const rest = st[3];
      const n: N = { date, refCol: rest, desc: '', acct, amount: null, balance: null, open: true };
      const m = amountAndBalance.exec(rest);
      if (m) { n.amount = parseMoney(m[2])?.[0] ?? null; n.balance = parseMoney(m[3])?.[0] ?? null; }
      out.push(n);
      continue;
    }
    if (full(numberedHeaderLine).test(t)) { used.add(idx); continue; }
    const n = out[out.length - 1];
    if (!n) continue;
    if (!n.open) continue;
    used.add(idx);
    if (pageNoise.test(t)) { if (n.amount != null) n.open = false; continue; }
    if (n.amount == null) {
      const m = amountAndBalance.exec(t);
      if (m) {
        n.amount = parseMoney(m[2])?.[0] ?? null;
        n.balance = parseMoney(m[3])?.[0] ?? null;
        n.desc += ' ' + m[1];
        continue;
      }
    }
    if (n.desc.length < 240) n.desc += ' ' + t; else n.open = false;
  }
  const done = out.filter((n) => n.amount != null && n.amount !== 0);
  if (done.length < 2 || done.length < out.length * 0.9) return null;
  const dirs = directionsFromBalance(done.map((n) => [Math.abs(n.amount!), n.balance]));
  const rows = done.map((n, i) => {
    const desc = cleanDesc(n.desc);
    const rc = n.refCol.trim();
    const refCol = /^\d{6,}$/.test(rc) ? rc : null;
    const type: TxnType = dirs[i] ?? (/debit/i.test(n.refCol) ? 'DEBIT' : guessType(desc, 'BANK'));
    return row({
      date: n.date, description: desc, amountMinor: Math.abs(n.amount!), type,
      balanceMinor: n.balance != null ? Math.abs(n.balance) : null, reference: referenceIn(desc) ?? refCol, cardTail: n.acct,
    });
  });
  const hm = numberedHeader.exec(text);
  const headRaw = hm ? text.slice(0, hm.index) : '';
  const head = headRaw.trim() !== '' ? headRaw : text.slice(0, 3000);
  let check: StatementCheck | null = null;
  try { check = StatementChecks.bank(rows, null); } catch { check = null; }
  return [statement({
    rows, kind: 'BANK', bank: bankIn(head) ?? bankIn(text.slice(0, 6000)), accountTail: accountTailIn(head) ?? accountTailIn(text.slice(0, 6000)),
    check, numbersSeen: numbersIn(text.slice(0, 8000)),
  }), used];
}

const webViewLine = /^(\d{2}-\d{2}-\d{4})\s+(.*?)\s+(\d[\d,]*(?:\.\d{1,2})?)\s+(Dr|Cr)\.?\s+(-?\d+)\s+(\d{9,16})\s*$/i;
const webViewHeader = /reward\s+points\s+reference\s+number/i;
const webViewEnd = /^(page\s+\d|note|important|disclaimer|\*|for\s+any|this\s+is\s+a)/i;

function inr(label: string, text: string): number | null {
  const m = new RegExp(label + String.raw`\s+INR\s+(-?[\d,]+(?:\.\d{1,2})?)`, 'i').exec(text);
  return m ? parseMoney(m[1])?.[0] ?? null : null;
}

function ddmmyyyy(s: string): LocalDate | null {
  const m = /^(\d{2})-(\d{2})-(\d{4})$/.exec(s);
  return m ? LocalDate.of(+m[3], +m[2], +m[1]) : null;
}

/** The card statement as ICICI's website shows it ("View last statement", printed to PDF). */
export function cardWebView(text: string): ParsedStatement | null {
  if (!webViewHeader.test(text)) return null;
  interface W { date: LocalDate; desc: string; amount: number; type: TxnType; ref: string }
  const out: W[] = [];
  let open = false;
  for (const raw of lines(text)) {
    const t = nbsp(raw).trim();
    if (t === '') continue;
    const m = webViewLine.exec(t);
    if (m) {
      const date = ddmmyyyy(m[1]);
      if (!date) continue;
      const amount = parseMoney(m[3])?.[0];
      if (amount == null) continue;
      const type: TxnType = m[4].toLowerCase() === 'cr' ? 'CREDIT' : 'DEBIT';
      out.push({ date, desc: m[2], amount: Math.abs(amount), type, ref: m[6] });
      open = true;
    } else if (open) {
      if (webViewEnd.test(t) || lineStart.exec(t) != null) { open = false; continue; }
      out[out.length - 1].desc += ' ' + t;
    }
  }
  if (out.length < 2) return null;
  const rows = out.map((w) => {
    const desc = cleanDesc(w.desc);
    return row({ date: w.date, description: desc, amountMinor: w.amount, type: w.type, reference: referenceIn(desc) ?? w.ref, serial: w.ref });
  }).filter((r) => r.amountMinor > 0);
  const due = inr('Total Amount Due', text);
  const previous = inr('Previous Balance', text);
  const dm = /Payment\s+Due\s+Date\s+(\d{2}-\d{2}-\d{4})/i.exec(text);
  const dueDate = dm ? ddmmyyyy(dm[1]) : null;
  const check = due != null && previous != null ? StatementChecks.card(rows, previous, due) : null;
  return statement({
    rows, kind: 'CARD', bank: bankIn(text.slice(0, 6000)) ?? 'ICICI Bank', accountTail: accountTailIn(text.slice(0, 6000)),
    billTotalMinor: due, billMinMinor: inr('Minimum Amount Due', text), billDueDate: dueDate, check,
    numbersSeen: numbersIn(text.slice(0, 8000)),
  });
}

const cardNumberRe = /(?<![\w*])(?:\d{4}|[Xx*]{4})[\s-]?(?:\d{2}[Xx*]{2}|[Xx*]{4})[\s-]?[Xx*]{4}[\s-]?(\d{4})\b/;
const accountWord = /\b(?:a\/c|acct|account)\b/i;
const accountHeading = /\b(?:savings?|current|salary|overdraft|nre|nro)\s+(?:a\/c|acct|account)\b|\b(?:a\/c|acct|account)\s*(?:no|number|num)\b/i;
const narrationCodes = /\b(?:neft|imps|rtgs|upi|trf|inft|mmt|transfer|transferred|credited|debited|bil)\b/i;
const accountNumber = /(?<![\w/])(?:[Xx*•]{2,}[\s-]?(?:[Xx*•]+[\s-]?)*\d{3,8}|\d{9,18})(?![\w/])/;

/** The last 4 digits of the account a heading line names ("Savings Account XXXXXXXX1234"). */
export function accountTailOfHeading(line: string): string | null {
  if (!accountHeading.test(line) || narrationCodes.test(line)) return null;
  const w = accountWord.exec(line);
  if (!w) return null;
  if (cardNumberRe.test(line) && /card/i.test(line)) return null;
  const n = findFrom(accountNumber, line, end(w));
  if (!n || n.index - (end(w) - 1) >= 40) return null;
  const d = digitsOnly(n[0]).slice(-4);
  return d.length === 4 ? d : null;
}

/** Consolidated statements can print the same statement (or month) twice: repeats are left out. */
function withoutRepeats(rows: StatementRow[], sections: number[], bank: boolean): StatementRow[] {
  if (bank) {
    const kept = new Map<string, string[]>();
    return rows.filter((r) => {
      if (r.balanceMinor == null) return true;
      const k = JSON.stringify([r.cardTail, r.date.toString(), r.amountMinor, r.type, r.balanceMinor]);
      const d = r.description.toLowerCase().replace(/\s+/g, ' ').trim();
      let list = kept.get(k);
      if (!list) { list = []; kept.set(k, list); }
      if (list.some((it) => it === d || (d.length >= 24 && it.includes(d)) || (it.length >= 24 && d.includes(it)))) return false;
      list.push(d);
      return true;
    });
  }
  const key = (r: StatementRow) => JSON.stringify([r.serial, r.date.toString(), r.amountMinor, r.type]);
  const plain = (r: StatementRow) => JSON.stringify([r.date.toString(), r.amountMinor, r.type, r.description.toLowerCase()]);
  const seenBefore = new Set<string>();
  const plainBefore = new Set<string>();
  const bySection = new Map<number, number[]>();
  rows.forEach((_, i) => {
    const s = sections[i];
    const a = bySection.get(s);
    if (a) a.push(i); else bySection.set(s, [i]);
  });
  const keep = rows.map(() => true);
  for (const s of [...bySection.keys()].sort((a, b) => a - b)) {
    const idx = bySection.get(s)!;
    const numbered = idx.filter((i) => rows[i].serial != null);
    const repeats = numbered.filter((i) => seenBefore.has(key(rows[i])));
    if (numbered.length > 0 && repeats.length === numbered.length) idx.forEach((i) => { keep[i] = false; });
    else {
      repeats.forEach((i) => { keep[i] = false; });
      if (repeats.length > 0) idx.filter((i) => rows[i].serial == null && plainBefore.has(plain(rows[i]))).forEach((i) => { keep[i] = false; });
    }
    numbered.forEach((i) => seenBefore.add(key(rows[i])));
    idx.forEach((i) => plainBefore.add(plain(rows[i])));
  }
  return rows.filter((_, i) => keep[i]);
}

const percentRe = /\S*\d+(?:\.\d+)?%/g;
const zeroTail = /^(.*[A-Za-z0-9].*?)\s+0\.00$/;
const headingWords = /\b(PARTICULARS|WITHDRAWALS|DEPOSITS|BALANCE|NARRATION|DESCRIPTION)\b|^DATE\b/i;
const notDescription =
  /^#|^\d{1,2}:\d{2}(?::\d{2})?\s*(?:am|pm)?$|transaction\s+details|card\s+(number|account)|category\s+of\s+service|registration\s*no|\brewards?\b|\bpoints\b|invoice\s+no|\bcin\b|^(date|serno|sl\.?\s*no)\b/i;
const hdfcCredit = /(?:^|\s)\+\s*(?:C|₹)?\s*$/;
const rupeeGlyph = /\s+C$/;
const convertWord = /\bConvert\b/g;
const prevBalanceLabel = /\b(?:previous|last|opening)\s+(?:statement\s+)?(?:balance|dues|outstanding)\b/i;
const iciciSummary = /=\s*\+\s*\+\s*-\s*\n\s*\S?\s*[\d,]+\.\d{2}\s*\n\s*\S?\s*([\d,]+\.\d{2})/;

/** A card statement's previous balance ("Previous Balance ₹12,345.00"), when printed with its label. */
export function previousBalanceIn(text: string): number | null {
  const flat = nbsp(text).slice(0, 8000);
  const im = iciciSummary.exec(flat);
  if (im) return parseMoney(im[1])?.[0] ?? null;
  const at = prevBalanceLabel.exec(flat);
  if (!at) return null;
  const m = findFrom(moneyRe, flat, end(at));
  if (!m || m.index - (end(at) - 1) >= 60) return null;
  const p = parseMoney(m[0]);
  if (!p) return null;
  return p[1] === 'CREDIT' ? -Math.abs(p[0]) : p[0];
}

const currencyColumn = /\s+(USD|EUR|GBP|AED|SGD|MYR|THB|JPY|AUD|CAD|CHF|HKD|CNY|LKR|NPR|SAR|QAR|NZD|IDR|VND|KRW|ZAR)$/;
const serialFirst = /^\d{9,24}\s+/;
const pointsLast = /\s+-?\d{1,6}$/;
const upiRef = /(?<!\d)(\d{12})(?!\d)/;

/** The 12-digit UPI / IMPS reference in a narration; SMS alerts quote the same number. */
export function referenceIn(text: string): string | null {
  const m = upiRef.exec(text);
  return m ? m[1] : null;
}

const cardHints = /credit\s*card|card\s*statement|card\s*no|card\s*number|minimum\s+amount\s+due|total\s+amount\s+due|payment\s+due\s+date|credit\s+limit/i;

function looksLikeCard(text: string) {
  return cardHints.test(text);
}

const tailRe = /\b(?:a\/?c|acc(?:ount|t)?|card)\s*(?:no|number|num)?\.?\s*[:#\-]?\s*([0-9Xx*•\s\-]{6,24}\d{4})\b/i;
const maskedRe = /\b[0-9]{4,6}[Xx*•]{4,}[0-9Xx*•]*?(\d{4})\b|\b[Xx*•]{4,}\s?(\d{4})\b|\b\d{4}\s[Xx*]{4}\s[Xx*]{4}\s(\d{4})\b/;
const numberRe = /(?<![\d])(\d{4,6})?[\sXx*•-]*[Xx*•]{2,}[\sXx*•-]*(\d{4})(?!\d)|(?<![\d])(\d{9,18})(?!\d)/g;

/** Every masked or full card / account number in [text], as (first digits when printed, last 4). */
export function numbersIn(text: string): [string | null, string][] {
  const out: [string | null, string][] = [];
  const seen = new Set<string>();
  for (const m of findAll(numberRe, text)) {
    let p: [string | null, string];
    if (g(m, 2) !== '') p = [g(m, 1) || null, m[2]];
    else {
      const d = m[3];
      p = [d.length >= 15 ? d.slice(0, 6) : null, d.slice(-4)];
    }
    const k = JSON.stringify(p);
    if (!seen.has(k)) { seen.add(k); out.push(p); }
  }
  return out;
}

export function accountTailIn(text: string): string | null {
  const m = tailRe.exec(text);
  if (m) return digitsOnly(m[1]).slice(-4);
  const k = maskedRe.exec(text);
  if (k) return [k[1], k[2], k[3]].find((x) => x != null && x !== '') ?? null;
  return null;
}

const banks: [string, RegExp][] = [
  ['HDFC Bank', /\bhdfc\b/gi],
  ['ICICI Bank', /\bicici\b/gi],
  ['SBI', /\bstate bank of india\b|\bsbi\s*card\b|\bsbi\b/gi],
  ['Axis Bank', /\baxis\s*bank\b/gi],
  ['Kotak', /\bkotak\b/gi],
  ['Yes Bank', /\byes\s*bank\b/gi],
  ['IndusInd', /\bindusind\b/gi],
  ['IDFC First', /\bidfc\b/gi],
  ['Canara Bank', /\bcanara\b/gi],
  ['Bank of Baroda', /\bbank of baroda\b/gi],
  ['PNB', /\bpunjab national\b/gi],
  ['Union Bank', /\bunion bank\b/gi],
  ['AU Bank', /\bau small finance\b/gi],
  ['Federal Bank', /\bfederal bank\b/gi],
  ['American Express', /\bamerican express\b/gi],
  ['RBL Bank', /\brbl\b/gi],
];

/** The bank that most often names itself in [text]; the first mention wins ties. */
export function bankIn(text: string): string | null {
  let best: string | null = null;
  let bestCount = 0;
  let bestFirst = Infinity;
  for (const [name, re] of banks) {
    const all = findAll(re, text);
    if (all.length === 0) continue;
    const first = all[0].index;
    if (all.length > bestCount || (all.length === bestCount && first < bestFirst)) {
      best = name; bestCount = all.length; bestFirst = first;
    }
  }
  return best;
}

// ---------------------------------------------------------------- PayeeNames

export const PayeeNames = {
  /** A UPI handle or reference that is a code ("yblec123879a52a"), not a name. */
  isCode(name: string | null | undefined): boolean {
    if (name == null) return false;
    const n = before(name.trim(), '@');
    if (n.length < 10 || /\s/.test(n)) return false;
    const digits = countDigits(n);
    const letters = countLetters(n);
    if (digits < 3 || letters < 3) return false;
    let switches = 0;
    const isD = (c: string) => /\p{Nd}/u.test(c);
    for (let i = 1; i < n.length; i++) if (isD(n[i]) !== isD(n[i - 1])) switches++;
    return switches >= 3 || (switches >= 2 && digits >= 6);
  },
  brandIn(text: string | null | undefined): string | null {
    if (text == null) return null;
    for (const [re, name] of payeeBrands) if (re.test(text)) return name;
    return null;
  },
  better(merchant: string | null, text: string | null): string | null {
    if (merchant != null && !PayeeNames.isCode(merchant)) return merchant;
    return PayeeNames.brandIn(text) ?? merchant;
  },
};

const payeeBrands: [RegExp, string][] = ([
  [String.raw`\bjio\s*(?:fiber|fibre|air\s*fiber|airfiber|broadband|home|fixed\s*voice)|\bjiohom|\bjio\b.{0,40}\bbroadband\b|\bbroadband\b.{0,40}\bjio\b`, 'Jio broadband'],
  [String.raw`\bairtel\s*(?:xstream|fiber|fibre|broadband|black)\b|\bairtel\b.{0,40}\bbroadband\b`, 'Airtel broadband'],
  [String.raw`\breliance\s*jio\b|\bjio\b|\bjiomobility\b|\brjil\b`, 'Jio'],
  [String.raw`\bairtel\b|\bbharti\s*airtel\b`, 'Airtel'],
  [String.raw`\bvodafone\b|\bvi\s+(?:postpaid|prepaid)\b`, 'Vi'],
  [String.raw`\bbsnl\b`, 'BSNL'],
  [String.raw`\bact\s*fibernet\b`, 'ACT Fibernet'],
  [String.raw`\bhathway\b`, 'Hathway'],
  [String.raw`\btata\s*play\b`, 'Tata Play'],
  [String.raw`\bbescom\b`, 'BESCOM'],
  [String.raw`\btangedco\b|\btneb\b`, 'TANGEDCO'],
] as [string, string][]).map(([p, n]) => [new RegExp(p, 'i'), n]);

// ---------------------------------------------------------------- Narration

const ifsc = /^[A-Z]{4}0[A-Z0-9]{6}$/;
const bankCode = /^[A-Z]{4}$|^[A-Z]{4}\d{1,7}$/;
const noise = new Set([
  'UPI', 'DR', 'CR', 'P2M', 'P2A', 'PAYMENT', 'PAY', 'PAID', 'VIA', 'NEFT', 'IMPS', 'RTGS', 'MMT', 'POS', 'ECOM', 'ACH', 'NACH',
  'TRF', 'TRANSFER', 'TO', 'BY', 'FROM', 'INB', 'IB', 'MOB', 'MB', 'BIL', 'BILLPAY', 'ONL', 'UPIINTENT', 'COLLECT', 'REQ',
  'SENT', 'USING', 'THE', 'NA', 'NULL', 'OTHERS', 'REF', 'NO', 'TXN', 'INR', 'IN', 'IND', 'INDIA', 'PVT', 'LTD',
  'RVSL', 'REV', 'AUTOPAY', 'MANDATE', 'EXECUTE', 'OK', 'YBL', 'IBL', 'AXL', 'PAYTM', 'APL', 'OKAXIS', 'OKICICI',
  'OKHDFCBANK', 'OKSBI',
]);
const codeToken = /\b[A-Za-z]{2,4}[0-9a-fA-F]{16,}\b|\b[0-9a-f]{16,}\b/g;

const narrationBrands: [RegExp, string][] = ([
  [String.raw`\bSWIGGY|UPISWIGGY`, 'Swiggy'],
  [String.raw`\bZOMATO`, 'Zomato'],
  [String.raw`\bZEPTO`, 'Zepto'],
  [String.raw`\bBLINKIT|\bGROFERS`, 'Blinkit'],
  [String.raw`\bBBDAIL|\bBB\s?DAILY|\bBBNOW|\bBIG\s?BASKET|SUPERMARKET\s+GROCERY\s+SUPP`, 'BigBasket'],
  [String.raw`AMAZONCHANNELS|PRIME\s?VIDEO`, 'Amazon Prime Video'],
  [String.raw`\bAMAZON`, 'Amazon'],
  [String.raw`\bFLIPKART`, 'Flipkart'],
  [String.raw`\bMYNTRA`, 'Myntra'],
  [String.raw`\bIRCTC`, 'IRCTC'],
  [String.raw`\bHUNGER\s?BO\s?X`, 'Hungerbox'],
  [String.raw`\bSMARTQ`, 'SmartQ'],
  [String.raw`SHAREKHAN`, 'Sharekhan'],
  [String.raw`\bLIC\s?HOUSING|LICHOUSING`, 'LIC Housing Finance'],
  [String.raw`\bHDFC\s?LTD|\bHDFCLTD`, 'HDFC Ltd'],
  [String.raw`\bBOB\s+LOAN`, 'Bank of Baroda loan'],
  [String.raw`\bBAJAJ\s?FIN|\bBAJAJ_AUTO|\bBAJAJ\s+AUTO\s+FIN`, 'Bajaj Finance'],
  [String.raw`ICICI\s+BANK\s+CREDIT\s+CA`, 'Credit card bill'],
  [String.raw`\bCRED(CC|CLUB)?@|\bPAYMENT\s+ON\s+CRED\b|^CRED$`, 'CRED'],
  [String.raw`\bRENTOMOJO`, 'RentoMojo'],
  [String.raw`\bDUNZO`, 'Dunzo'],
  [String.raw`PLAYSTORE|\bGOOGLE\s?PLAY`, 'Google Play'],
  [String.raw`GOOGLECLOUD|\bGOOGLE\s?CLOUD`, 'Google Cloud'],
  [String.raw`\bOPENAI|\bCHATGPT`, 'OpenAI ChatGPT'],
  [String.raw`\bURBAN\s?CO(MPANY)?\b|\bURBANCOM|\bURBANCLAP`, 'Urban Company'],
  [String.raw`\bBOTTLE\s?L(AB)?\b`, 'Bottle Lab'],
  [String.raw`\bVENDOLIT`, 'Vendolite'],
  [String.raw`\bPVR\b|PVRCINEMAS`, 'PVR'],
  [String.raw`\bTENDERCUTS`, 'TenderCuts'],
  [String.raw`\bLICIOUS`, 'Licious'],
  [String.raw`\bTIMESPRIME|\bTIMES\s+PRIME`, 'Times Prime'],
  [String.raw`\bVIACOM\s?18`, 'Viacom18'],
  [String.raw`\bCROMA\b`, 'Croma'],
  [String.raw`\bREL(IANCE)?\s+RETAIL`, 'Reliance Retail'],
  [String.raw`\bSARAVANA\s+ST`, 'Saravana Stores'],
  [String.raw`\bZUDIO`, 'Zudio'],
  [String.raw`\bNANDO\s?S\b`, "Nando's"],
  [String.raw`\bUBER\b`, 'Uber'],
  [String.raw`\bOLA\s?CABS|\bOLACABS`, 'Ola'],
  [String.raw`\bRAPIDO`, 'Rapido'],
  [String.raw`\bMAKEMYTRIP`, 'MakeMyTrip'],
  [String.raw`\bDMART|AVENUE\s+SUPERMARTS`, 'DMart'],
  [String.raw`\bNETFLIX`, 'Netflix'],
  [String.raw`\bSPOTIFY`, 'Spotify'],
  [String.raw`\bHOTSTAR`, 'Disney+ Hotstar'],
  [String.raw`\bYOUTUBE`, 'YouTube'],
  [String.raw`\bMAX\s+HYPERMARKET`, 'Max Hypermarket'],
  [String.raw`\bLIFE\s?STYLE\s+INTERNATION`, 'Lifestyle'],
  [String.raw`PHONEPEMERCHANT|\bPHONEPE\s+REVERSA`, 'PhonePe'],
] as [string, string][]).map(([p, n]) => [new RegExp(p, 'i'), n]);

const modePrefix =
  /^(?:(?:NET BANKING|MOBILE BANKING|INTERNET BANKING|DEBIT CARD|CREDIT CARD|CMS TRANSACTION|OTHER ATMS|ICICI ATM|ATM|BRANCH|CASH|CLEARING|TRANSFER|CHEQUE\s+\d+|CHQ\s+\d+)\s+)+/i;
const upiFiller = full(new RegExp(
  String.raw`(PAYMENT(\s+(FROM|TO|FOR|ON|FO|FR|F|T)\b.*)?|PAY\s+TO\b.*|SENT\s+(FROM|USING|VIA)\b.*|UPI(\s+.*)?|NA|NO\s+REMARKS?|TEST|PAY|PAID|COLLECT.*|REQUEST.*|` +
  String.raw`PHONEPE.*|GOOGLE\s*PAY.*|PAYTM.*|YOU\s+ARE\s+PAYING.*|ON\s+TAPPING\s+PAY.*|PAY\s*VIA.*|MANDATE\s*EXE.*|DELE\b.*|.*\bREVERSA?L?|[A-Z]{1,2})`,
));
const gateway = /^(EASEBUZZ|RAZORPAY|RAZORPA|PAYU|CCAVENUES?|BILLDESK|CASHFREE|PAYTM|PHONEPE)$/;
const countryEnd = /\s+[A-Z]{2}\*$|\s+(IN|IND|INDIA)$/;
const cardBill = /^(?:[A-Z]{3}\s+)?CREDIT\s*(CARD|BILL|CARD\s*PAY)?\b|CREDIT\s+(BILL|CARD\s+PAY)/;
const townEnd = new RegExp(
  String.raw`\s+(MUMBAI|BANGALORE|BENGALURU|CHENNAI|CHEENAI|KANCHIPURAM|GURGAON|GURUGRAM|NEW\s+DELHI|DELHI|NOIDA|HYDERABAD|PUNE|KOLKATA|` +
  String.raw`CHROMPET|CHROMEPET|TAMBARAM|COIMBATORE|MADURAI|TRICHY|SALEM|AHMEDABAD|JAIPUR|KOCHI|THANE|NAVI\s+MUMBAI|WWW\.\S+|HTTPS?\S*)$`,
);
const filler = /^(PAYMENT|PAID|SENT|PAY|UPI)\s+(FROM|TO|VIA|USING|INTENT)\b/;
const bankPrefixes = new Set([
  'HDFC', 'ICIC', 'SBIN', 'UTIB', 'KKBK', 'YESB', 'PUNB', 'BARB', 'CNRB', 'UBIN', 'IDIB', 'IOBA', 'INDB', 'IDFB',
  'FDRL', 'PYTM', 'AIRP', 'BKID', 'MAHB', 'CBIN', 'UCBA', 'ESFB', 'AUBL', 'RATN', 'DBSS', 'SCBL', 'CITI', 'HSBC',
]);

function okName(s: string | null | undefined): string | null {
  if (s == null) return null;
  const t = trimChars(s, '.,*- ');
  const letters = countLetters(t);
  return letters >= 3 && countDigits(t) <= letters ? t : null;
}

function brandOf(text: string): string | null {
  for (const [re, name] of narrationBrands) if (re.test(text)) return name;
  return null;
}

function weak(name: string): boolean {
  return upiFiller.test(name.toUpperCase()) || name.length <= 4 || /^upi /i.test(name) || /^paytm/i.test(name) || name === 'CMS collection';
}

function known(field: string): string | null {
  const f = field.trim().toUpperCase();
  if (f.startsWith('ADD-MONEY@PAYTM')) return 'Paytm wallet';
  if (f.startsWith('GOOG-PAYMENT')) return 'Google Pay';
  if (f === 'HDFCLTD' || f === 'HDFC LTD') return 'HDFC Ltd';
  return null;
}

function named(d0: string): string | null {
  const d1 = d0.replace(modePrefix, '').trim();
  const pm = /(?:^|[\s/])(BIL|UPI|MMT|NEFT|ACH|NACH|DECS|VPS|MPS|IPS|PCD|CMS|CLG|NFS)\//.exec(d1);
  const startAt = pm ? pm.index + pm[0].indexOf(pm[1]) : -1;
  const d = (startAt >= 0 ? d1.slice(startAt) : d1)
    .replace(/\b(MOBILE|NET|INTERNET)\s+BANKING\b\s*/gi, '')
    .replace(/^ACH\/TP\s+ACH\s+/i, 'ACH/').trim();
  const up = d.toUpperCase();
  if (/REV\s+SWEEP\s+FROM|SWEEP\s+FROM/.test(up)) return 'Sweep from linked account';
  if (/^SWEEP\s+(?:TRF\s+)?TO/.test(up)) return 'Sweep to linked account';
  if (/^\d{9,}:\s*INT\.?\s*PD/.test(up)) return 'Savings interest';
  if (/^\d{9,}:\s*INT\.?\s*COLL/.test(up)) return 'Overdraft interest';
  if (/^FUEL\s+SURCHARGE/.test(up)) return 'Fuel surcharge';
  if (/^PERSONAL\s+LOAN\b.*\bEMI/.test(up)) return 'Personal loan EMI';
  const parts = up.split('/').map((p) => p.trim());
  const p0 = parts[0];
  if (/^(SAL|ICSP|SALARY)\b/.test(up) || /\bSALARY\b/.test(up)) return 'Salary';
  if (/^[SCI]GST\d|^[SCI]GST\b|^IGST-/.test(up)) return 'GST';
  if (up.startsWith('PRINCIPAL AMOUNT AMORTIZATION') || up.startsWith('INTEREST AMOUNT AMORTIZATION')) {
    const afterGt = after(d, '>', '').trim();
    const what = countLetters(afterGt) >= 3 ? generic(afterGt) : null;
    const interest = up.startsWith('INTEREST');
    if (what == null) return interest ? 'EMI interest' : 'EMI principal';
    return interest ? `${what} EMI interest` : `${what} EMI`;
  }
  if (/^(ANNUAL FEES?|ANNUAL FEE REVERSAL)/.test(up)) return 'Card annual fee';
  if (up.startsWith('PROCESSING FEE')) return 'Processing fee';
  const lender = p0 === 'DECS' || p0 === 'ACH' || p0 === 'NACH';
  if (lender && parts.length >= 2 && known(parts[1]) != null) return known(parts[1]);
  if (lender) {
    const o = okName(parts[1]?.replace(/\bDEBITS?\b/g, ''));
    if (o) return title(o.replace(/\s+/g, ' ').trim());
    return generic(d);
  }
  if (['VPS', 'IPS', 'MPS', 'PCD', 'POS', 'VIN'].includes(p0) && parts.length >= 2) {
    const o = okName(parts[1]);
    if (o) return title(o);
    return generic(d);
  }
  if (p0 === 'UPI' && parts.length >= 3) {
    if (/\d/.test(parts[1]) && /^OID/.test(parts[2]) && parts.length >= 4) {
      const payee = parts[3];
      const k = known(payee);
      if (k) return k;
      const o = okName(before(payee, '@').replace(/^PAYTM-/, '').replace(/[._\-\d]+/g, ' ').trim());
      if (o) return title(o);
      return 'Paytm';
    }
    for (const p of parts.slice(1)) { const k = known(p); if (k) return k; }
    const u = upiPayee(d.split('/').map((x) => x.trim()));
    if (u) return u;
    return generic(d);
  }
  if (p0 === 'MMT') {
    if (parts[1] === 'IMPS') {
      const o4 = okName(parts[4]);
      if (o4 && !/\bBANK\b/.test(o4)) return title(o4);
      const o3 = okName(parts[3]);
      if (o3) return title(o3);
    }
    return 'IMPS transfer';
  }
  if (p0 === 'BIL' && ['INFT', 'NEFT', 'IMPS', 'RTGS'].includes(parts[1] ?? '')) {
    const remark = parts[3];
    if (remark != null && cardBill.test(remark)) return 'Credit card bill';
    const who = parts[4];
    if (who === 'SELF' || remark === 'SELF') return 'Own account transfer';
    const ow = okName(who);
    if (ow && !/\bBANK\b/.test(ow) && !ifsc.test(ow.replace(/ /g, ''))) return title(ow);
    const orm = okName(remark);
    if (orm && !upiFiller.test(orm)) return title(orm);
    return parts[1] === 'INFT' ? 'Transfer' : `${parts[1]} transfer`;
  }
  if (p0 === 'BIL' && (parts.some((p) => cardBill.test(p)) || (parts.some((p) => p.includes('CREDIT')) && parts.some((p) => /^\d{15,16}$/.test(p))))) {
    return 'Credit card bill';
  }
  if (p0 === 'CLG' && parts.length >= 2) {
    const o = okName(parts[1]);
    if (o) return title(o);
    return generic(d);
  }
  if (p0 === 'BIL') {
    const fields = parts.slice(1).filter((p) => p !== 'ONL' && p !== 'PAVC' && !/^\d+$/.test(p))
      .map((p) => p.replace(/CHGRS\s*[\d.]+|GSTRS\s*[\d.]+/g, '').trim());
    for (const f of fields) {
      const o = okName(f);
      if (o && !upiFiller.test(o)) return title(o);
    }
    return generic(d);
  }
  if (p0 === 'CMS') return 'CMS collection';
  if (/^UPI-\d{9,}-/.test(up)) {
    const raw = after(after(d, '-'), '-').replace(/\s+(IN|IND|INDIA)$/i, '').trim();
    const joined = raw.length > 9 && raw[8] === ' ' && raw[7] !== ' ' && raw[9] !== ' ' ? raw.slice(0, 8) + raw.slice(9) : raw;
    if (okName(joined.toUpperCase())) return title(joined);
    return generic(d);
  }
  if (/^IRCTC\s+RFND/.test(up)) return 'IRCTC refund';
  return generic(d);
}

function isVpa(f: string): boolean {
  return f.includes('@') || (/^[a-z0-9][a-z0-9._\-]{4,}$/.test(f) && /\p{L}/u.test(f));
}

function isBankField(f: string): boolean {
  return /\bBANK\b|\bBANK\s?L|\bLTD\b|PAYMENTS?\b|PAYME$|^NSDL|^YES\s?BANK|^STATE\s+BANK|BANK$|^[A-Z]{4}0[A-Z0-9]{6}$/i.test(f);
}

function isCodeField(f: string): boolean {
  const ns = f.replace(/ /g, '');
  return full(codeToken).test(ns) || PayeeNames.isCode(ns) || /^[\d\s]{6,}$/.test(f) || /^[A-Z]{3}[0-9A-Fa-f]{10,}/.test(ns);
}

function upiPayee(fields: string[]): string | null {
  const rest = fields.slice(1).filter((f) => f.trim() !== '');
  const vpa = rest.find((f) => isVpa(f)) ?? null;
  for (const f of rest) {
    if (f === vpa || isVpa(f) || isBankField(f) || isCodeField(f)) continue;
    const cleaned = f.replace(/^(FROM|TO)\s+/i, '').replace(/\s+ORDER(\s+ID)?$/i, '').trim();
    const up = cleaned.toUpperCase();
    const fu = f.toUpperCase();
    if (upiFiller.test(up) || (upiFiller.test(fu) && !fu.startsWith('FROM ')) || gateway.test(up)) continue;
    const o = okName(cleaned);
    if (o) return title(o);
  }
  if (vpa == null) return null;
  const handle = before(vpa, '@').toLowerCase()
    .replace(/^(easebuzz|razorpay|payu|cashfree|paytm|upi)[._\-]?/, '')
    .replace(/[._\-](payu|rzp|razorpay|cf|easebuzz)$/, '')
    .replace(/[._\-\d]+/g, ' ').trim();
  const o = okName(handle);
  if (o) return title(o);
  return `UPI ${vpa.toLowerCase()}`;
}

function generic(d: string): string | null {
  const up = d.toUpperCase();
  if (/\b(ATW|ATM|NWD|CASH\s*WDL|CASH\s*WITHDRAWAL|ATD)\b/.test(up)) return 'ATM withdrawal';
  if (/\b(INT\.?\s*PD|INTEREST\s+(PAID|CREDIT|CAPITALISED)|CREDIT\s+INTEREST|SB\s*INT)\b/.test(up)) return 'Interest';
  if (/\bPAYMENT\s+RECEIVED|THANK\s*YOU\b/.test(up)) return 'Card payment received';
  const structured = (up.match(/[/\-|]/g) ?? []).length >= 2;
  if (structured) {
    const tokens = up.split(/[/\-|:]/).map((t) => t.trim()).filter((t) => t !== '');
    let vpaName: string | null = null;
    for (const raw of tokens) {
      const t = trimChars(raw, '.,*').replace(/\s+(IN|IND|INDIA)$/, '');
      if (t.includes('@')) {
        if (vpaName == null) { const b = before(t, '@').split(' '); vpaName = b[b.length - 1]; }
        continue;
      }
      if (noise.has(t) || ifsc.test(t) || (bankCode.test(t) && t.length <= 11 && bankPrefixes.has(t.slice(0, 4)))) continue;
      const letters = countLetters(t);
      if (letters < 3 || countDigits(t) > letters) continue;
      if (t.split(' ').every((w) => noise.has(w) || w.length === 1)) continue;
      if (filler.test(t) || /\bBANK\b/.test(t)) continue;
      return title(t);
    }
    if (vpaName != null) {
      const v = vpaName.replace(/[._\d]+/g, ' ').trim();
      if (v.length >= 3) return title(v);
    }
  }
  const plain = up.replace(/\b(POS|ECOM|PUR|PURCHASE|TXN|REF)\b|\bCYBS(\s+SI)?\b/g, ' ')
    .replace(/\b[0-9X*]{6,}\b/g, ' ')
    .replace(/\s+(IN|IND|INDIA)$/, '')
    .replace(/\s+\d{4,5}$/, '')
    .replace(/\s+/g, ' ').trim();
  const noCountry = plain.replace(countryEnd, '').trim();
  const nt = noCountry.replace(townEnd, '').trim();
  const noTown = countLetters(nt) >= 3 ? nt : noCountry;
  return countLetters(noTown) >= 3 ? title(noTown.slice(0, 40).trim()) : null;
}

function fallback(d: string): string {
  const cleaned = trimChars(d.replace(modePrefix, '').replace(/\b\d{6,}\b/g, ' ').replace(/[/|]+/g, ' ')
    .replace(/\s+/g, ' ').trim(), '- ');
  return title((cleaned === '' ? d : cleaned).slice(0, 40).trim());
}

export const Narration = {
  /** The merchant or person a statement line is about, read from its narration. Never empty for a narration with text. */
  merchant(description: string): string | null {
    const d = before(removePrefix(description, 'Statement: '), '\n\nSMS: ').replace(/\s+/g, ' ').trim();
    if (d === '') return null;
    const name = PayeeNames.better(named(d) ?? fallback(d), d);
    if (name == null) return null;
    const b = brandOf(name);
    if (b) return b;
    if (weak(name)) {
      const b2 = brandOf(d.replace(codeToken, ' '));
      if (b2) return b2;
    }
    return name;
  },
  brandOf,
};
