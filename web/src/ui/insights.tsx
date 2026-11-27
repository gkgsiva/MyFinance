import { useState } from 'preact/hooks';
import { LocalDate, financialYearLabel, financialYearStart, YearMonth } from '../core/dates';
import { guessSalary } from '../core/rules';
import { formatInr } from '../core/text';
import { store, useStore } from '../store/store';
import { byCategory, byFinancialYear, byPayee, firstPeriod, inPeriods, monthly, totalsOf } from '../store/stats';
import { Empty, go, toast } from './common';
import { GroupedBars, RankBars } from './charts';
import { toLite } from './home';

type Range = 'month' | 'last' | 'year' | 'fy' | 'all';
const rangeLabel: Record<Range, string> = { month: 'This month', last: 'Last month', year: '12 months', fy: 'This FY', all: 'All time' };

export function Insights() {
  const s = useStore();
  const [range, setRange] = useState<Range>('month');
  const [allCats, setAllCats] = useState(false);
  if (!s.txns.length) return <Empty icon="📊" title="Insights appear after your first statement" />;

  const cur = s.cycle.current();
  const first = firstPeriod() ?? cur;
  const fyStart = YearMonth.of(financialYearStart(LocalDate.today()), 4);
  const [from, to] = range === 'month' ? [cur, cur] : range === 'last' ? [cur.minusMonths(1), cur.minusMonths(1)]
    : range === 'year' ? [cur.minusMonths(11), cur] : range === 'fy' ? [fyStart, cur] : [first, cur];
  const list = s.memo(`range:${from}:${to}`, () => inPeriods(from, to));
  const tot = totalsOf(list);
  const cats = byCategory(list);
  const payees = byPayee(list).slice(0, 8);
  const months = Math.max(1, to.index - from.index + 1);
  const budgets = s.settings.budgets;

  const trendFrom = cur.minusMonths(11).compareTo(first) < 0 ? first : cur.minusMonths(11);
  const trend = monthly(trendFrom, cur);
  const salary = s.settings.salaryFromDay == null ? s.memo('salaryGuess', () => guessSalary(s.counted().map(toLite), LocalDate.today())) : null;

  return (
    <>
      <div class="chips">
        {(Object.keys(rangeLabel) as Range[]).map((r) => <button key={r} class="chip" aria-pressed={range === r} onClick={() => setRange(r)}>{rangeLabel[r]}</button>)}
      </div>

      <div class="stats" style="margin-top:6px">
        <div class="stat"><span>Money in</span><b class="num in">{formatInr(tot.inMinor)}</b></div>
        <div class="stat"><span>Spent</span><b class="num">{formatInr(tot.spentMinor)}</b></div>
        <div class="stat"><span>Invested</span><b class="num">{formatInr(tot.investedMinor)}</b></div>
        <div class="stat"><span>Saved</span><b class="num">{tot.inMinor - tot.spentMinor < 0 ? '−' : ''}{formatInr(Math.abs(tot.inMinor - tot.spentMinor))}</b>
          {tot.inMinor > 0 && <span>{Math.round(((tot.inMinor - tot.spentMinor) / tot.inMinor) * 100)}% of money in</span>}</div>
      </div>
      {months > 1 && <p class="tiny" style="margin:6px 4px">Monthly average: spent {formatInr(Math.round(tot.spentMinor / months))}, money in {formatInr(Math.round(tot.inMinor / months))}.</p>}

      {salary && (
        <div class="card">
          <h2>Salary month</h2>
          <p class="small" style="margin:0 0 8px">Your salary{salary.payer ? ` from ${salary.payer}` : ''} usually arrives around the {salary.typicalDay}th. Count it in the month it is meant for, so each month shows its own salary?</p>
          <button class="btn" onClick={async () => { await store.setSettings({ salaryFromDay: salary.fromDay }); toast(`Salary from the ${salary.fromDay}th now counts in the next month`); }}>Yes, from the {salary.fromDay}th</button>
          <button class="btn ghost" onClick={() => go('more/profile')}>Settings</button>
        </div>
      )}

      <div class="card">
        <h2>Money in and spent, by month</h2>
        <GroupedBars
          labels={trend.map((m) => m.ym.short().split(' ')[0])}
          series={[{ name: 'Money in', color: 'var(--series-1)' }, { name: 'Spent', color: 'var(--series-2)' }]}
          values={trend.map((m) => [m.totals.inMinor, m.totals.spentMinor])}
        />
        <p class="tiny" style="margin:4px 0 0">{trend[0].ym.label()} to {trend[trend.length - 1].ym.label()}. Card bill payments and transfers between your own accounts are not counted.</p>
      </div>

      <div class="card">
        <h2>Where it went · {rangeLabel[range].toLowerCase()}</h2>
        {cats.length === 0 ? <p class="small muted">No spending.</p> : (
          <RankBars
            rows={(allCats ? cats : cats.slice(0, 8)).map((c) => ({
              name: c.name, value: c.totalMinor, sub: `${Math.round((c.totalMinor / Math.max(1, tot.spentMinor)) * 100)}%`,
              budget: budgets[c.name] ? budgets[c.name] * months : undefined,
            }))}
          />
        )}
        {cats.length > 8 && <button class="btn ghost" onClick={() => setAllCats(!allCats)}>{allCats ? 'Show fewer' : `Show all ${cats.length}`}</button>}
      </div>

      <div class="card">
        <h2>Top payees</h2>
        {payees.length === 0 ? <p class="small muted">No spending.</p> : <RankBars rows={payees.map((p) => ({ name: p.name, value: p.totalMinor, sub: `${p.count}×` }))} />}
      </div>

      <FamilyCard from={from} to={to} />

      <div class="card">
        <h2>By financial year</h2>
        <table class="t">
          <thead><tr><th>Year</th><th>Money in</th><th>Spent</th><th>Invested</th></tr></thead>
          <tbody>
            {byFinancialYear().map(({ fy, totals }) => (
              <tr key={fy}><td>{financialYearLabel(fy).replace('FY ', '')}</td><td class="num">{formatInr(totals.inMinor)}</td><td class="num">{formatInr(totals.spentMinor)}</td><td class="num">{formatInr(totals.investedMinor)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>

      <div class="row wrap">
        <button class="btn secondary" onClick={() => go('cards')}>Card bills</button>
        <button class="btn secondary" onClick={() => go('more/report')}>Monthly PDF report</button>
      </div>
    </>
  );
}

function FamilyCard({ from, to }: { from: YearMonth; to: YearMonth }) {
  const s = store;
  const people = [...new Set(s.counted().map((t) => t.owner ?? ''))];
  if (people.length < 2) return null;
  const rows = people.map((p) => ({ name: p || s.me || 'Me', value: totalsOf(inPeriods(from, to, p || null)).spentMinor })).sort((a, b) => b.value - a.value);
  return (
    <div class="card">
      <h2>Spent per person</h2>
      <RankBars rows={rows} />
    </div>
  );
}
