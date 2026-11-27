import { useState } from 'preact/hooks';
import { YearMonth, formatDate, weekdayShort } from '../core/dates';
import { buildLedger, Lite } from '../core/ledger';
import { formatInr } from '../core/text';
import { Txn, useStore } from '../store/store';
import { Empty, Money, TxnRow, go } from './common';
import { openEditor } from './editor';

type L = Lite & { t: Txn };

export function toLite(t: Txn): L {
  return { id: t.id, type: t.type, amountMinor: t.amountMinor, timestamp: t.timestamp, category: t.category, merchant: t.merchant, account: t.accountTail, t };
}

export function Home() {
  const s = useStore();
  const cycle = s.cycle;
  const current = cycle.current();
  const [period, setPeriod] = useState<YearMonth | null>(null);
  const ym = period ?? current;

  if (!s.loaded) return <p class="muted">Loading…</p>;
  if (s.txns.length === 0) return <Welcome />;

  const ledger = s.memo(`ledger:${ym}`, () => buildLedger<L>(
    s.counted().map(toLite), ym, cycle, s.hiddenIds(), s.cardTails(), s.cardBillPayments().map(toLite),
  ));
  const first = s.memo('firstPeriod', () => {
    const min = s.txns.reduce((m, t) => Math.min(m, t.timestamp), Infinity);
    return cycle.periodOf({ timestamp: min, type: 'DEBIT', category: '' });
  });
  const note = cycle.note(ym);
  const hasCards = s.cardTails().size > 0;

  return (
    <>
      <div class="monthnav">
        <button aria-label="Previous month" disabled={ym.compareTo(first) <= 0} onClick={() => setPeriod(ym.minusMonths(1))}>‹</button>
        <div class="m">
          {ym.label()}
          {note && <div class="tiny">{note}</div>}
        </div>
        <button aria-label="Next month" disabled={ym.compareTo(current) >= 0} onClick={() => setPeriod(ym.plusMonths(1))}>›</button>
      </div>

      <div class="hero">
        <div class="label">Left this month</div>
        <div class="big num">{ledger.leftMinor < 0 ? '−' : ''}{formatInr(Math.abs(ledger.leftMinor))}</div>
        <div class="grid">
          <div><span>Money in</span><b class="num">{formatInr(ledger.inMinor)}</b></div>
          <div><span>Spent</span><b class="num">{formatInr(ledger.outMinor)}</b></div>
          <div><span>Invested</span><b class="num">{formatInr(ledger.investedMinor)}</b></div>
        </div>
      </div>

      {(hasCards || ledger.cardBillPaidMinor > 0) && (
        <div class="stats">
          <div class="stat"><span>Bank in − out</span><b class="num">{ledger.bankMinor < 0 ? '−' : ''}{formatInr(Math.abs(ledger.bankMinor))}</b></div>
          <button class="stat" style="border:0;text-align:left" onClick={() => go('cards')}>
            <span>Card due so far ›</span><b class="num">{formatInr(ledger.cardDueMinor)}</b>
            {ledger.cardBillPaidMinor > 0 && <span>Card bills paid {formatInr(ledger.cardBillPaidMinor)}</span>}
          </button>
        </div>
      )}

      {ledger.investments.length > 0 && (
        <div class="card">
          <h2>Invested</h2>
          {ledger.investments.slice(0, 5).map((g) => (
            <div class="row between small" key={g.name} style="padding:3px 0">
              <span class="ellipsis">{g.name}{g.count > 1 ? ` ×${g.count}` : ''}</span><Money minor={g.totalMinor} />
            </div>
          ))}
        </div>
      )}

      {ledger.days.length === 0 ? (
        <Empty icon="🗓️" title={`Nothing in ${ym.label()} yet`}>
          <p class="small">Upload this month's statement, or add an entry with +.</p>
        </Empty>
      ) : (
        <div class="card flush">
          {ledger.days.map((d) => (
            <div key={d.date.toString()}>
              <div class="dayhead">
                <span><b>{weekdayShort(d.date)}, {formatDate(d.date, false)}</b></span>
                <span class="num">
                  {d.inMinor > 0 && <span class="in">+{formatInr(d.inMinor)} </span>}
                  {d.outMinor > 0 && <span>−{formatInr(d.outMinor)}</span>}
                </span>
              </div>
              {d.rows.map((r) => (
                <TxnRow key={r.txn.id} t={r.txn.t} balance={r.kind === 'BANK' || r.kind === 'CARD_BILL' ? r.balanceMinor : undefined} onClick={() => openEditor(r.txn.t)} />
              ))}
            </div>
          ))}
        </div>
      )}
      {ledger.hiddenCount > 0 && <p class="tiny" style="text-align:center">{ledger.hiddenCount} hidden {ledger.hiddenCount === 1 ? 'entry' : 'entries'} still count in the totals.</p>}
      <p class="tiny" style="text-align:center">The running figure is money in minus money out of your bank accounts this month. Card spends count when made; the bank drops when the card bill is paid.</p>
    </>
  );
}

function Welcome() {
  return (
    <>
      <div class="hero">
        <div class="big" style="font-size:26px">Welcome to MyFinance</div>
        <div>Upload your bank and credit card statements to see where your money goes. Everything is read on this phone. Nothing is sent anywhere.</div>
      </div>
      <div class="card">
        <h2>Get started</h2>
        <ol class="small" style="padding-left:18px;margin:0 0 12px">
          <li>Download statements from net banking or the bank's email (PDF, password-locked PDF, Excel or CSV).</li>
          <li>Tap <b>Upload</b> and pick one or many files. Overlapping statements are read once.</li>
          <li>Add cash and other payments with <b>+</b>.</li>
        </ol>
        <div class="row wrap">
          <button class="btn" onClick={() => go('import')}>Upload statements</button>
          <button class="btn secondary" onClick={() => go('more/family')}>Merge from Android</button>
        </div>
      </div>
      <div class="card small muted">
        On iPhone, tap the Share button in Safari and choose <b>Add to Home Screen</b>, so MyFinance opens like an app and keeps your data.
      </div>
    </>
  );
}
