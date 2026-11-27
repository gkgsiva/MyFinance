// Compares the web port with the Android parser on real statements. Runs only when ORACLE_DIR points at a folder
// of PDFs with the Android oracle's dumps (<file>.pdfbox.txt and <file>.oracle.json); nothing private is in the repo.
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
import { fromText } from '../src/core/statementParser';
import { candidates } from '../src/core/statementImport';
import { pdfText } from '../src/core/pdfText';

const dir = process.env.ORACLE_DIR;
const files = dir ? fs.readdirSync(dir).filter((f) => f.endsWith('.pdf') && fs.existsSync(path.join(dir, f + '.oracle.json'))) : [];

function rowsOf(text: string) {
  const st = fromText(text);
  if (!st) return null;
  const c = candidates(st);
  return {
    kind: st.kind, bank: st.bank, tail: st.accountTail, billTotal: st.billTotalMinor, billMin: st.billMinMinor,
    billDue: st.billDueDate?.toString() ?? null, check: st.check ? [st.check.expectedMinor, st.check.computedMinor] : null,
    rows: st.rows.map((r, i) => [r.date.toString(), r.amountMinor, r.type, r.description, r.reference, r.cardTail, r.balanceMinor, c[i].key, c[i].uid, c[i].merchant, c[i].accountTail]),
  };
}

function compare(name: string, got: any, want: any, strictDesc: boolean) {
  const head = ['kind', 'bank', 'tail', 'billTotal', 'billMin', 'billDue', 'check'];
  const diffs: string[] = [];
  for (const k of head) if (JSON.stringify(got?.[k] ?? null) !== JSON.stringify(want[k] ?? null)) diffs.push(`${k}: ${JSON.stringify(got?.[k])} vs ${JSON.stringify(want[k])}`);
  const gr = got?.rows ?? [];
  const wr = want.rows;
  if (gr.length !== wr.length) diffs.push(`rows: ${gr.length} vs ${wr.length}`);
  let bad = 0;
  for (let i = 0; i < Math.min(gr.length, wr.length); i++) {
    const cols = strictDesc ? [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10] : [0, 1, 2, 4, 5, 6, 7, 8, 9, 10];
    const d = cols.filter((c) => JSON.stringify(gr[i][c]) !== JSON.stringify(wr[i][c]));
    if (d.length) { if (bad < 8) diffs.push(`row ${i} cols ${d}: ${JSON.stringify(d.map((c) => gr[i][c]))} vs ${JSON.stringify(d.map((c) => wr[i][c]))}`); bad++; }
  }
  if (bad) diffs.push(`${bad} rows differ`);
  if (diffs.length) console.log(`--- ${name}\n` + diffs.join('\n'));
  return diffs.length;
}

describe.skipIf(!dir)('matches the Android parser on real statements', () => {
  for (const f of files) {
    const want = JSON.parse(fs.readFileSync(path.join(dir!, f + '.oracle.json'), 'utf8'));
    it(`${f}: same reading of the same text`, () => {
      const text = fs.readFileSync(path.join(dir!, f + '.pdfbox.txt'), 'utf8');
      expect(compare(f + ' (pdfbox text)', rowsOf(text), want, true)).toBe(0);
    });
    it(`${f}: same lines from pdf.js text`, async () => {
      const bytes = new Uint8Array(fs.readFileSync(path.join(dir!, f)));
      const text = await pdfText(pdfjs as any, bytes, null);
      fs.writeFileSync(path.join(dir!, f + '.pdfjs.txt'), text);
      expect(compare(f + ' (pdf.js text)', rowsOf(text), want, false)).toBe(0);
    }, 120_000);
  }
});
