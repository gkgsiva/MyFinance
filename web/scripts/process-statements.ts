// Run by .github/workflows/statements.yml: reads every statement in the S3 bucket and writes what it says as JSON
// back into the same private bucket, for the app to load. Uses the AWS CLI the GitHub runner already has, signed in
// through the workflow's OIDC role.
//
// The repository is public, so its Actions logs are too: this prints only counts and positions, never file names,
// amounts or payees, and leaves nothing behind as an artifact.
//
// Settings (environment): S3_BUCKET, STATEMENTS_PREFIX (default "statements/"), OUTPUT_PREFIX (default "processed/"),
// PDF_PASSWORDS (one per line, from a GitHub secret), FORCE=1 to read every file again.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
import { readStatementFile } from '../src/core/readFile';
import { FORMAT, IndexEntry, IndexJson, toJson } from '../src/core/statementJson';
import { statementFrom, statementTo } from '../src/core/statementParser';
import { sha1Hex } from '../src/core/hash';
import { PdfJs } from '../src/core/pdfText';

const bucket = need('S3_BUCKET');
const inPrefix = process.env.STATEMENTS_PREFIX || 'statements/';
const outPrefix = process.env.OUTPUT_PREFIX || 'processed/';
const passwords = (process.env.PDF_PASSWORDS ?? '').split(/\r?\n/).map((p) => p.trim()).filter(Boolean);
const force = process.env.FORCE === '1' || process.env.FORCE === 'true';
const EXT = /\.(pdf|csv|xls|xlsx|htm|html|txt)$/i;

function need(name: string): string {
  const v = process.env[name];
  if (!v) { console.error(`Missing setting ${name}`); process.exit(2); }
  return v;
}

function aws(args: string[]): string {
  return execFileSync('aws', args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
}

interface S3Object { Key: string; ETag: string; Size: number }

function list(): S3Object[] {
  const out = aws(['s3api', 'list-objects-v2', '--bucket', bucket, '--prefix', inPrefix, '--output', 'json']);
  const j = out.trim() ? JSON.parse(out) : {};
  return ((j.Contents ?? []) as S3Object[]).filter((o) => o.Size > 0 && EXT.test(o.Key));
}

function previousIndex(tmp: string): IndexJson | null {
  const f = path.join(tmp, 'index.json');
  try {
    aws(['s3', 'cp', `s3://${bucket}/${outPrefix}index.json`, f, '--only-show-errors']);
    const j = JSON.parse(fs.readFileSync(f, 'utf8')) as IndexJson;
    return j.format === FORMAT ? j : null;
  } catch {
    return null;
  }
}

async function main() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'stmts-'));
  try {
    const objects = list().sort((a, b) => a.Key.localeCompare(b.Key));
    const before = force ? null : previousIndex(tmp);
    const old = new Map((before?.files ?? []).map((e) => [e.key, e]));
    console.log(`${objects.length} statement files in the bucket${before ? `, ${old.size} read before` : ''}.`);
    const entries: IndexEntry[] = [];
    let read = 0, reused = 0, failed = 0, locked = 0;
    for (const [i, o] of objects.entries()) {
      const prev = old.get(o.Key);
      // Unchanged and read fine before: kept. A file that needed a password is tried again (a password may be new).
      if (prev && prev.etag === o.ETag && prev.status === 'OK') { entries.push(prev); reused++; continue; }
      const local = path.join(tmp, `in-${i}`);
      aws(['s3', 'cp', `s3://${bucket}/${o.Key}`, local, '--only-show-errors']);
      const bytes = new Uint8Array(fs.readFileSync(local));
      fs.rmSync(local);
      const r = await readStatementFile(async () => pdfjs as unknown as PdfJs, bytes, [null, ...passwords]);
      const base: IndexEntry = { key: o.Key, etag: o.ETag, path: null, status: 'FAILED', message: null, kind: null, rows: 0, from: null, to: null };
      if (r.ok) {
        const st = r.statement;
        const rel = `${outPrefix}files/${sha1Hex(o.Key).slice(0, 20)}.json`;
        const out = path.join(tmp, `out-${i}.json`);
        fs.writeFileSync(out, JSON.stringify(toJson(st)));
        aws(['s3', 'cp', out, `s3://${bucket}/${rel}`, '--content-type', 'application/json', '--cache-control', 'no-cache', '--only-show-errors']);
        fs.rmSync(out);
        entries.push({ ...base, path: rel, status: 'OK', kind: st.kind, rows: st.rows.length, from: statementFrom(st)?.toString() ?? null, to: statementTo(st)?.toString() ?? null });
        read++;
        console.log(`File ${i + 1}: ${st.kind === 'CARD' ? 'card' : 'bank'} statement, ${st.rows.length} lines${st.check ? (Math.abs(st.check.computedMinor - st.check.expectedMinor) <= 100 ? ', totals match' : ', totals differ') : ''}.`);
      } else if (r.needsPassword) {
        entries.push({ ...base, status: 'NEEDS_PASSWORD', message: 'Locked PDF: none of the saved passwords opened it. Add its password to the PDF_PASSWORDS secret.' });
        locked++;
        console.log(`File ${i + 1}: locked, no saved password opened it.`);
      } else {
        entries.push({ ...base, message: r.message });
        failed++;
        console.log(`File ${i + 1}: could not be read.`);
      }
    }
    const index: IndexJson = { format: FORMAT, generatedAt: new Date().toISOString(), files: entries };
    const idx = path.join(tmp, 'index-out.json');
    fs.writeFileSync(idx, JSON.stringify(index));
    aws(['s3', 'cp', idx, `s3://${bucket}/${outPrefix}index.json`, '--content-type', 'application/json', '--cache-control', 'no-cache', '--only-show-errors']);
    console.log(`Done: ${read} read, ${reused} unchanged, ${locked} locked, ${failed} unreadable.`);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

main().catch((e) => {
  // The AWS CLI's own message helps (access denied, no such bucket), with object names taken out; anything else
  // only by its kind, as a message could quote a file's text.
  const err = (e as { stderr?: string }).stderr;
  if (err) console.error(`AWS: ${String(err).split('\n')[0].replace(/s3:\/\/\S+/g, 's3://…').replace(/"[^"]*"/g, '"…"')}`);
  else console.error(`Failed: ${(e as Error)?.name ?? 'error'}`);
  process.exit(1);
});
