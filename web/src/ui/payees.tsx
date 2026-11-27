// Every payee in one list, to give each a proper name. A name applies to all their past entries and to every
// new one (statements, pasted SMS, cloud syncs, family files), as the Android app's payee names do.

import { useMemo, useState } from 'preact/hooks';
import { formatDateMs } from '../core/dates';
import { merchantKey } from '../core/rules';
import { PayeeNames } from '../core/statementParser';
import { Txn, store, useStore } from '../store/store';
import { Money, Sheet, TxnRow, toast } from './common';
import { openEditor } from './editor';

interface Payee {
  name: string;
  key: string;
  count: number;
  outMinor: number;
  inMinor: number;
  last: number;
  /** Raw names (keys) the user mapped to this name. */
  from: string[];
  code: boolean;
  txns: Txn[];
}

type Filter = 'all' | 'unnamed' | 'codes' | 'named';
const PAGE = 80;

function payees(txns: Txn[], map: Record<string, string>): Payee[] {
  const byName = new Map<string, Payee>();
  for (const t of txns) {
    const name = t.merchant?.trim();
    if (!name) continue;
    const key = merchantKey(name);
    if (!key) continue;
    let p = byName.get(key);
    if (!p) { p = { name, key, count: 0, outMinor: 0, inMinor: 0, last: 0, from: [], code: PayeeNames.isCode(name), txns: [] }; byName.set(key, p); }
    p.count++;
    if (t.type === 'DEBIT') p.outMinor += t.amountMinor; else p.inMinor += t.amountMinor;
    p.last = Math.max(p.last, t.timestamp);
    p.txns.push(t);
  }
  for (const [raw, n] of Object.entries(map)) {
    const p = byName.get(merchantKey(n));
    if (p && raw !== p.key) p.from.push(raw);
  }
  return [...byName.values()];
}

export function Payees() {
  const s = useStore();
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [limit, setLimit] = useState(PAGE);
  const [open, setOpen] = useState<string | null>(null);
  const all = useMemo(() => payees(s.txns, s.settings.payees), [s.version]);
  const named = (p: Payee) => p.from.length > 0 || Object.values(s.settings.payees).some((n) => merchantKey(n) === p.key);

  const list = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return all
      .filter((p) => filter === 'all' || (filter === 'codes' ? p.code : filter === 'named' ? named(p) : !named(p)))
      .filter((p) => !needle || p.name.toLowerCase().includes(needle) || p.from.some((f) => f.includes(needle)))
      .sort((a, b) => (b.outMinor + b.inMinor) - (a.outMinor + a.inMinor));
  }, [all, q, filter]);

  const counts = { all: all.length, unnamed: all.filter((p) => !named(p)).length, codes: all.filter((p) => p.code).length, named: all.filter(named).length };
  const current = open ? all.find((p) => p.key === open) : undefined;

  return (
    <>
      <p class="small muted" style="margin:4px 2px 8px">
        Give a payee a proper name, for example a UPI ID like "9876543210@ybl" as "Milk – Ravi". Every entry from them, earlier
        and from now on (statements, pasted SMS, cloud and family files), uses that name. Give two payees the same name to put them together.
      </p>
      <input type="search" placeholder="Search payees or UPI IDs" value={q} onInput={(e) => { setQ(e.currentTarget.value); setLimit(PAGE); }} style="margin:0 0 8px" />
      <div class="chips">
        {([['all', 'All'], ['codes', 'Look like codes'], ['unnamed', 'Not named'], ['named', 'Named']] as [Filter, string][]).map(([k, l]) => (
          <button key={k} class="chip" aria-pressed={filter === k} onClick={() => { setFilter(k); setLimit(PAGE); }}>{l} ({counts[k]})</button>
        ))}
      </div>
      {list.length === 0 && <p class="muted small" style="margin:16px 2px">{all.length ? 'No payees match.' : 'No payees yet.'}</p>}
      <div class="card flush"><ul class="list">
        {list.slice(0, limit).map((p) => (
          <li key={p.key}>
            <button class="item" onClick={() => setOpen(p.key)}>
              <div class="grow" style="min-width:0">
                <div class="title ellipsis">{p.name}{p.code && <span class="tag warn" style="margin-left:6px">code</span>}</div>
                <div class="sub ellipsis">
                  {p.count} {p.count === 1 ? 'entry' : 'entries'} · last {formatDateMs(p.last)}{p.from.length ? ` · from ${p.from.join(', ')}` : ''}
                </div>
              </div>
              <div style="text-align:right">
                {p.outMinor > 0 && <div><Money minor={p.outMinor} type="DEBIT" /></div>}
                {p.inMinor > 0 && <div><Money minor={p.inMinor} type="CREDIT" signed /></div>}
              </div>
            </button>
          </li>
        ))}
      </ul></div>
      {list.length > limit && <button class="btn secondary block" onClick={() => setLimit(limit + PAGE)}>Show more ({list.length - limit})</button>}
      {current && <PayeeSheet p={current} isNamed={named(current)} onClose={() => setOpen(null)} />}
    </>
  );
}

function PayeeSheet({ p, isNamed, onClose }: { p: Payee; isNamed: boolean; onClose: () => void }) {
  const [name, setName] = useState(p.name);
  const [busy, setBusy] = useState(false);
  const recent = [...p.txns].sort((a, b) => b.timestamp - a.timestamp).slice(0, 5);
  const others = store.txns.length ? [...new Set(Object.values(store.settings.payees))].filter((n) => n !== p.name).sort() : [];

  const save = async () => {
    const n = name.trim().slice(0, 60);
    if (!n || n === p.name) { onClose(); return; }
    setBusy(true);
    const changed = await store.renamePayee(p.name, n);
    setBusy(false);
    onClose();
    toast(`Named "${n}". ${changed} ${changed === 1 ? 'entry' : 'entries'} updated; new ones will use it too.`);
  };

  return (
    <Sheet onClose={onClose} title="Payee name">
      <label class="field"><span>Name for every entry from this payee</span>
        <input value={name} list="payee-names" onInput={(e) => setName(e.currentTarget.value)} autoFocus placeholder="e.g. Milk – Ravi" />
        <datalist id="payee-names">{others.map((o) => <option key={o} value={o} />)}</datalist>
      </label>
      <p class="small muted" style="margin:-4px 0 10px">
        Now shown as "{p.name}"{p.from.length ? `, and used for ${p.from.join(', ')}` : ''}. Picking a name another payee has puts them together.
      </p>
      <button class="btn block" disabled={busy || !name.trim()} onClick={save}>Use this name everywhere</button>
      {isNamed && (
        <button class="btn ghost block" style="margin-top:6px" onClick={async () => { await store.forgetPayee(p.name); onClose(); toast('New entries will keep the name the bank gives. Entries already named keep this name.'); }}>
          Stop using this name for new entries
        </button>
      )}
      <div class="section-title" style="margin-top:14px">Latest entries</div>
      <div class="card flush" style="margin-top:4px"><ul class="list">
        {recent.map((t) => <li key={t.id}><TxnRow t={t} showDate onClick={() => { onClose(); openEditor(t); }} /></li>)}
      </ul></div>
      {recent[0]?.rawText && <p class="small muted" style="white-space:pre-wrap;overflow-wrap:anywhere;margin-top:8px">{recent[0].rawText.slice(0, 300)}</p>}
    </Sheet>
  );
}
