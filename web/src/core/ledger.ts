// Port of MonthCycle.kt and MonthLedger.kt: which month an entry counts in, and the month as a ledger for Home.

import { LocalDate, YearMonth, formatDate, MONTH_NAMES } from './dates';
import { Cat, isInvestment } from './rules';
import { TxnType, digitsOnly } from './text';

export interface Lite {
  id: number;
  type: TxnType;
  amountMinor: number;
  timestamp: number;
  category: string;
  merchant: string | null;
  account: string | null;
}

export class MonthCycle {
  constructor(readonly startDay = 1, readonly salaryFromDay: number | null = null) {
    if (startDay < 1 || startDay > 28) this.startDay = 1;
    if (salaryFromDay != null && (salaryFromDay < 2 || salaryFromDay > 31)) this.salaryFromDay = null;
  }

  get isCalendar() {
    return this.startDay === 1 && this.salaryFromDay == null;
  }

  periodOfDate(date: LocalDate): YearMonth {
    const ym = YearMonth.from(date);
    return this.startDay > 1 && date.day >= this.startDay ? ym.plusMonths(1) : ym;
  }

  periodOf(t: { timestamp: number; type: TxnType; category: string }): YearMonth {
    let date = LocalDate.fromMillis(t.timestamp);
    if (this.shiftsSalary(date, t.type, t.category)) date = date.withDayOfMonth(1).plusMonths(1);
    return this.periodOfDate(date);
  }

  shiftsSalary(date: LocalDate, type: TxnType, category: string): boolean {
    if (this.salaryFromDay == null || type !== 'CREDIT' || category.toLowerCase() !== Cat.SALARY.toLowerCase()) return false;
    return !date.isBefore(this.salaryStart(YearMonth.from(date))!);
  }

  salaryStart(ym: YearMonth): LocalDate | null {
    const d = this.salaryFromDay;
    if (d == null) return null;
    const day = ym.atDay(Math.min(d, ym.lengthOfMonth()));
    if (day.dayOfWeek === 6) return day.minusDays(1);
    if (day.dayOfWeek === 7) return day.minusDays(2);
    return day;
  }

  start(period: YearMonth): LocalDate {
    return this.startDay === 1 ? period.atDay(1) : period.minusMonths(1).atDay(this.startDay);
  }

  end(period: YearMonth): LocalDate {
    return this.start(period.plusMonths(1)).minusDays(1);
  }

  current(today: LocalDate = LocalDate.today()): YearMonth {
    return this.periodOfDate(today);
  }

  note(period: YearMonth): string | null {
    const parts: string[] = [];
    if (this.startDay !== 1) parts.push(`${formatDate(this.start(period), false)} – ${formatDate(this.end(period), false)}`);
    const s = this.salaryStart(period.minusMonths(1));
    if (s) parts.push(`salary from ${s.day} ${MONTH_NAMES[s.month - 1].slice(0, 3)} counts here`);
    return parts.length ? parts.join(' · ') : null;
  }
}

export type RowKind = 'BANK' | 'CARD' | 'CARD_BILL';

export interface LedgerRow<T extends Lite> { txn: T; balanceMinor: number; kind: RowKind }

export interface LedgerDay<T extends Lite> {
  date: LocalDate;
  inMinor: number;
  outMinor: number;
  closingMinor: number;
  rows: LedgerRow<T>[];
  investedMinor: number;
  cardOutMinor: number;
  cardBillMinor: number;
}

export interface GroupTotal { name: string; totalMinor: number; count: number }

export interface MonthLedger<T extends Lite> {
  period: YearMonth;
  inMinor: number;
  outMinor: number;
  days: LedgerDay<T>[];
  hiddenCount: number;
  investedMinor: number;
  investments: GroupTotal[];
  cardSpentMinor: number;
  cardCreditsMinor: number;
  cardBillPaidMinor: number;
  bankMinor: number;
  leftMinor: number;
  cardDueMinor: number;
}

function tail4(s: string | null): string | null {
  if (s == null) return null;
  const d = digitsOnly(s).slice(-4);
  return d.length === 4 ? d : null;
}

export function isOnCard(t: Lite, cardTails: Set<string>): boolean {
  if (cardTails.size === 0) return false;
  const tl = tail4(t.account);
  return tl != null && cardTails.has(tl);
}

