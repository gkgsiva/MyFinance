import { useEffect, useState } from 'preact/hooks';
import { formatDate } from '../core/dates';
import { checkDifference, checkOk, kindLabel, statementFrom, statementTo } from '../core/statementParser';
import { summary } from '../core/statementImport';
import { formatInr, digitsOnly } from '../core/text';
import { StatementFile, accountFor, importReady, readStatement, recompute } from '../store/importer';
import { useStore } from '../store/store';
import { go, pickFiles } from './common';
import { CloudCard } from './cloud';

// Kept while the app is open, so leaving the screen doesn't lose files being read.
let files: StatementFile[] = [];
let result: { added: number; updated: number; leftOut: number; removed: number } | null = null;
let busy: { done: number; total: number } | null = null;
const listeners = new Set<() => void>();
function set(f: StatementFile[]) { files = f; listeners.forEach((l) => l()); }
function patch(id: string, p: Partial<StatementFile>) { set(files.map((f) => (f.id === id ? { ...f, ...p } : f))); }
function refresh() { set(recompute(files)); }

let queue = Promise.resolve();

async function open(id: string, password: string | null) {
  const f = files.find((x) => x.id === id);
  if (!f) return;
  patch(id, { status: 'READING', message: null, wrongPassword: false, pagesRead: 0, pages: 0 });
  const r = await readStatement(f.bytes, password, (p, n) => patch(id, { pagesRead: p, pages: n }));
  if (r.ok) {
    const a = accountFor(r.statement);
    patch(id, { status: 'READY', statement: r.statement, bank: a.bank, accountTail: a.tail });
    refresh();
  } else if (r.needsPassword) patch(id, { status: 'NEEDS_PASSWORD', wrongPassword: r.wrong });
  else patch(id, { status: 'FAILED', message: r.message });
}

async function add(list: File[]) {
  result = null;
  const fresh: StatementFile[] = [];
  for (const file of list) {
    const bytes = new Uint8Array(await file.arrayBuffer());
    fresh.push({
      id: `${file.name}-${file.size}-${Math.random().toString(36).slice(2)}`, name: file.name, bytes, status: 'READING', wrongPassword: false,
      message: null, statement: null, bank: null, accountTail: null, pagesRead: 0, pages: 0, candidates: [], include: true,
    });
  }
  set([...files, ...fresh]);
  for (const f of fresh) queue = queue.then(() => open(f.id, null));
  await queue;
}

export function ImportScreen() {
  const s = useStore();
  const [, setV] = useState(0);
  const [pw, setPw] = useState<Record<string, string>>({});
  useEffect(() => { const l = () => setV((v) => v + 1); listeners.add(l); return () => { listeners.delete(l); }; }, []);
  useEffect(() => { if (files.some((f) => f.status === 'READY') && !busy) refresh(); }, [s.version]);

  const ready = files.filter((f) => f.status === 'READY' && f.include);
  const all = ready.flatMap((f) => f.candidates);
  const total = summary(all);
  const reading = files.some((f) => f.status === 'READING');

  const doImport = async () => {
    busy = { done: 0, total: all.length };
    set(files);
    try {
      result = await importReady(ready, ready.map((f) => f.name), (done, n) => { busy = { done, total: n }; listeners.forEach((l) => l()); });
      files = [];
    } finally {
      busy = null;
      set(files);
    }
  };

  if (result) {
    const r = result;
    return (
      <div class="card">
        <h2>Imported</h2>
        <p style="font-size:18px;margin:4px 0"><b>{r.added}</b> new {r.added === 1 ? 'entry' : 'entries'}{r.updated > 0 && <>, <b>{r.updated}</b> updated with statement details</>}.</p>
        {r.leftOut > 0 && <p class="small muted">{r.leftOut} card bill payments, own transfers and refunds are kept out of totals.</p>}
        {r.removed > 0 && <p class="small muted">{r.removed} copies of the same payment (same transaction ID) were moved to Recently deleted.</p>}
        <div class="row wrap" style="margin-top:10px">
          <button class="btn" onClick={() => { result = null; go('home'); }}>See this month</button>
          <button class="btn secondary" onClick={() => { result = null; set([]); }}>Upload more</button>
        </div>
      </div>
    );
  }

  return (
    <>
      <button class="card" style="border:0;width:100%;text-align:left;display:block" onClick={() => go('import/sms')}>
        <div class="row between"><b>Paste bank messages (SMS)</b><span class="muted">›</span></div>
        <p class="small muted" style="margin:4px 0 0">Copy messages from the Messages app, or open a text or PDF file of them.</p>
      </button>
      <CloudCard />
      <div class="drop">
        <p style="margin:0 0 6px"><b>Upload bank or card statements</b></p>
        <p class="small muted" style="margin:0 0 12px">PDF (also password-locked), Excel or CSV. Pick many at once, any years. Files are read on this phone and never uploaded anywhere.</p>
        <button class="btn" disabled={!!busy} onClick={async () => { const l = await pickFiles('.pdf,.csv,.xls,.xlsx,.htm,.html,.txt,application/pdf,text/csv'); if (l.length) add(l); }}>Choose files</button>
      </div>

      {files.map((f) => <FileCard key={f.id} f={f} pw={pw[f.id] ?? ''} setPw={(v) => setPw({ ...pw, [f.id]: v })} />)}

      {ready.length > 0 && (
        <div class="card">
          <h2>All files</h2>
          <Summary sum={total} />
          {busy ? (
            <>
              <div class="progress"><div style={{ width: `${(busy.done / Math.max(1, busy.total)) * 100}%` }} /></div>
              <p class="small muted">Saving…</p>
            </>
          ) : (
            <button class="btn block" style="margin-top:10px" disabled={reading || total.newCount + total.updateCount === 0} onClick={doImport}>
              {total.newCount + total.updateCount === 0 ? 'Nothing new to import' : `Import ${total.newCount} new${total.updateCount ? ` + update ${total.updateCount}` : ''}`}
            </button>
          )}
        </div>
      )}

      <div class="card small muted">
        <b>Tips</b>
        <ul style="padding-left:18px;margin:6px 0 0">
          <li>Import savings account statements first, then card statements: card bill payments are then matched on both sides.</li>
          <li>Overlapping statements are fine: lines already here are skipped.</li>
          <li>Statements imported on Android can be brought over with More › Family & Android.</li>
        </ul>
        <p style="margin:8px 0 0"><button class="btn ghost" style="padding-left:0" onClick={() => go('more/imports')}>Import history ›</button></p>
      </div>
    </>
  );
}

