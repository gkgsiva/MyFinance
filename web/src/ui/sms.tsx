// Bank messages on iPhone: paste them, or open a .txt / PDF / CSV file of saved messages. Read with the Android
// app's SMS reader and saved with the same checks (statement lines joined, bank + wallet repeats kept once).

import { useMemo, useState } from 'preact/hooks';
import { LocalDate, formatDateMs } from '../core/dates';
import { DatedBy, SplitSms, splitSms } from '../core/smsSplit';
import { readSmsFile } from '../store/importer';
import { SmsPlan, SmsStatus, store, useStore } from '../store/store';
import { Money, go, pickFiles, toast } from './common';

interface Msg extends SplitSms { id: number }

// Kept while the app is open, so going to another tab doesn't lose what was pasted.
let msgs: Msg[] = [];
let nextId = 1;
let text = '';
let result: { added: number; linked: number; leftOut: number } | null = null;

const statusText: Record<SmsStatus, [string, string]> = {
  NEW: ['New', 'good'],
  MATCHES_STATEMENT: ['Joins a statement line', ''],
  ALREADY_HERE: ['Already here', ''],
  REPEATS_SMS: ['Same payment as another SMS', ''],
  DELETED_BEFORE: ['Deleted before', 'warn'],
  NOT_A_TRANSACTION: ['Not a payment', 'warn'],
};

const datedText: Record<DatedBy, string> = { HEADER: 'date from the file', MESSAGE: 'date from the message', NONE: 'no date found: today' };

function dayString(ts: number) { return LocalDate.fromMillis(ts).toString(); }
function withDay(ts: number, day: string): number {
  const d = LocalDate.parse(day);
  if (!d) return ts;
  const t = new Date(ts);
  return d.atTime(t.getHours(), t.getMinutes(), t.getSeconds());
}

