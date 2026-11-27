import { useEffect, useState } from 'preact/hooks';
import { LocalDate, formatDateMs } from '../core/dates';
import { Cat, categorize, modeLabel, PaymentMode } from '../core/rules';
import { TxnType, decimalToMinor, minorToPlain } from '../core/text';
import { Txn, store } from '../store/store';
import { Sheet, toast } from './common';

type Open = { txn: Txn | null } | null;
let setOpen: ((o: Open) => void) | null = null;

export function openEditor(t: Txn) { setOpen?.({ txn: t }); }
export function openAdd() { setOpen?.({ txn: null }); }

export function EditorHost() {
  const [o, setO] = useState<Open>(null);
  useEffect(() => { setOpen = setO; return () => { setOpen = null; }; }, []);
  if (!o) return null;
  return <Editor txn={o.txn} onClose={() => setO(null)} key={o.txn?.id ?? 'new'} />;
}

function Editor({ txn, onClose }: { txn: Txn | null; onClose: () => void }) {
  const isNew = txn == null;
  const [type, setType] = useState<TxnType>(txn?.type ?? 'DEBIT');
  const [amount, setAmount] = useState(txn ? minorToPlain(txn.amountMinor) : '');
  const [merchant, setMerchant] = useState(txn?.merchant ?? '');
  const [category, setCategory] = useState<string>(txn?.category ?? '');
  const [newCat, setNewCat] = useState('');
  const [date, setDate] = useState((txn ? LocalDate.fromMillis(txn.timestamp) : LocalDate.today()).toString());
  const [account, setAccount] = useState(txn?.accountTail ?? '');
  const [note, setNote] = useState(txn?.note ?? '');
  const [labels, setLabels] = useState((txn?.labels ?? []).join(', '));
  const [excluded, setExcluded] = useState(txn?.excluded ?? false);
  const [hidden, setHidden] = useState(txn?.hidden ?? false);
  const [mode, setMode] = useState<string>(txn?.mode ?? (isNew ? 'CASH' : ''));
  const [err, setErr] = useState('');
  const [everyone, setEveryone] = useState(false);

  const cats = store.categories.sort((a, b) => a.localeCompare(b));
  const auto = merchant.trim() ? categorize(merchant.trim(), type, null, store.ruleMap()) : '';
  const guess = category || (auto === Cat.OTHER ? '' : auto);

  const save = async () => {
    const minor = decimalToMinor(amount.replace(/[,₹\s]/g, ''));
    if (minor == null || minor <= 0) { setErr('Enter the amount.'); return; }
    const d = LocalDate.parse(date);
    if (!d) { setErr('Pick a date.'); return; }
    const cat = (category === '__new' ? newCat.trim() : category) || guess || Cat.OTHER;
    const keepTime = txn && LocalDate.fromMillis(txn.timestamp).equals(d);
    const now = new Date();
    const ts = keepTime ? txn!.timestamp : d.equals(LocalDate.today()) ? d.atTime(now.getHours(), now.getMinutes(), now.getSeconds()) : d.atTime(12, 0, 0);
    const lab = labels.split(',').map((l) => l.trim()).filter(Boolean);
    const acct = account || null;
    const bank = acct ? store.accounts.find((a) => a.tail === acct)?.bank ?? txn?.bank ?? null : null;
    if (isNew) {
      await store.addManual({ amountMinor: minor, type, merchant: merchant.trim() || null, category: cat, timestamp: ts, note: note.trim() || null, accountTail: acct, mode: mode || null, labels: lab });
      toast('Entry added');
    } else {
      const renamed = everyone && txn!.merchant && merchant.trim() && merchant.trim() !== txn!.merchant ? await store.renamePayee(txn!.merchant, merchant.trim()) : 0;
      const learnt = await store.update({
        ...txn!, amountMinor: minor, type, merchant: merchant.trim() || null, category: cat, timestamp: ts, note: note.trim() || null,
        accountTail: acct, bank: acct === txn!.accountTail ? txn!.bank : bank, labels: lab, excluded, hidden, mode: mode || null,
      });
      if (renamed > 1) toast(`Saved. ${renamed} entries from this payee now say "${merchant.trim()}".`); else toast(learnt > 0 ? `Saved. ${learnt} other ${learnt === 1 ? 'entry' : 'entries'} from this payee moved to ${cat}.` : 'Saved');
    }
    onClose();
  };

  const remove = async () => {
    const gone = await store.remove([txn!.id]);
    onClose();
    toast('Entry deleted', { label: 'Undo', run: () => store.restore(gone.map((g) => g.uid)) });
  };

  const accts = store.accounts;
  const hasAcct = account && !accts.some((a) => a.tail === account);

  return (
    <Sheet onClose={onClose} title={isNew ? 'Add an entry' : 'Entry'}>
      <div class="seg" role="group" aria-label="Direction">
        <button aria-pressed={type === 'DEBIT'} onClick={() => setType('DEBIT')}>Money out</button>
        <button aria-pressed={type === 'CREDIT'} onClick={() => setType('CREDIT')}>Money in</button>
      </div>
      <label class="field"><span>Amount (₹)</span>
        <input inputMode="decimal" value={amount} onInput={(e) => { setAmount(e.currentTarget.value); setErr(''); }} placeholder="0.00" autoFocus={isNew} />
      </label>
      <label class="field"><span>{type === 'DEBIT' ? 'Paid to' : 'Received from'}</span>
        <input value={merchant} onInput={(e) => setMerchant(e.currentTarget.value)} placeholder="e.g. Swiggy, Rent, Salary" />
      </label>
      {!isNew && txn?.merchant && merchant.trim() && merchant.trim() !== txn.merchant && (
        <label class="row small" style="margin:-4px 0 10px"><input type="checkbox" checked={everyone} style="width:20px;height:20px" onChange={(e) => setEveryone(e.currentTarget.checked)} /> Call every payment from "{txn.merchant}" this, earlier and from now on</label>
      )}
      <label class="field"><span>Category{!category && guess ? ` (suggested: ${guess})` : ''}</span>
        <select value={category} onChange={(e) => setCategory(e.currentTarget.value)}>
          <option value="">{guess ? `${guess} (suggested)` : 'Choose…'}</option>
          {cats.map((c) => <option key={c} value={c}>{c}</option>)}
          <option value="__new">+ New category…</option>
        </select>
      </label>
      {category === '__new' && <label class="field"><span>New category name</span><input value={newCat} onInput={(e) => setNewCat(e.currentTarget.value)} /></label>}
      <div class="row">
        <label class="field grow"><span>Date</span><input type="date" value={date} onInput={(e) => setDate(e.currentTarget.value)} /></label>
        <label class="field grow"><span>Paid by</span>
          <select value={mode} onChange={(e) => setMode(e.currentTarget.value)}>
            <option value="">—</option>
            {(Object.keys(modeLabel) as PaymentMode[]).map((m) => <option key={m} value={m}>{modeLabel[m]}</option>)}
          </select>
        </label>
      </div>
      <label class="field"><span>Account</span>
        <select value={account} onChange={(e) => setAccount(e.currentTarget.value)}>
          <option value="">Cash / other</option>
          {accts.map((a) => <option key={a.tail} value={a.tail}>{store.accountName(a.tail, a.bank)}{a.owner ? ` (${a.owner})` : ''}</option>)}
          {hasAcct && <option value={account}>{store.accountName(account, txn?.bank ?? null)}</option>}
        </select>
      </label>
      <label class="field"><span>Note</span><input value={note} onInput={(e) => setNote(e.currentTarget.value)} /></label>
      <label class="field"><span>Labels (comma separated, e.g. Goa trip, Office)</span><input value={labels} onInput={(e) => setLabels(e.currentTarget.value)} /></label>
      {!isNew && (
        <>
          <label class="row" style="margin:10px 0"><input type="checkbox" checked={excluded} onChange={(e) => setExcluded(e.currentTarget.checked)} style="width:22px;height:22px" />
            <span class="grow">Don't count in totals <span class="tiny">(own transfer, card bill payment, refund pair)</span></span></label>
          <label class="row" style="margin:10px 0"><input type="checkbox" checked={hidden} onChange={(e) => setHidden(e.currentTarget.checked)} style="width:22px;height:22px" />
            <span class="grow">Hide from Home <span class="tiny">(still counts)</span></span></label>
          <p class="tiny">
            {txn!.source === 'STATEMENT' ? 'From a statement' : txn!.source === 'SMS' ? 'From an SMS (Android)' : 'Added by hand'}
            {txn!.owner ? ` · ${txn!.owner}'s` : ''} · {formatDateMs(txn!.timestamp)}
            {txn!.reference ? ` · Ref ${txn!.reference}` : ''}
          </p>
          {txn!.rawText && <details><summary>Statement text</summary><pre class="raw">{txn!.rawText}</pre></details>}
        </>
      )}
      {err && <p class="danger small">{err}</p>}
      <div class="row" style="margin-top:12px">
        {!isNew && <button class="btn secondary danger-text" onClick={remove} style="color:var(--danger)">Delete</button>}
        <button class="btn grow" onClick={save}>{isNew ? 'Add' : 'Save'}</button>
      </div>
    </Sheet>
  );
}
