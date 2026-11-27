import { useState } from 'preact/hooks';
import { LocalDate, YearMonth, formatDateMs } from '../core/dates';
import { guessSalary, reversalLabel } from '../core/rules';
import { isExport } from '../core/familyCsv';
import { decimalToMinor, digitsOnly, minorToPlain } from '../core/text';
import { Account, store, useStore } from '../store/store';
import { firstPeriod } from '../store/stats';
import { Empty, Money, TxnRow, go, pickFiles, shareFile, toast } from './common';
import { openEditor } from './editor';
import { toLite } from './home';
import { monthlyReport } from './report';

export function More() {
  const s = useStore();
  const dups = s.duplicateGroups().length;
  const backupDays = s.settings.lastBackup ? Math.floor((Date.now() - s.settings.lastBackup) / 86_400_000) : null;
  const item = (route: string, title: string, sub?: string) => (
    <li><button class="item" onClick={() => go(route)}><div class="grow"><div class="title">{title}</div>{sub && <div class="sub">{sub}</div>}</div><span class="chev">›</span></button></li>
  );
  return (
    <>
      {s.txns.length > 0 && (backupDays == null || backupDays > 30) && (
        <button class="notice" style="border:0;width:100%;text-align:left" onClick={() => go('more/backup')}>
          {backupDays == null ? 'Your data lives only in this browser. Save a backup to Files or iCloud Drive.' : `Last backup ${backupDays} days ago. Save a fresh one.`}
        </button>
      )}
      <div class="section-title">You</div>
      <div class="card flush"><ul class="list">
        {item('more/profile', 'Name and salary month', s.me ? `${s.me} · month starts on day ${s.settings.monthStartDay}${s.settings.salaryFromDay ? `, salary from ${s.settings.salaryFromDay}th counts next month` : ''}` : 'Set your name for family sharing')}
        {item('more/accounts', 'My accounts and cards', `${s.accounts.length} saved`)}
        {item('more/categories', 'Categories, budgets and rules', `${s.categories.length} categories · ${Object.keys(s.settings.rules).length} rules`)}
        {item('more/payees', 'Payees', `Give payees proper names${Object.keys(s.settings.payees).length ? ` · ${new Set(Object.values(s.settings.payees)).size} named` : ''}`)}
        {item('cards', 'Card bills')}
      </ul></div>
      <div class="section-title">Share and keep</div>
      <div class="card flush"><ul class="list">
        {item('more/cloud', 'Statements from cloud', s.cloud ? `${s.cloud.provider === 'gcp' ? 'Google Cloud' : 'AWS'} · ${s.cloud.bucket} · ${Object.keys(s.cloud.imported).length} files` : 'AWS S3 or Google Cloud, processed by your GitHub workflow')}
        {item('more/family', 'Family & Android', 'Share your entries, merge a file from Android or family')}
        {item('more/report', 'Monthly PDF report')}
        {item('more/backup', 'Backup and restore', backupDays != null ? `Last backup ${backupDays === 0 ? 'today' : `${backupDays} days ago`}` : 'Never backed up')}
      </ul></div>
      <div class="section-title">Tidy up</div>
      <div class="card flush"><ul class="list">
        {item('more/duplicates', 'Possible duplicates', dups ? `${dups} to review` : 'None found')}
        {item('more/reversals', 'Reversed and refunded', 'Payments that came back, not counted')}
        {item('more/imports', 'Import history', `${s.batches.length} imports`)}
        {item('more/trash', 'Recently deleted', `${s.trash.length} entries, kept 60 days`)}
      </ul></div>
      <div class="section-title">About</div>
      <div class="card small muted">
        MyFinance for iPhone and the web. Statements are read in this browser and stored only on this device (nothing is uploaded; there is no server).
        Add it to your Home Screen from Safari's Share menu so it opens like an app.
        <p><button class="btn ghost" style="padding-left:0;color:var(--danger)" onClick={async () => {
          if (confirm('Delete every entry and setting on this device? Save a backup first if you may need them.')) { await store.clearEverything(); toast('All data cleared'); go('home'); }
        }}>Clear all data on this device</button></p>
      </div>
    </>
  );
}

