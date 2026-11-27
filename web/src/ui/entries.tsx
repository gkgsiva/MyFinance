import { useMemo, useState } from 'preact/hooks';
import { YearMonth } from '../core/dates';
import { Cat, merchantKey } from '../core/rules';
import { formatInr } from '../core/text';
import { Txn, useStore } from '../store/store';
import { Empty, Sheet, TxnRow, toast } from './common';
import { openEditor } from './editor';

const PAGE = 150;

export function Entries() {
  const s = useStore();
  const [q, setQ] = useState('');
  const [period, setPeriod] = useState<string>('');
  const [dir, setDir] = useState<'' | 'DEBIT' | 'CREDIT'>('');
  const [cat, setCat] = useState('');
  const [acct, setAcct] = useState('');
  const [owner, setOwner] = useState('');
  const [label, setLabel] = useState('');
  const [leftOut, setLeftOut] = useState(false);
  const [limit, setLimit] = useState(PAGE);
  const [bulk, setBulk] = useState(false);

  const cycle = s.cycle;
  const periods = s.memo('periods', () => {
    const set = new Map<string, YearMonth>();
    for (const t of s.txns) { const p = cycle.periodOf(t); set.set(p.toString(), p); }
    return [...set.values()].sort((a, b) => b.compareTo(a));
  });
  const owners = s.memo('owners', () => [...new Set(s.txns.map((t) => t.owner ?? ''))].sort());
  const labels = s.memo('labels', () => [...new Set(s.txns.flatMap((t) => t.labels))].sort());
  const accounts = s.memo('acctList', () => {
    const m = new Map<string, string>();
    for (const t of s.txns) { const k = t.accountTail ?? ''; if (!m.has(k)) m.set(k, s.accountName(t.accountTail, t.bank)); }
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  });

  const list = useMemo(() => {
    const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const amt = q.trim().replace(/[,₹]/g, '');
    const amtMinor = /^\d+(\.\d{1,2})?$/.test(amt) ? Math.round(parseFloat(amt) * 100) : null;
    return s.txns.filter((t) => {
      if (!leftOut && t.excluded) return false;
      if (leftOut && !t.excluded) return false;
      if (dir && t.type !== dir) return false;
      if (cat && t.category !== cat) return false;
      if (acct !== '' && (t.accountTail ?? '') !== acct.slice(1)) return false;
      if (owner !== '' && (t.owner ?? '') !== owner.slice(1)) return false;
      if (label && !t.labels.includes(label)) return false;
      if (period && cycle.periodOf(t).toString() !== period) return false;
      if (words.length) {
        if (amtMinor != null && t.amountMinor === amtMinor) return true;
        const hay = [t.merchant, t.category, t.note, t.rawText, t.owner, ...t.labels].join(' ').toLowerCase();
        if (!words.every((w) => hay.includes(w))) return false;
      }
      return true;
    }).sort((a, b) => b.timestamp - a.timestamp || b.id - a.id);
  }, [s.version, q, period, dir, cat, acct, owner, label, leftOut]);

  const out = list.filter((t) => t.type === 'DEBIT').reduce((x, t) => x + t.amountMinor, 0);
  const inn = list.filter((t) => t.type === 'CREDIT').reduce((x, t) => x + t.amountMinor, 0);
  const filtered = q || period || dir || cat || acct || owner || label || leftOut;
  const uncategorised = s.counted().filter((t) => t.category === Cat.OTHER).length;

  if (s.txns.length === 0) return <Empty icon="🧾" title="No entries yet"><p class="small">Upload a statement or add one with +.</p></Empty>;

  return (
    <>
      <input type="search" placeholder="Search payee, note, amount…" value={q} onInput={(e) => { setQ(e.currentTarget.value); setLimit(PAGE); }} style="margin:6px 0 8px" />
      <div class="chips">
        <select class="chip" value={period} onChange={(e) => setPeriod(e.currentTarget.value)} aria-label="Month" style="width:auto">
          <option value="">All months</option>
          {periods.map((p) => <option key={p.toString()} value={p.toString()}>{p.label()}</option>)}
        </select>
        <button class="chip" aria-pressed={dir === 'DEBIT'} onClick={() => setDir(dir === 'DEBIT' ? '' : 'DEBIT')}>Money out</button>
        <button class="chip" aria-pressed={dir === 'CREDIT'} onClick={() => setDir(dir === 'CREDIT' ? '' : 'CREDIT')}>Money in</button>
        <select class="chip" value={cat} onChange={(e) => setCat(e.currentTarget.value)} aria-label="Category" style="width:auto">
          <option value="">All categories</option>
          {s.categories.sort().map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <select class="chip" value={acct} onChange={(e) => setAcct(e.currentTarget.value)} aria-label="Account" style="width:auto">
          <option value="">All accounts</option>
          {accounts.map(([k, n]) => <option key={k} value={'#' + k}>{n}</option>)}
        </select>
        {owners.length > 1 && (
          <select class="chip" value={owner} onChange={(e) => setOwner(e.currentTarget.value)} aria-label="Person" style="width:auto">
            <option value="">Everyone</option>
            {owners.map((o) => <option key={o} value={'#' + o}>{o || s.me || 'Me'}</option>)}
          </select>
        )}
        {labels.length > 0 && (
          <select class="chip" value={label} onChange={(e) => setLabel(e.currentTarget.value)} aria-label="Label" style="width:auto">
            <option value="">All labels</option>
            {labels.map((l) => <option key={l} value={l}>{l}</option>)}
          </select>
        )}
        <button class="chip" aria-pressed={leftOut} onClick={() => setLeftOut(!leftOut)}>Not counted</button>
      </div>
      {uncategorised > 0 && !filtered && (
        <button class="notice" style="border:0;width:100%;text-align:left" onClick={() => setCat(Cat.OTHER)}>
          {uncategorised} entries are in “Other”. Tap to sort them into categories.
        </button>
      )}
      <div class="row between small muted" style="margin:6px 4px">
        <span>{list.length} {list.length === 1 ? 'entry' : 'entries'}</span>
        <span class="num">{out > 0 && <>−{formatInr(out)} </>}{inn > 0 && <span class="in">+{formatInr(inn)}</span>}</span>
      </div>
      {filtered && list.length > 0 && (
        <div class="row" style="margin-bottom:6px">
          <button class="btn ghost" onClick={() => setBulk(true)}>Set category for these {list.length}</button>
          <button class="btn ghost" onClick={() => { setQ(''); setPeriod(''); setDir(''); setCat(''); setAcct(''); setOwner(''); setLabel(''); setLeftOut(false); }}>Clear filters</button>
        </div>
      )}
      {list.length === 0 ? <Empty icon="🔍" title="Nothing matches" /> : (
        <div class="card flush">
          {list.slice(0, limit).map((t) => <TxnRow key={t.id} t={t} showDate onClick={() => openEditor(t)} />)}
        </div>
      )}
      {list.length > limit && <button class="btn secondary block" onClick={() => setLimit(limit + PAGE * 2)}>Show more ({list.length - limit} left)</button>}
      {bulk && <BulkCategory rows={list} onClose={() => setBulk(false)} />}
    </>
  );
}

function BulkCategory({ rows, onClose }: { rows: Txn[]; onClose: () => void }) {
  const s = useStore();
  const [cat, setCat] = useState('');
  const [newCat, setNewCat] = useState('');
  const payees = new Set(rows.map((r) => (r.merchant ? merchantKey(r.merchant) : ''))).size;
  return (
    <Sheet onClose={onClose} title={`Category for ${rows.length} entries`}>
      <p class="small muted">From {payees} {payees === 1 ? 'payee' : 'payees'}. Future entries from these payees get this category too.</p>
      <select value={cat} onChange={(e) => setCat(e.currentTarget.value)}>
        <option value="">Choose…</option>
        {s.categories.sort().map((c) => <option key={c} value={c}>{c}</option>)}
        <option value="__new">+ New category…</option>
      </select>
      {cat === '__new' && <label class="field"><span>New category name</span><input value={newCat} onInput={(e) => setNewCat(e.currentTarget.value)} /></label>}
      <button class="btn block" style="margin-top:12px" disabled={!cat || (cat === '__new' && !newCat.trim())} onClick={async () => {
        const c = cat === '__new' ? newCat.trim() : cat;
        if (!s.settings.categories.includes(c)) await s.setSettings({ categories: [...s.settings.categories, c] });
        await s.categoriseAll(rows.map((r) => r.id), c);
        toast(`${rows.length} entries moved to ${c}`);
        onClose();
      }}>Apply</button>
    </Sheet>
  );
}