export function SmsScreen() {
  const s = useStore();
  const [, setV] = useState(0);
  const rerender = () => setV((v) => v + 1);
  const [draft, setDraft] = useState(text);
  const [reading, setReading] = useState<string | null>(null);
  const [pdf, setPdf] = useState<{ bytes: Uint8Array; name: string; wrong: boolean } | null>(null);
  const [pw, setPw] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [saving, setSaving] = useState(false);

  const plans = useMemo(() => s.planSms(msgs.map((m) => ({ body: m.body, timestamp: m.timestamp, sender: m.sender }))), [s.version, msgs]);
  const counts = useMemo(() => {
    const c: Record<SmsStatus, number> = { NEW: 0, MATCHES_STATEMENT: 0, ALREADY_HERE: 0, REPEATS_SMS: 0, DELETED_BEFORE: 0, NOT_A_TRANSACTION: 0 };
    for (const p of plans) c[p.status]++;
    return c;
  }, [plans]);

  const setMsgs = (m: Msg[]) => { msgs = m; result = null; rerender(); };
  const addMessages = (list: SplitSms[]) => {
    const seen = new Set(msgs.map((m) => m.body.trim()));
    const fresh = list.filter((m) => !seen.has(m.body.trim())).map((m) => ({ ...m, id: nextId++ }));
    setMsgs([...msgs, ...fresh]);
    return fresh.length;
  };

  const readText = () => {
    const n = addMessages(splitSms(draft));
    text = ''; setDraft('');
    if (!n) toast('No new messages in that text.');
  };

  const paste = async () => {
    try {
      const t = await navigator.clipboard.readText();
      if (!t.trim()) { toast('Nothing copied yet.'); return; }
      const v = draft ? `${draft.trimEnd()}\n\n${t}` : t;
      text = v; setDraft(v);
    } catch {
      toast('Tap in the box and choose Paste.');
    }
  };

  const openFile = async (bytes: Uint8Array, name: string, password: string | null) => {
    setReading(name);
    try {
      const r = await readSmsFile(bytes, password);
      if (r.ok) {
        setPdf(null); setPw('');
        const n = addMessages(r.messages);
        toast(`${n} message${n === 1 ? '' : 's'} read from ${name}.`);
      } else if (r.needsPassword) setPdf({ bytes, name, wrong: r.wrong });
      else toast(r.message ?? "This file couldn't be read.");
    } finally {
      setReading(null);
    }
  };

  const chooseFile = async () => {
    const files = await pickFiles('.txt,.pdf,.csv,.htm,.html,text/plain,application/pdf,text/csv');
    for (const f of files) await openFile(new Uint8Array(await f.arrayBuffer()), f.name, null);
  };

  const doImport = async () => {
    setSaving(true);
    try {
      result = await s.addSms(plans);
      msgs = [];
    } finally {
      setSaving(false);
      rerender();
    }
  };

  if (result) {
    const r = result;
    return (
      <div class="card">
        <h2>Imported</h2>
        <p style="font-size:18px;margin:4px 0"><b>{r.added}</b> new {r.added === 1 ? 'entry' : 'entries'}{r.linked > 0 && <>, <b>{r.linked}</b> statement {r.linked === 1 ? 'line' : 'lines'} given the SMS details</>}.</p>
        {r.leftOut > 0 && <p class="small muted">{r.leftOut} card bill payments, own transfers and refunds are kept out of totals.</p>}
        <div class="row wrap" style="margin-top:10px">
          <button class="btn" onClick={() => { result = null; go('home'); }}>See this month</button>
          <button class="btn secondary" onClick={() => { result = null; rerender(); }}>Add more</button>
        </div>
      </div>
    );
  }

  const toSave = counts.NEW + counts.MATCHES_STATEMENT;
  const shown = plans.map((p, i) => [p, msgs[i]] as const).filter(([p]) => showAll || p.status === 'NEW' || p.status === 'MATCHES_STATEMENT');
  const undated = msgs.filter((m, i) => m.datedBy === 'NONE' && (plans[i]?.status === 'NEW' || plans[i]?.status === 'MATCHES_STATEMENT')).length;

  return (
    <>
      <div class="card">
        <h2>Paste bank messages</h2>
        <p class="small muted" style="margin:0 0 8px">In Messages, touch and hold a bank message, tap Copy, then paste it here. Paste as many as you like, with a blank line between them.</p>
        <textarea rows={6} value={draft} placeholder="Rs 450.00 debited from A/c XX1234 on 20-11-26 to VPA swiggy@icici…"
          onInput={(e) => { text = e.currentTarget.value; setDraft(text); }} aria-label="Bank messages" />
        <div class="row" style="margin-top:8px">
          <button class="btn secondary" onClick={paste}>Paste</button>
          <button class="btn grow" disabled={!draft.trim()} onClick={readText}>Read messages</button>
        </div>
      </div>

      <div class="drop">
        <p style="margin:0 0 6px"><b>Or open a file of messages</b></p>
        <p class="small muted" style="margin:0 0 12px">A text file, PDF or CSV holding many messages, for example a conversation saved or exported from the iPhone. Read on this phone only.</p>
        <button class="btn" disabled={!!reading} onClick={chooseFile}>{reading ? `Reading ${reading}…` : 'Choose file'}</button>
        {pdf && (
          <form style="margin-top:10px" onSubmit={(e) => { e.preventDefault(); if (pw) openFile(pdf.bytes, pdf.name, pw); }}>
            <p class="small" style="margin:4px 0">{pdf.name} is locked. Enter its password. It is not saved.</p>
            {pdf.wrong && <p class="small danger">That password didn't open it.</p>}
            <div class="row">
              <input type="password" class="grow" value={pw} onInput={(e) => setPw(e.currentTarget.value)} autoComplete="off" placeholder="PDF password" />
              <button class="btn" type="submit">Open</button>
            </div>
          </form>
        )}
      </div>

      {msgs.length > 0 && (
        <div class="card">
          <div class="row between"><h2 style="margin:0">{msgs.length} message{msgs.length === 1 ? '' : 's'}</h2>
            <button class="btn ghost" onClick={() => setMsgs([])}>Clear</button></div>
          <div class="small" style="margin-top:6px">
            <div class="row between"><span>New entries</span><b>{counts.NEW}</b></div>
            {counts.MATCHES_STATEMENT > 0 && <div class="row between"><span>Join a statement line already here</span><b>{counts.MATCHES_STATEMENT}</b></div>}
            {counts.ALREADY_HERE + counts.REPEATS_SMS > 0 && <div class="row between muted"><span>Already here (skipped)</span><span>{counts.ALREADY_HERE + counts.REPEATS_SMS}</span></div>}
            {counts.DELETED_BEFORE > 0 && <div class="row between muted"><span>Deleted before (skipped)</span><span>{counts.DELETED_BEFORE}</span></div>}
            {counts.NOT_A_TRANSACTION > 0 && <div class="row between muted"><span>Not payments: OTPs, offers, reminders (skipped)</span><span>{counts.NOT_A_TRANSACTION}</span></div>}
          </div>
          {undated > 0 && (
            <div class="notice" style="margin-top:10px">
              {undated} message{undated === 1 ? ' has' : 's have'} no date, so {undated === 1 ? 'it is' : 'they are'} dated today. Change the date below, or set them all:
              <input type="date" style="margin-top:6px" max={LocalDate.today().toString()} aria-label="Date for messages without one"
                onChange={(e) => { const v = e.currentTarget.value; if (v) setMsgs(msgs.map((m) => (m.datedBy === 'NONE' ? { ...m, timestamp: withDay(m.timestamp, v), datedBy: 'HEADER' } : m))); }} />
            </div>
          )}
          <button class="btn block" style="margin-top:10px" disabled={saving || toSave === 0} onClick={doImport}>
            {toSave === 0 ? 'Nothing new to import' : `Import ${counts.NEW} new${counts.MATCHES_STATEMENT ? ` + join ${counts.MATCHES_STATEMENT}` : ''}`}
          </button>
        </div>
      )}

      {msgs.length > 0 && (
        <>
          {plans.length !== shown.length || showAll ? (
            <label class="row small" style="margin:4px 2px"><input type="checkbox" checked={showAll} style="width:20px;height:20px" onChange={(e) => setShowAll(e.currentTarget.checked)} /> Show skipped messages too</label>
          ) : null}
          {shown.slice(0, 300).map(([p, m]) => <SmsCard key={m.id} p={p} m={m}
            onDate={(v) => setMsgs(msgs.map((x) => (x.id === m.id ? { ...x, timestamp: withDay(x.timestamp, v), datedBy: 'HEADER' } : x)))}
            onRemove={() => setMsgs(msgs.filter((x) => x.id !== m.id))} />)}
          {shown.length > 300 && <p class="small muted">And {shown.length - 300} more.</p>}
        </>
      )}

      <div class="card small muted">
        <b>Good to know</b>
        <ul style="padding-left:18px;margin:6px 0 0">
          <li>Messages are read the same way as on the Android app. A message already imported from a statement joins that line, so nothing is counted twice.</li>
          <li>The bank's message and the UPI app's message for one payment are kept once.</li>
          <li>Pasting the same messages again is fine: ones already here are skipped.</li>
        </ul>
      </div>
    </>
  );
}

function SmsCard({ p, m, onDate, onRemove }: { p: SmsPlan; m: Msg; onDate: (day: string) => void; onRemove: () => void }) {
  const [label, cls] = statusText[p.status];
  const t = p.parsed;
  const line = p.lineId != null ? store.byId.get(p.lineId) : undefined;
  const shown = line ?? p.txn;
  return (
    <div class="card">
      <div class="row between">
        <span><span class={`tag ${cls}`}>{label}</span>{t?.foreign && <span class="tag">{t.foreign.code}</span>}</span>
        <button class="btn ghost" onClick={onRemove} aria-label="Remove this message">Remove</button>
      </div>
      {t && (
        <div class="row between" style="margin:6px 0 2px">
          <b class="ellipsis grow">{shown?.merchant ?? t.merchant ?? (t.type === 'CREDIT' ? 'Money in' : 'Payment')}</b>
          <Money minor={t.amountMinor} type={t.type} signed />
        </div>
      )}
      {t && <p class="small muted" style="margin:0 0 6px">{[t.bank, t.accountTail ? `••${t.accountTail}` : null, shown?.category, shown?.excluded ? 'not counted (own transfer or card bill)' : null].filter(Boolean).join(' · ')}</p>}
      <p class="small" style="margin:0 0 8px;white-space:pre-wrap;overflow-wrap:anywhere;max-height:7.5em;overflow:hidden">{m.body}</p>
      <div class="row small">
        <input type="date" style="max-width:170px" value={dayString(m.timestamp)} max={LocalDate.today().toString()} aria-label="Date of this message"
          onChange={(e) => { if (e.currentTarget.value) onDate(e.currentTarget.value); }} />
        <span class="muted">{m.datedBy === 'NONE' ? datedText.NONE : `${formatDateMs(m.timestamp)}, ${datedText[m.datedBy]}`}</span>
      </div>
    </div>
  );
}
