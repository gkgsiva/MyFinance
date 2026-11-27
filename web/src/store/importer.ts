// Opening statement files (as Android's StatementFiles and StatementImportViewModel): PDF text in the browser,
// Excel and CSV read here, then each line compared with what is stored and with the files before it.

import { ParsedStatement } from '../core/statementParser';
import { ReadResult, readStatementFile } from '../core/readFile';
import { ExistingTxn, ImportCandidate, candidates, markDuplicates, markOwnTransfers } from '../core/statementImport';
import { PdfJs } from '../core/pdfText';
import { digitsOnly } from '../core/text';
import { CardBillRec, store } from './store';

export type FileStatus = 'READING' | 'READY' | 'NEEDS_PASSWORD' | 'FAILED';

export interface StatementFile {
  id: string;
  name: string;
  bytes: Uint8Array;
  status: FileStatus;
  wrongPassword: boolean;
  message: string | null;
  statement: ParsedStatement | null;
  bank: string | null;
  accountTail: string | null;
  pagesRead: number;
  pages: number;
  candidates: ImportCandidate[];
  include: boolean;
}

let pdfjsP: Promise<PdfJs> | null = null;

/** pdf.js is large: loaded the first time a PDF is opened, with its worker. */
function loadPdfJs(): Promise<PdfJs> {
  if (!pdfjsP) {
    pdfjsP = (async () => {
      const [lib, worker] = await Promise.all([
        import('pdfjs-dist/legacy/build/pdf.mjs'),
        import('pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'),
      ]);
      lib.GlobalWorkerOptions.workerSrc = worker.default;
      return lib as unknown as PdfJs;
    })();
  }
  return pdfjsP;
}

/** Passwords that opened a file in this visit: a bank uses one password for every statement. Never stored. */
const passwords: string[] = [];

export async function readStatement(bytes: Uint8Array, password: string | null, onPage: (p: number, n: number) => void): Promise<ReadResult> {
  const r = await readStatementFile(loadPdfJs, bytes, password != null ? [password] : [null, ...passwords], onPage);
  if (r.ok && r.password != null && !passwords.includes(r.password)) passwords.push(r.password);
  if (!r.ok && r.needsPassword) return { ...r, wrong: password != null };
  return r;
}

/** The account the statement belongs to: a registered account printed on it wins. */
export function accountFor(st: ParsedStatement): { tail: string | null; bank: string | null } {
  const accts = store.accounts;
  const t = st.accountTail != null ? digitsOnly(st.accountTail).slice(-4) : null;
  if (t && accts.some((a) => a.tail === t)) {
    const a = accts.find((x) => x.tail === t)!;
    return { tail: a.tail, bank: a.bank ?? st.bank };
  }
  for (const [, tail] of st.numbersSeen) {
    const hits = accts.filter((a) => a.tail === tail);
    if (hits.length === 1) return { tail: hits[0].tail, bank: hits[0].bank ?? st.bank };
  }
  return { tail: st.accountTail, bank: st.bank };
}

/** Marks what is new in each file, compared with what is stored and the files listed before it. */
export function recompute(files: StatementFile[]): StatementFile[] {
  const seen: ExistingTxn[] = store.existingForImport();
  const marked = files.map((f) => {
    if (f.status !== 'READY' || !f.statement || !f.include) return { ...f, candidates: [] };
    const m = markDuplicates(candidates(f.statement, f.bank, f.accountTail), seen);
    for (const c of m) {
      if (c.duplicateOf == null) {
        seen.push({ amountMinor: c.row.amountMinor, type: c.row.type, timestamp: c.timestamp, accountTail: c.accountTail, reference: c.row.reference, key: c.key, id: 0, fromStatement: true, text: c.row.description });
      }
    }
    return { ...f, candidates: m };
  });
  const all = marked.flatMap((f) => f.candidates);
  const own = new Set<string>([...all.map((c) => c.accountTail).filter((t): t is string => t != null), ...store.accounts.map((a) => a.tail)]);
  const paired = markOwnTransfers(all, own);
  let at = 0;
  return marked.map((f) => {
    const out = { ...f, candidates: paired.slice(at, at + f.candidates.length) };
    at += f.candidates.length;
    return out;
  });
}

export function billsOf(files: StatementFile[]): CardBillRec[] {
  const out: CardBillRec[] = [];
  for (const f of files) {
    const st = f.statement;
    if (!st || !f.include || st.kind !== 'CARD' || st.billTotalMinor == null || st.billDueDate == null) continue;
    const tail = f.accountTail != null ? digitsOnly(f.accountTail).slice(-4) : null;
    if (!tail) continue;
    const last = st.rows.reduce((m, r) => (r.date.isAfter(m) ? r.date : m), st.rows[0]?.date ?? st.billDueDate.minusDays(20));
    out.push({ tail, bank: f.bank, due: st.billDueDate.toString(), arrived: last.toString(), totalMinor: st.billTotalMinor, minMinor: st.billMinMinor, markedPaid: false });
  }
  return out;
}

/** Saves the new lines of [ready] files (already passed through [recompute]); their accounts join My accounts. */
export async function importReady(ready: StatementFile[], names: string[], onProgress: (done: number, total: number) => void = () => {}) {
  const accts = [...store.accounts];
  for (const f of ready) {
    const tails = new Set(f.candidates.map((c) => c.accountTail).filter((t): t is string => !!t).map((t) => digitsOnly(t).slice(-4)).filter((t) => t.length === 4));
    for (const t of tails) if (!accts.some((a) => a.tail === t)) accts.push({ tail: t, name: '', kind: f.statement!.kind, bank: f.bank, owner: null });
  }
  if (accts.length !== store.accounts.length) await store.setAccounts(accts);
  return store.addImported(ready.flatMap((f) => f.candidates), names, billsOf(ready), onProgress);
}

export function readyFile(name: string, statement: ParsedStatement): StatementFile {
  const a = accountFor(statement);
  return {
    id: name, name, bytes: new Uint8Array(0), status: 'READY', wrongPassword: false, message: null, statement, bank: a.bank, accountTail: a.tail,
    pagesRead: 0, pages: 0, candidates: [], include: true,
  };
}