export function Profile() {
  const s = useStore();
  const [name, setName] = useState(s.settings.ownerName);
  const [start, setStart] = useState(String(s.settings.monthStartDay));
  const [sal, setSal] = useState(s.settings.salaryFromDay ? String(s.settings.salaryFromDay) : '');
  const guess = s.memo('salaryGuessAny', () => guessSalary(s.counted().map(toLite), LocalDate.today()));
  return (
    <div class="card">
      <label class="field"><span>Your name (marks your entries when the family merges files)</span><input value={name} onInput={(e) => setName(e.currentTarget.value)} placeholder="e.g. Asha" /></label>
      <label class="field"><span>Budget month starts on day</span>
        <select value={start} onChange={(e) => setStart(e.currentTarget.value)}>{Array.from({ length: 28 }, (_, i) => <option key={i} value={String(i + 1)}>{i + 1}{i === 0 ? ' (calendar month)' : ''}</option>)}</select>
      </label>
      <label class="field"><span>Salary paid near the month end counts in the next month from day</span>
        <select value={sal} onChange={(e) => setSal(e.currentTarget.value)}>
          <option value="">Off</option>
          {Array.from({ length: 30 }, (_, i) => <option key={i} value={String(i + 2)}>{i + 2}</option>)}
        </select>
      </label>
      {guess && <p class="tiny">Your salary{guess.payer ? ` from ${guess.payer}` : ''} usually arrives around the {guess.typicalDay}th (seen in {guess.months} months). Suggested: from the {guess.fromDay}th. If that day is a weekend, the Friday before counts.</p>}
      <button class="btn block" onClick={async () => {
        await s.setSettings({ ownerName: name.trim(), monthStartDay: +start || 1, salaryFromDay: sal ? +sal : null });
        toast('Saved');
      }}>Save</button>
    </div>
  );
}

export function Accounts() {
  const s = useStore();
  const [list, setList] = useState<Account[]>(s.accounts);
  const [dirty, setDirty] = useState(false);
  const upd = (i: number, p: Partial<Account>) => { setList(list.map((a, j) => (j === i ? { ...a, ...p } : a))); setDirty(true); };
  return (
    <>
      <p class="small muted" style="margin:4px">Accounts and cards from your statements appear here. Give each a name and say whose it is, so family entries are told apart. Card spends count when made; the bank drops when the bill is paid.</p>
      {list.map((a, i) => (
        <div class="card" key={i}>
          <div class="row">
            <label class="field grow"><span>Name</span><input value={a.name} placeholder={`${a.bank ?? ''} ${a.kind === 'CARD' ? 'card' : 'account'}`.trim()} onInput={(e) => upd(i, { name: e.currentTarget.value })} /></label>
            <label class="field" style="width:90px"><span>Last 4</span><input inputMode="numeric" value={a.tail} onInput={(e) => upd(i, { tail: digitsOnly(e.currentTarget.value).slice(-4) })} /></label>
          </div>
          <div class="row">
            <label class="field grow"><span>Type</span>
              <select value={a.kind} onChange={(e) => upd(i, { kind: e.currentTarget.value as Account['kind'] })}><option value="BANK">Bank account</option><option value="CARD">Credit card</option></select>
            </label>
            <label class="field grow"><span>Bank</span><input value={a.bank ?? ''} onInput={(e) => upd(i, { bank: e.currentTarget.value || null })} /></label>
          </div>
          <label class="field"><span>Whose (empty = yours)</span><input value={a.owner ?? ''} placeholder={s.me || 'Me'} onInput={(e) => upd(i, { owner: e.currentTarget.value || null })} /></label>
          <button class="btn ghost" style="color:var(--danger);padding-left:0" onClick={() => { setList(list.filter((_, j) => j !== i)); setDirty(true); }}>Remove</button>
        </div>
      ))}
      <div class="row">
        <button class="btn secondary grow" onClick={() => { setList([...list, { tail: '', name: '', kind: 'BANK', bank: null, owner: null }]); setDirty(true); }}>Add account or card</button>
        <button class="btn grow" disabled={!dirty} onClick={async () => {
          const ok = list.filter((a) => a.tail.length === 4);
          await s.setAccounts(ok);
          // Owners follow the account: entries on it take its owner (yours when empty).
          const me = s.me;
          const changed = s.txns.filter((t) => t.source !== 'MANUAL' || t.accountTail).flatMap((t) => {
            const acc = ok.find((a) => t.accountTail != null && digitsOnly(t.accountTail).endsWith(a.tail));
            if (!acc) return [];
            const owner = acc.owner?.trim() && acc.owner.trim() !== me ? acc.owner.trim() : null;
            return owner !== t.owner ? [{ ...t, owner }] : [];
          });
          if (changed.length) await s.setMany(changed);
          setDirty(false);
          toast('Saved');
        }}>Save</button>
      </div>
    </>
  );
}