export function buildLedger<T extends Lite>(
  txns: T[], period: YearMonth, cycle: MonthCycle, hiddenIds: Set<number>, cardTails: Set<string>, cardBillPayments: T[],
): MonthLedger<T> {
  const bills = cardBillPayments.filter((t) => t.type === 'DEBIT' && !isOnCard(t, cardTails));
  const billIds = new Set(bills.map((t) => t.id));
  const inMonth = [...txns.filter((t) => t.id === 0 || !billIds.has(t.id)), ...bills]
    .filter((t) => cycle.periodOf(t).equals(period))
    .sort((a, b) => a.timestamp - b.timestamp || a.id - b.id);
  const kindOf = (t: T): RowKind => (t.id !== 0 && billIds.has(t.id) ? 'CARD_BILL' : isOnCard(t, cardTails) ? 'CARD' : 'BANK');
  let balance = 0, totalIn = 0, totalOut = 0, totalInvested = 0, cardSpent = 0, cardCredits = 0, billPaid = 0, hidden = 0;
  const rows: LedgerRow<T>[] = [];
  const dayIn = new Map<string, number>(), dayOut = new Map<string, number>(), dayCardOut = new Map<string, number>();
  const dayBill = new Map<string, number>(), dayInvested = new Map<string, number>(), dayClose = new Map<string, number>();
  const add = (m: Map<string, number>, k: string, v: number) => m.set(k, (m.get(k) ?? 0) + v);
  for (const t of inMonth) {
    const d = LocalDate.fromMillis(t.timestamp).toString();
    const kind = kindOf(t);
    const a = t.amountMinor;
    const inv = isInvestment(t.type, t.category);
    if (kind === 'CARD_BILL') { balance -= a; billPaid += a; add(dayBill, d, a); }
    else if (kind === 'CARD') {
      if (t.type === 'CREDIT') { cardCredits += a; totalIn += a; add(dayIn, d, a); }
      else {
        cardSpent += a;
        if (inv) { totalInvested += a; add(dayInvested, d, a); }
        else { totalOut += a; add(dayOut, d, a); add(dayCardOut, d, a); }
      }
    } else if (t.type === 'CREDIT') { balance += a; totalIn += a; add(dayIn, d, a); }
    else {
      balance -= a;
      if (inv) { totalInvested += a; add(dayInvested, d, a); }
      else { totalOut += a; add(dayOut, d, a); }
    }
    dayClose.set(d, balance);
    if (t.id !== 0 && hiddenIds.has(t.id)) hidden++;
    else rows.push({ txn: t, balanceMinor: balance, kind });
  }
  const byDay = new Map<string, LedgerRow<T>[]>();
  for (const r of rows) {
    const k = LocalDate.fromMillis(r.txn.timestamp).toString();
    const a = byDay.get(k); if (a) a.push(r); else byDay.set(k, [r]);
  }
  const days: LedgerDay<T>[] = [...byDay.entries()].map(([k, r]) => ({
    date: LocalDate.parse(k)!, inMinor: dayIn.get(k) ?? 0, outMinor: dayOut.get(k) ?? 0, closingMinor: dayClose.get(k) ?? 0,
    rows: [...r].reverse(), investedMinor: dayInvested.get(k) ?? 0, cardOutMinor: dayCardOut.get(k) ?? 0, cardBillMinor: dayBill.get(k) ?? 0,
  })).sort((a, b) => b.date.compareTo(a.date));
  const invGroups = new Map<string, GroupTotal>();
  for (const t of inMonth) {
    if (!isInvestment(t.type, t.category) || kindOf(t) === 'CARD_BILL') continue;
    const k = t.merchant && t.merchant.trim() !== '' ? t.merchant : t.category;
    const gt = invGroups.get(k) ?? { name: k, totalMinor: 0, count: 0 };
    gt.totalMinor += t.amountMinor; gt.count++;
    invGroups.set(k, gt);
  }
  return {
    period, inMinor: totalIn, outMinor: totalOut, days, hiddenCount: hidden, investedMinor: totalInvested,
    investments: [...invGroups.values()].sort((a, b) => b.totalMinor - a.totalMinor),
    cardSpentMinor: cardSpent, cardCreditsMinor: cardCredits, cardBillPaidMinor: billPaid, bankMinor: balance,
    leftMinor: totalIn - totalOut - totalInvested, cardDueMinor: Math.max(0, cardSpent - cardCredits),
  };
}