function Summary({ sum }: { sum: ReturnType<typeof summary> }) {
  return (
    <div class="small">
      <div class="row between"><span>New entries</span><b>{sum.newCount}</b></div>
      {sum.updateCount > 0 && <div class="row between"><span>Entries here updated with statement details</span><b>{sum.updateCount}</b></div>}
      {sum.duplicateCount > 0 && <div class="row between muted"><span>Already here (skipped)</span><span>{sum.duplicateCount}</span></div>}
      <div class="row between"><span>Spent (new)</span><span class="num">{formatInr(sum.spentMinor)}</span></div>
      <div class="row between"><span>Received (new)</span><span class="num in">{formatInr(sum.receivedMinor)}</span></div>
      {sum.notCountedCount > 0 && <div class="row between muted"><span>Card bills and own transfers, not counted ({sum.notCountedCount})</span><span class="num">{formatInr(sum.notCountedMinor)}</span></div>}
    </div>
  );
}

function FileCard({ f, pw, setPw }: { f: StatementFile; pw: string; setPw: (v: string) => void }) {
  const st = f.statement;
  const remove = () => { set(files.filter((x) => x.id !== f.id)); refresh(); };
  return (
    <div class="card">
      <div class="row between">
        <b class="ellipsis grow">{f.name}</b>
        <button class="btn ghost" onClick={remove} aria-label={`Remove ${f.name}`}>Remove</button>
      </div>
      {f.status === 'READING' && (
        <>
          <p class="small muted" style="margin:4px 0">Reading{f.pages > 0 ? ` page ${f.pagesRead} of ${f.pages}` : '…'}</p>
          <div class="progress"><div style={{ width: f.pages ? `${(f.pagesRead / f.pages) * 100}%` : '5%' }} /></div>
        </>
      )}
      {f.status === 'FAILED' && <p class="small danger">{f.message}</p>}
      {f.status === 'NEEDS_PASSWORD' && (
        <form onSubmit={(e) => { e.preventDefault(); if (pw) queue = queue.then(() => open(f.id, pw)); }}>
          <p class="small" style="margin:4px 0">This PDF is locked. Enter its password (often given in the bank's email, e.g. part of your name and date of birth). It is used only to open the file and is not saved.</p>
          {f.wrongPassword && <p class="small danger">That password didn't open it.</p>}
          <div class="row">
            <input type="password" class="grow" value={pw} onInput={(e) => setPw(e.currentTarget.value)} autoComplete="off" placeholder="PDF password" />
            <button class="btn" type="submit">Open</button>
          </div>
        </form>
      )}
      {f.status === 'READY' && st && (
        <>
          <p class="small muted" style="margin:2px 0 8px">
            {kindLabel[st.kind]}{f.bank ? ` · ${f.bank}` : ''} · {st.rows.length} lines
            {statementFrom(st) && <> · {formatDate(statementFrom(st)!)} – {formatDate(statementTo(st)!)}</>}
          </p>
          {st.check && (checkOk(st.check)
            ? <span class="tag good">✓ Totals match the statement</span>
            : <div class="notice">Totals differ from the statement by {formatInr(Math.abs(checkDifference(st.check)))}. Some lines may not have been read. You can still import.</div>)}
          {st.kind === 'CARD' && st.billTotalMinor != null && (
            <p class="small" style="margin:6px 0">Bill {formatInr(st.billTotalMinor)}{st.billMinMinor != null ? `, minimum ${formatInr(st.billMinMinor)}` : ''}{st.billDueDate ? `, due ${formatDate(st.billDueDate)}` : ''}</p>
          )}
          <label class="field"><span>{st.kind === 'CARD' ? 'Card' : 'Account'} number, last 4 digits</span>
            <input inputMode="numeric" value={f.accountTail ?? ''} placeholder="e.g. 1234"
              onChange={(e) => { const v = digitsOnly(e.currentTarget.value).slice(-4); patch(f.id, { accountTail: v || null }); refresh(); }} />
          </label>
          <label class="row small" style="margin:6px 0"><input type="checkbox" checked={f.include} style="width:20px;height:20px" onChange={(e) => { patch(f.id, { include: e.currentTarget.checked }); refresh(); }} /> Include this file</label>
          {f.include && <Summary sum={summary(f.candidates)} />}
        </>
      )}
    </div>
  );
}
