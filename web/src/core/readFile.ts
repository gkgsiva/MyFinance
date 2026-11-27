// Opening one statement file, as Android's StatementFiles.read: PDF text (pdf.js), or an Excel / CSV table.
// Shared by the app and the GitHub Actions job, so both read a file the same way.

import { ParsedStatement, fromTable, fromText } from './statementParser';
import { PasswordNeeded, PdfJs, pdfText } from './pdfText';
import { detect, readTable } from './spreadsheet';

export type ReadResult =
  | { ok: true; statement: ParsedStatement; password: string | null }
  | { ok: false; needsPassword: true; wrong: boolean }
  | { ok: false; needsPassword: false; message: string };

/** Tries each of [passwords] in turn on a locked PDF (null = none); a bank uses one password for every statement. */
export async function readStatementFile(
  loadPdfJs: () => Promise<PdfJs>, bytes: Uint8Array, passwords: (string | null)[], onPage: (p: number, n: number) => void = () => {},
): Promise<ReadResult> {
  try {
    let st: ParsedStatement | null;
    let used: string | null = null;
    if (detect(bytes) === 'PDF') {
      const pdfjs = await loadPdfJs();
      let text: string | null = null;
      let wrong = false;
      for (const pw of passwords.length ? passwords : [null]) {
        try {
          text = await pdfText(pdfjs, bytes, pw, onPage);
          used = pw;
          break;
        } catch (e) {
          if (e instanceof PasswordNeeded) { wrong = wrong || e.wrong; continue; }
          throw e;
        }
      }
      if (text == null) return { ok: false, needsPassword: true, wrong };
      if ((text.match(/[\p{L}\p{N}]/gu) ?? []).length < 50) {
        return { ok: false, needsPassword: false, message: 'This PDF has no readable text (it is probably a scan). Download the statement again from net banking or the bank\'s email as PDF, Excel or CSV.' };
      }
      st = fromText(text);
    } else {
      st = fromTable(await readTable(bytes));
    }
    if (!st) return { ok: false, needsPassword: false, message: 'No transactions found. Make sure this is a bank account or credit card statement with dates and amounts.' };
    return { ok: true, statement: st, password: used };
  } catch (e) {
    return { ok: false, needsPassword: false, message: `Could not read this file (${(e as Error)?.message ?? 'error'}). Try the Excel or CSV download instead.` };
  }
}