export function Categories() {
  const s = useStore();
  const [newCat, setNewCat] = useState('');
  const counts = new Map<string, number>();
  for (const t of s.txns) counts.set(t.category, (counts.get(t.category) ?? 0) + 1);
  const rules = Object.entries(s.settings.rules).sort((a, b) => a[0].localeCompare(b[0]));
  const [budget, setBudget] = useState<Record<string, string>>(Object.fromEntries(Object.entries(s.settings.budgets).map(([k, v]) => [k, minorToPlain(v)])));
  return (
    <>
      <div class="section-title">Categories and monthly budgets</div>
      <div class="card flush"><ul class="list">
        {s.categories.sort().map((c) => (
          <li key={c}><div class="item">
            <div class="grow"><div class="title">{c}</div><div class="sub">{counts.get(c) ?? 0} entries</div></div>
            <input style="width:110px" inputMode="decimal" placeholder="Budget ₹" value={budget[c] ?? ''} aria-label={`Monthly budget for ${c}`}
              onInput={(e) => setBudget({ ...budget, [c]: e.currentTarget.value })}
              onBlur={async () => {
                const v = decimalToMinor((budget[c] ?? '').replace(/[,₹\s]/g, ''));
                const b = { ...s.settings.budgets };
                if (v && v > 0) b[c] = v; else delete b[c];
                await s.setSettings({ budgets: b });
              }} />
          </div></li>
        ))}
      </ul></div>
      <div class="row">
        <input class="grow" placeholder="New category" value={newCat} onInput={(e) => setNewCat(e.currentTarget.value)} />
        <button class="btn" disabled={!newCat.trim()} onClick={async () => { await s.setSettings({ categories: [...s.settings.categories, newCat.trim()] }); setNewCat(''); }}>Add</button>
      </div>
      <div class="section-title">Learnt rules ({rules.length})</div>
      <p class="small muted" style="margin:0 4px 6px">Made when you change an entry's category: later entries from that payee get the same category.</p>
      {rules.length > 0 && (
        <div class="card flush"><ul class="list">
          {rules.map(([k, c]) => (
            <li key={k}><div class="item">
              <div class="grow"><div class="title ellipsis">{k}</div><div class="sub">→ {c}</div></div>
              <button class="btn ghost" onClick={async () => { const r = { ...s.settings.rules }; delete r[k]; await s.setSettings({ rules: r }); }}>Remove</button>
            </div></li>
          ))}
        </ul></div>
      )}
    </>
  );
}

