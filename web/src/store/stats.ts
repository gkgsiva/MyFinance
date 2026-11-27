// Totals for Insights and the PDF report, from counted entries (card bill payments, own transfers and refund
// pairs are left out, as on Android).

import { YearMonth, LocalDate, financialYearStart } from '../core/dates';
import { isInvestment, isSpend, merchantKey } from '../core/rules';
import { Txn, store } from './store';

export interface Totals { inMinor: number; spentMinor: number; investedMinor: number; count: number }

const zero = (): Totals => ({ inMinor: 0, spentMinor: 0, investedMinor: 0, count: 0 });

function addTo(t: Totals, x: Txn) {
  if (x.type === 'CREDIT') t.inMinor += x.amountMinor;
  else if (isInvestment(x.type, x.category)) t.investedMinor += x.amountMinor;
  else t.spentMinor += x.amountMinor;
  t.count++;
}

export function inPeriods(from: YearMonth, to: YearMonth, owner?: string | null): Txn[] {
  const c = store.cycle;
  return store.counted().filter((t) => {
    if (owner !== undefined && (t.owner ?? '') !== (owner ?? '')) return false;
    const p = c.periodOf(t);
    return p.compareTo(from) >= 0 && p.compareTo(to) <= 0;
  });
}

export function monthly(from: YearMonth, to: YearMonth): { ym: YearMonth; totals: Totals }[] {
  return store.memo(`monthly:${from}:${to}`, () => {
    const c = store.cycle;
    const m = new Map<string, Totals>();
    for (let y = from; y.compareTo(to) <= 0; y = y.plusMonths(1)) m.set(y.toString(), zero());
    for (const t of store.counted()) {
      const k = c.periodOf(t).toString();
      const tt = m.get(k);
      if (tt) addTo(tt, t);
    }
    return [...m.entries()].map(([k, totals]) => ({ ym: YearMonth.parse(k)!, totals }));
  });
}

export function totalsOf(list: Txn[]): Totals {
  const t = zero();
  for (const x of list) addTo(t, x);
  return t;
}

export interface Group { name: string; totalMinor: number; count: number }

export function byCategory(list: Txn[]): Group[] {
  const m = new Map<string, Group>();
  for (const t of list) {
    if (!isSpend(t.type, t.category)) continue;
    const g = m.get(t.category) ?? { name: t.category, totalMinor: 0, count: 0 };
    g.totalMinor += t.amountMinor; g.count++;
    m.set(t.category, g);
  }
  return [...m.values()].sort((a, b) => b.totalMinor - a.totalMinor);
}

export function byPayee(list: Txn[], type: 'DEBIT' | 'CREDIT' = 'DEBIT'): Group[] {
  const m = new Map<string, Group>();
  for (const t of list) {
    if (t.type !== type || (type === 'DEBIT' && !isSpend(t.type, t.category))) continue;
    const name = t.merchant?.trim() || t.category;
    const k = merchantKey(name) || name;
    const g = m.get(k) ?? { name, totalMinor: 0, count: 0 };
    g.totalMinor += t.amountMinor; g.count++;
    m.set(k, g);
  }
  return [...m.values()].sort((a, b) => b.totalMinor - a.totalMinor);
}

export function byFinancialYear(): { fy: number; totals: Totals }[] {
  return store.memo('fy', () => {
    const m = new Map<number, Totals>();
    for (const t of store.counted()) {
      const fy = financialYearStart(LocalDate.fromMillis(t.timestamp));
      const tt = m.get(fy) ?? zero();
      addTo(tt, t);
      m.set(fy, tt);
    }
    return [...m.entries()].sort((a, b) => b[0] - a[0]).map(([fy, totals]) => ({ fy, totals }));
  });
}

export function firstPeriod(): YearMonth | null {
  if (!store.txns.length) return null;
  const min = store.txns.reduce((m, t) => Math.min(m, t.timestamp), Infinity);
  return store.cycle.periodOf({ timestamp: min, type: 'DEBIT', category: '' });
}
