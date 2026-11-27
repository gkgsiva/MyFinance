// Bringing in statements processed by the GitHub Actions job: reads processed/index.json from the private bucket,
// then each statement not imported yet (or changed), and imports them like files opened on the phone.

import { IndexEntry, IndexJson, StatementJson, fromJson } from '../core/statementJson';
import { getText } from './s3';
import { importReady, readyFile, recompute } from './importer';
import { store } from './store';

export interface SyncResult { files: number; added: number; updated: number; leftOut: number; removed: number; problems: IndexEntry[]; generatedAt: string }

let running: Promise<SyncResult> | null = null;

export function sync(onStep: (text: string) => void = () => {}): Promise<SyncResult> {
  if (!running) running = doSync(onStep).finally(() => { running = null; });
  return running;
}

export function syncing(): boolean { return running != null; }

async function doSync(onStep: (text: string) => void): Promise<SyncResult> {
  const c = store.cloud;
  if (!c) throw new Error('Set up the bucket first.');
  const prefix = c.prefix.replace(/^\/+/, '').replace(/\/?$/, '/');
  onStep('Checking for new statements…');
  const index = JSON.parse(await getText(c, `${prefix}index.json`)) as IndexJson;
  const todo = index.files.filter((f) => f.status === 'OK' && f.path && c.imported[f.key] !== f.etag);
  const problems = index.files.filter((f) => f.status !== 'OK');
  let result = { added: 0, updated: 0, leftOut: 0, removed: 0 };
  if (todo.length) {
    const files = [];
    for (const [i, f] of todo.entries()) {
      onStep(`Downloading ${i + 1} of ${todo.length}…`);
      const j = JSON.parse(await getText(c, f.path!)) as StatementJson;
      files.push(readyFile(f.key.split('/').pop() ?? f.key, fromJson(j)));
    }
    // Oldest first, savings before cards (as when importing by hand), so card bill payments pair on both sides.
    files.sort((a, b) => (a.statement!.kind === b.statement!.kind ? 0 : a.statement!.kind === 'BANK' ? -1 : 1));
    onStep('Importing…');
    const ready = recompute(files);
    result = await importReady(ready, ready.map((f) => `☁ ${f.name}`), (d, n) => onStep(`Importing ${Math.round((d / Math.max(1, n)) * 100)}%…`));
  }
  const imported = { ...c.imported };
  for (const f of todo) imported[f.key] = f.etag;
  await store.setCloud({ ...(store.cloud ?? c), imported, lastSync: Date.now() });
  return { files: todo.length, ...result, problems, generatedAt: index.generatedAt };
}