export function Family() {
  const s = useStore();
  const [res, setRes] = useState<string | null>(null);
  const merge = async () => {
    const [f] = await pickFiles('.csv,text/csv,text/comma-separated-values,text/plain', false);
    if (!f) return;
    const text = await f.text();
    if (!isExport(text)) { setRes('This is not a MyFinance export file. To read a bank statement, use Upload.'); return; }
    const r = await s.mergeCsv(text);
    setRes(`Added ${r.added} ${r.added === 1 ? 'entry' : 'entries'}${r.from.length ? ` from ${r.from.join(', ')}` : ''}. ${r.duplicates} were already here.${r.unreadable ? ` ${r.unreadable} rows could not be read.` : ''}`);
  };
  const share = async () => {
    if (!s.me) { toast('Set your name first (More › Name and salary month), so the family sees whose entries these are.'); go('more/profile'); return; }
    const who = s.me.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'family';
    const ok = await shareFile(`myfinance-${who}-${LocalDate.today()}.csv`, s.exportCsv(), 'text/csv');
    if (ok) await s.markExported();
  };
  return (
    <>
      <div class="card">
        <h2>Share your entries</h2>
        <p class="small">Makes the same file as the Android app's “Share via WhatsApp”. On the main phone, open it with MyFinance to merge. Duplicates are skipped.</p>
        <button class="btn block" onClick={share}>Share {s.txns.length} entries</button>
        {s.settings.lastExport > 0 && <p class="tiny">Last shared {formatDateMs(s.settings.lastExport)}.</p>}
      </div>
      <div class="card">
        <h2>Merge a file</h2>
        <p class="small">Pick a MyFinance export from the Android app (Manage › Share or Export) or a family member's phone. Save it to Files from WhatsApp first. Entries you deleted here stay deleted.</p>
        <button class="btn block secondary" onClick={merge}>Choose export file</button>
        {res && <p class="small" style="margin-top:10px">{res}</p>}
      </div>
    </>
  );
}

export function Report() {
  const s = useStore();
  const cur = s.cycle.current();
  const first = firstPeriod() ?? cur;
  const months: YearMonth[] = [];
  for (let m = cur; m.compareTo(first) >= 0 && months.length < 60; m = m.minusMonths(1)) months.push(m);
  const [ym, setYm] = useState(cur.toString());
  const owners = [...new Set(s.txns.map((t) => t.owner ?? ''))];
  const [who, setWho] = useState('*');
  const [busy, setBusy] = useState(false);
  return (
    <div class="card">
      <p class="small">A printable report of a month: totals, where the money went, card bills and every entry.</p>
      <label class="field"><span>Month</span><select value={ym} onChange={(e) => setYm(e.currentTarget.value)}>{months.map((m) => <option key={m.toString()} value={m.toString()}>{m.label()}</option>)}</select></label>
      {owners.length > 1 && (
        <label class="field"><span>Whose</span><select value={who} onChange={(e) => setWho(e.currentTarget.value)}>
          <option value="*">Everyone</option>
          {owners.map((o) => <option key={o} value={o}>{o || s.me || 'Me'}</option>)}
        </select></label>
      )}
      <button class="btn block" disabled={busy || !s.txns.length} onClick={async () => {
        setBusy(true);
        try {
          const p = YearMonth.parse(ym)!;
          const blob = await monthlyReport(p, who === '*' ? undefined : who || null);
          await shareFile(`MyFinance-${p.toString()}.pdf`, blob, 'application/pdf');
        } finally { setBusy(false); }
      }}>{busy ? 'Making…' : 'Make PDF'}</button>
    </div>
  );
}

export function Backup() {
  const s = useStore();
  const [msg, setMsg] = useState<string | null>(null);
  return (
    <>
      <div class="card">
        <h2>Save a backup</h2>
        <p class="small">Your data is kept only in this browser on this phone. If Safari's website data is cleared, it is gone. Save a backup to Files or iCloud Drive now and then.</p>
        <button class="btn block" onClick={async () => {
          const ok = await shareFile(`myfinance-backup-${LocalDate.today()}.json`, s.backupJson(), 'application/json');
          if (ok) { await s.setSettings({ lastBackup: Date.now() }); toast('Backup made'); }
        }}>Save backup ({s.txns.length} entries)</button>
      </div>
      <div class="card">
        <h2>Restore</h2>
        <p class="small">Replaces everything on this device with the backup.</p>
        <button class="btn block secondary" onClick={async () => {
          const [f] = await pickFiles('.json,application/json', false);
          if (!f) return;
          if (!confirm('Replace all data on this device with this backup?')) return;
          try { await s.restoreJson(await f.text()); setMsg(`Restored ${store.txns.length} entries.`); } catch (e) { setMsg((e as Error).message); }
        }}>Choose backup file</button>
        {msg && <p class="small">{msg}</p>}
      </div>
    </>
  );
}

