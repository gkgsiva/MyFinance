// Port of CardBills.kt: each credit card statement against the payments made to that card.

import { LocalDate } from './dates';
import { TxnType } from './text';

export type CardBillStatus = 'PAID' | 'PAID_LATE' | 'PARTLY_PAID' | 'MIN_ONLY' | 'UNPAID' | 'MARKED_PAID' | 'NOT_DUE';
export const statusLabel: Record<CardBillStatus, string> = {
  PAID: 'Paid in full', PAID_LATE: 'Paid late', PARTLY_PAID: 'Part paid', MIN_ONLY: 'Only minimum paid',
  UNPAID: 'Not paid', MARKED_PAID: 'Marked paid', NOT_DUE: 'Not due yet',
};

export interface BillDue {
  title: string;
  accountTail: string;
  dueDate: LocalDate;
  amountMinor: number;
  minDueMinor: number | null;
}

export interface BillTxn { id: number; type: TxnType; amountMinor: number; timestamp: number; account: string | null; merchant: string | null }

export interface CardBillCheck {
  bill: BillDue;
  statementDate: LocalDate;
  paidByDueMinor: number;
  paidLateMinor: number;
  firstPayment: LocalDate | null;
  lastPayment: LocalDate | null;
  status: CardBillStatus;
  gapMinor: number;
  daysLate: number;
}

export function needsAttention(c: CardBillCheck): boolean {
  return c.status === 'PAID_LATE' || c.status === 'PARTLY_PAID' || c.status === 'MIN_ONLY' || c.status === 'UNPAID';
}

const cardPaymentWords = /credit\s*card|card\s*payment|cc\s*payment|\bcc\b|billdesk.*card|cred\b|card\s*bill/i;

export function isPaymentTo(bill: BillDue, t: BillTxn): boolean {
  const tail = bill.accountTail;
  if (t.account != null && t.account.endsWith(tail)) return t.type === 'CREDIT';
  const m = t.merchant;
  return m != null && t.type === 'DEBIT' && m.includes(tail) && cardPaymentWords.test(m);
}

/** [bills] are (bill, when it arrived, marked paid by the user). */
export function check(bills: [BillDue, LocalDate, boolean][], txns: BillTxn[], today: LocalDate): CardBillCheck[] {
  const one = new Map<string, [BillDue, LocalDate, boolean]>();
  for (const b of bills) {
    const k = `${b[0].accountTail}|${b[0].dueDate}`;
    const have = one.get(k);
    if (!have || b[0].amountMinor > have[0].amountMinor) one.set(k, b);
  }
  const byCard = new Map<string, [BillDue, LocalDate, boolean][]>();
  for (const b of one.values()) { const a = byCard.get(b[0].accountTail); if (a) a.push(b); else byCard.set(b[0].accountTail, [b]); }
  const out: CardBillCheck[] = [];
  const used = new Set<number>();
  const earlier = (a: LocalDate, b: LocalDate) => (a.isBefore(b) ? a : b);
  for (const list of byCard.values()) {
    const sorted = [...list].sort((a, b) => a[0].dueDate.compareTo(b[0].dueDate));
    sorted.forEach(([bill, arrived, marked], i) => {
      const statementDate = earlier(arrived, bill.dueDate.minusDays(10));
      const next = sorted[i + 1];
      const lateUntil = next ? earlier(next[1], next[0].dueDate.minusDays(10)) : bill.dueDate.plusDays(30);
      let onTime = 0, late = 0;
      let first: LocalDate | null = null, last: LocalDate | null = null;
      const payments = txns.filter((t) => isPaymentTo(bill, t) && !used.has(t.id))
        .map((t) => [t, LocalDate.fromMillis(t.timestamp)] as const)
        .filter(([, d]) => !d.isBefore(statementDate.minusDays(2)) && d.isBefore(lateUntil))
        .sort((a, b) => a[0].timestamp - b[0].timestamp);
      for (const [t, d] of payments) {
        if (onTime + late >= bill.amountMinor) break;
        if (t.id !== 0) used.add(t.id);
        if (!d.isAfter(bill.dueDate)) onTime += t.amountMinor; else late += t.amountMinor;
        if (first == null) first = d;
        last = d;
      }
      const paid = onTime + late;
      const tolerance = Math.max(200, Math.trunc(bill.amountMinor / 200));
      const full = paid + tolerance >= bill.amountMinor;
      const pastDue = today.isAfter(bill.dueDate);
      let status: CardBillStatus;
      if (full && late === 0) status = 'PAID';
      else if (full) status = 'PAID_LATE';
      else if (marked) status = 'MARKED_PAID';
      else if (!pastDue && paid === 0) status = 'NOT_DUE';
      else if (!pastDue) status = 'PARTLY_PAID';
      else if (paid === 0) status = 'UNPAID';
      else if (bill.minDueMinor != null && paid + tolerance >= bill.minDueMinor && paid < bill.minDueMinor * 2) status = 'MIN_ONLY';
      else status = 'PARTLY_PAID';
      const gap = full || marked ? 0 : Math.max(0, bill.amountMinor - paid);
      let daysLate = 0;
      if (status === 'PAID_LATE') daysLate = (last ?? bill.dueDate).toEpochDay() - bill.dueDate.toEpochDay();
      else if (gap > 0 && pastDue) daysLate = today.toEpochDay() - bill.dueDate.toEpochDay();
      out.push({ bill, statementDate, paidByDueMinor: onTime, paidLateMinor: late, firstPayment: first, lastPayment: last, status, gapMinor: gap, daysLate: Math.max(0, daysLate) });
    });
  }
  return out.sort((a, b) => b.bill.dueDate.compareTo(a.bill.dueDate));
}
