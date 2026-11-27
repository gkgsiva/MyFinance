import { useState } from 'preact/hooks';
import { formatInr } from '../core/text';

export function compact(minor: number): string {
  const r = Math.abs(minor) / 100;
  const s = minor < 0 ? '−' : '';
  if (r >= 1e7) return `${s}₹${(r / 1e7).toFixed(r >= 1e8 ? 0 : 1)}Cr`;
  if (r >= 1e5) return `${s}₹${(r / 1e5).toFixed(r >= 1e6 ? 0 : 1)}L`;
  if (r >= 1e3) return `${s}₹${(r / 1e3).toFixed(r >= 1e4 ? 0 : 1)}k`;
  return `${s}₹${Math.round(r)}`;
}

function niceMax(v: number): number {
  if (v <= 0) return 100;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}

/** Bar with a 4px rounded top, anchored square to the baseline. */
function bar(x: number, y: number, w: number, h: number): string {
  if (h <= 0) return '';
  const r = Math.min(4, w / 2, h);
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

export interface Series { name: string; color: string }

/** Grouped bars by month: one bar per series, tap a month for its figures. */
export function GroupedBars({ labels, series, values }: { labels: string[]; series: Series[]; values: number[][] }) {
  const [tip, setTip] = useState<number | null>(null);
  const [table, setTable] = useState(false);
  const W = 340, H = 180, L = 40, B = 22, T = 8;
  const max = niceMax(Math.max(0, ...values.flat()));
  const n = labels.length;
  const gw = (W - L) / Math.max(1, n);
  const gap = 2;
  const bw = Math.max(3, Math.min(14, (gw - 6 - gap * (series.length - 1)) / series.length));
  const y = (v: number) => T + (H - T - B) * (1 - v / max);
  const ticks = [0, max / 2, max];
  const every = n > 8 ? 2 : 1;
  return (
    <div class="chart">
      <div class="legend">
        {series.map((s) => <span key={s.name}><i style={{ background: s.color }} />{s.name}</span>)}
        <button class="btn ghost" style="margin-left:auto;padding:0;min-height:0;font-size:13px" onClick={() => setTable(!table)}>{table ? 'Chart' : 'Table'}</button>
      </div>
      {table ? (
        <table class="t">
          <thead><tr><th>Month</th>{series.map((s) => <th key={s.name}>{s.name}</th>)}</tr></thead>
          <tbody>{labels.map((l, i) => <tr key={l}><td>{l}</td>{series.map((s, j) => <td key={s.name} class="num">{formatInr(values[i][j])}</td>)}</tr>)}</tbody>
        </table>
      ) : (
        <>
          <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${series.map((s) => s.name).join(' and ')} by month`} onPointerLeave={() => setTip(null)}>
            {ticks.map((t) => (
              <g key={t}>
                <line class="gridline" x1={L} x2={W} y1={y(t)} y2={y(t)} />
                <text class="axis" x={L - 6} y={y(t) + 4} text-anchor="end">{compact(t)}</text>
              </g>
            ))}
            {labels.map((l, i) => {
              const gx = L + i * gw + (gw - (bw * series.length + gap * (series.length - 1))) / 2;
              return (
                <g key={l}>
                  {tip === i && <rect x={L + i * gw + 1} y={T} width={gw - 2} height={H - T - B} rx="4" fill="var(--surface-2)" />}
                  {series.map((s, j) => <path key={s.name} d={bar(gx + j * (bw + gap), y(values[i][j]), bw, y(0) - y(values[i][j]))} fill={s.color} />)}
                  {i % every === (n - 1) % every && <text class="axis" x={L + i * gw + gw / 2} y={H - 6} text-anchor="middle">{l}</text>}
                  <rect x={L + i * gw} y={0} width={gw} height={H} fill="transparent" onPointerEnter={() => setTip(i)} onPointerDown={() => setTip(i)} />
                </g>
              );
            })}
          </svg>
          {tip != null && (
            <div class="tip" style={{ left: `${Math.min(70, Math.max(0, ((L + tip * gw) / W) * 100 - 10))}%`, top: '30px' }}>
              <b>{labels[tip]}</b>
              {series.map((s, j) => <div key={s.name}><i style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 2, background: s.color, marginRight: 6 }} />{s.name} <b class="num">{formatInr(values[tip][j])}</b></div>)}
            </div>
          )}
        </>
      )}
    </div>
  );
}

/** Ranked horizontal bars (one series): name and amount above each bar, optional budget line. */
export function RankBars({ rows, onPick }: { rows: { name: string; value: number; sub?: string; budget?: number }[]; onPick?: (name: string) => void }) {
  const max = Math.max(1, ...rows.map((r) => Math.max(r.value, r.budget ?? 0)));
  return (
    <div>
      {rows.map((r) => (
        <button key={r.name} class="hbar" style="width:100%;border:0;background:none;padding:0;text-align:left" onClick={() => onPick?.(r.name)}>
          <span class="ellipsis small">{r.name}{r.sub && <span class="tiny"> · {r.sub}</span>}</span>
          <span class="num small">{formatInr(r.value)}{r.budget ? <span class="tiny"> / {compact(r.budget)}</span> : null}</span>
          <div class="track"><div class={`fill ${r.budget && r.value > r.budget ? 'over' : ''}`} style={{ width: `${(r.value / max) * 100}%` }} /></div>
        </button>
      ))}
    </div>
  );
}