export function Trash() {
  const s = useStore();
  if (!s.trash.length) return <Empty icon="🗑️" title="Nothing deleted recently" />;
  return (
    <>
      <div class="row between" style="margin:6px 4px">
        <span class="small muted">Kept 60 days. Imports and merges won't bring these back.</span>
        <button class="btn ghost" onClick={() => s.restore(s.trash.map((t) => t.txn.uid))}>Restore all</button>
      </div>
      <div class="card flush">
        {s.trash.slice(0, 300).map((t) => (
          <div key={t.txn.uid} class="row" style="padding-right:12px">
            <div class="grow"><TxnRow t={t.txn} showDate /></div>
            <button class="btn ghost" onClick={() => s.restore([t.txn.uid])}>Restore</button>
          </div>
        ))}
      </div>
      <button class="btn ghost" style="color:var(--danger)" onClick={() => { if (confirm('Forget these deleted entries for good?')) s.emptyTrash(); }}>Empty now</button>
    </>
  );
}

export function Imports() {
  const s = useStore();
  if (!s.batches.length) return <Empty icon="📥" title="No imports yet" />;
  return (
    <div class="card flush"><ul class="list">
      {s.batches.map((b) => (
        <li key={b.id}><div class="item" style="align-items:flex-start">
          <div class="grow">
            <div class="title">{formatDateMs(b.at)}</div>
            <div class="sub">{b.added} added{b.updated ? `, ${b.updated} updated` : ''}{b.from ? ` · ${b.from} to ${b.to}` : ''}</div>
            <div class="tiny">{b.files.join(', ')}</div>
          </div>
          <button class="btn ghost" onClick={async () => {
            if (!confirm(`Take out the ${b.added} entries this import added? You can import the files again after.`)) return;
            const n = await s.undoImport(b.id);
            toast(`${n} entries taken out`);
          }}>Undo</button>
        </div></li>
      ))}
    </ul></div>
  );
}

export function DuplicatesScreen() {
  const s = useStore();
  const groups = s.duplicateGroups();
  if (!groups.length) return <Empty icon="✅" title="No duplicates found" />;
  return (
    <>
      <div class="row between" style="margin:6px 4px">
        <span class="small muted">{groups.length} payments that may be stored twice.</span>
        <button class="btn ghost" onClick={async () => { const n = await s.removeDuplicateGroups(groups); toast(`${n} copies moved to Recently deleted`); }}>Remove all</button>
      </div>
      {groups.slice(0, 100).map((g) => (
        <div class="card flush" key={g.key}>
          {g.rows.map((r) => { const t = s.byId.get(r.id); return t ? <TxnRow key={r.id} t={t} showDate onClick={() => openEditor(t)} /> : null; })}
          <div class="row" style="padding:6px 10px">
            <span class="tiny grow">{g.sameId ? 'Same transaction ID' : 'Same amount, same day'}</span>
            <button class="btn ghost" onClick={() => s.notDuplicates(g.key)}>Not duplicates</button>
            <button class="btn ghost" onClick={() => s.removeDuplicateGroups([g])}>Keep one</button>
          </div>
        </div>
      ))}
    </>
  );
}

export function ReversalsScreen() {
  const s = useStore();
  const pairs = s.reversalPairs();
  if (!pairs.length) return <Empty icon="↩️" title="No reversals or refunds paired" />;
  return (
    <>
      <p class="small muted" style="margin:6px 4px">A payment and the money that came back for it. Both are left out of totals.</p>
      {pairs.map((p) => (
        <div class="card flush" key={p.key}>
          {p.debit && <TxnRow t={p.debit} showDate onClick={() => openEditor(p.debit!)} />}
          {p.credit && <TxnRow t={p.credit} showDate onClick={() => openEditor(p.credit!)} />}
          <div class="row" style="padding:6px 10px">
            <span class="tiny grow">{reversalLabel[p.kind]} · <Money minor={p.debit?.amountMinor ?? p.credit?.amountMinor ?? 0} /></span>
            <button class="btn ghost" onClick={() => s.undoReversal(p.key)}>Not a reversal</button>
          </div>
        </div>
      ))}
    </>
  );
}

