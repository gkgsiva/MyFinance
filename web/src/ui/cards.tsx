import { LocalDate, formatDate } from '../core/dates';
import { check, needsAttention, statusLabel } from '../core/cardBills';
import { SelfTransfers } from '../core/rules';
import { formatInr } from '../core/text';
import { store, useStore } from '../store/store';
import { Empty, TxnRow, go } from './common';
import { openEditor } from './editor';

export function Cards() {
  const s = useStore();
  const checks = s.memo('cardChecks', () => check(
    s.bills.map((b) => [{
      title: s.accountName(b.tail, b.bank), accountTail: b.tail, dueDate: LocalDate.parse(b.due)!, amountMinor: b.totalMinor, minDueMinor: b.minMinor,
    }, LocalDate.parse(b.arrived)!, b.markedPaid]),
    s.txns.map((t) => ({ id: t.id, type: t.type, amountMinor: t.amountMinor, timestamp: t.timestamp, account: t.accountTail, merchant: [t.merchant, t.rawText].filter(Boolean).join(' ') })),
    LocalDate.today(),
  ));
  const cards = [...s.cardTails()];
  const cur = s.cycle.current();
  const spends = cards.map((tail) => {
    const rows = s.counted().filter((t) => t.accountTail?.endsWith(tail) && s.cycle.periodOf(t).equals(cur));
    const out = rows.filter((t) => t.type === 'DEBIT').reduce((x, t) => x + t.amountMinor, 0);
    const back = rows.filter((t) => t.type === 'CREDIT').reduce((x, t) => x + t.amountMinor, 0);
    return { tail, out, back, n: rows.length };
  });
  const payments = s.txns.filter((t) => t.excluded && t.type === 'DEBIT' && SelfTransfers.isCardPayment(t.merchant, t.rawText))
    .sort((a, b) => b.timestamp - a.timestamp).slice(0, 20);

  if (!cards.length && !s.bills.length) {
    return <Empty icon="💳" title="No credit cards yet"><p class="small">Upload a credit card statement. Its bill, due date and the payments you made towards it show here.</p></Empty>;
  }

  const toggle = async (tail: string, due: string) => {
    await s.setBills(s.bills.map((b) => (b.tail === tail && b.due === due ? { ...b, markedPaid: !b.markedPaid } : b)));
  };

  return (
    <>
      <div class="section-title">Spent this month ({cur.label()})</div>
      <div class="card flush">
        <ul class="list">
          {spends.map((c) => (
            <li key={c.tail}>
              <div class="item">
                <div class="grow"><div class="title">{store.accountName(c.tail, null)}</div><div class="sub">{c.n} entries{c.back > 0 ? ` · ${formatInr(c.back)} back` : ''}</div></div>
                <b class="num">{formatInr(c.out)}</b>
              </div>
            </li>
          ))}
        </ul>
      </div>

      <div class="section-title">Bills</div>
      {checks.length === 0 ? <p class="small muted" style="margin:0 4px">Bills appear when a card statement shows its total and due date.</p> : (
        <div class="card flush">
          <ul class="list">
            {checks.map((c) => (
              <li key={`${c.bill.accountTail}${c.bill.dueDate}`}>
                <div class="item" style="align-items:flex-start">
                  <div class="grow">
                    <div class="title">{c.bill.title} <span class={`tag ${needsAttention(c) ? 'warn' : c.status === 'PAID' ? 'good' : ''}`}>{needsAttention(c) ? '⚠ ' : c.status === 'PAID' ? '✓ ' : ''}{statusLabel[c.status]}</span></div>
                    <div class="sub">Due {formatDate(c.bill.dueDate)}{c.bill.minDueMinor != null ? ` · minimum ${formatInr(c.bill.minDueMinor)}` : ''}</div>
                    <div class="sub">Paid {formatInr(c.paidByDueMinor + c.paidLateMinor)}{c.paidLateMinor > 0 ? ` (${formatInr(c.paidLateMinor)} late)` : ''}{c.gapMinor > 0 ? ` · ${formatInr(c.gapMinor)} short` : ''}{c.daysLate > 0 ? ` · ${c.daysLate} days late` : ''}</div>
                    {(c.status === 'UNPAID' || c.status === 'PARTLY_PAID' || c.status === 'NOT_DUE' || c.status === 'MARKED_PAID' || c.status === 'MIN_ONLY') && (
                      <button class="btn ghost" style="padding-left:0" onClick={() => toggle(c.bill.accountTail, c.bill.dueDate.toString())}>{c.status === 'MARKED_PAID' ? 'Undo marked paid' : 'Mark as paid'}</button>
                    )}
                  </div>
                  <b class="num">{formatInr(c.bill.amountMinor)}</b>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {payments.length > 0 && (
        <>
          <div class="section-title">Card bill payments (not counted as spending)</div>
          <div class="card flush">{payments.map((t) => <TxnRow key={t.id} t={t} showDate onClick={() => openEditor(t)} />)}</div>
        </>
      )}
      <p class="tiny" style="margin:10px 4px">Name your cards and set whose they are in <a href="#more/accounts" onClick={(e) => { e.preventDefault(); go('more/accounts'); }}>My accounts and cards</a>.</p>
    </>
  );
}
