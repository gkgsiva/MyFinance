import { useState } from 'preact/hooks';
import { formatDateMs } from '../core/dates';
import { IndexEntry } from '../core/statementJson';
import { SyncResult, sync, syncing } from '../store/cloud';
import { store, useStore } from '../store/store';
import { go, toast } from './common';

export function syncMessage(r: SyncResult): string {
  if (!r.files) return 'No new statements in the bucket.';
  return `${r.files} new ${r.files === 1 ? 'statement' : 'statements'}: ${r.added} entries added${r.updated ? `, ${r.updated} updated` : ''}.`;
}

/** Syncs when the app opens, at most every 6 hours. */
export async function autoSync() {
  const c = store.cloud;
  if (!c || !navigator.onLine || Date.now() - c.lastSync < 6 * 3600_000) return;
  try {
    const r = await sync();
    if (r.files) toast(syncMessage(r));
  } catch (e) {
    console.warn('sync', e);
  }
}

export function CloudCard() {
  const s = useStore();
  const [step, setStep] = useState<string | null>(null);
  if (!s.cloud) {
    return (
      <button class="card" style="border:0;width:100%;text-align:left;display:block" onClick={() => go('more/cloud')}>
        <b>Statements in AWS S3?</b>
        <div class="small muted">Bring in statements processed by your GitHub workflow. Set up ›</div>
      </button>
    );
  }
  return (
    <div class="card">
      <div class="row between">
        <div class="grow">
          <b>Statements from S3</b>
          <div class="tiny">{s.cloud.lastSync ? `Last synced ${formatDateMs(s.cloud.lastSync)}` : 'Not synced yet'} · {Object.keys(s.cloud.imported).length} files</div>
        </div>
        <button class="btn" disabled={!!step || syncing()} onClick={async () => {
          try { const r = await sync(setStep); toast(syncMessage(r)); } catch (e) { toast((e as Error).message); } finally { setStep(null); }
        }}>{step ? 'Syncing…' : 'Sync now'}</button>
      </div>
      {step && <p class="small muted" style="margin:6px 0 0">{step}</p>}
    </div>
  );
}

export function CloudSettings() {
  const s = useStore();
  const c = s.cloud;
  const [bucket, setBucket] = useState(c?.bucket ?? '');
  const [region, setRegion] = useState(c?.region ?? 'ap-south-1');
  const [prefix, setPrefix] = useState(c?.prefix ?? 'processed/');
  const [keyId, setKeyId] = useState(c?.accessKeyId ?? '');
  const [secret, setSecret] = useState(c?.secretAccessKey ?? '');
  const [step, setStep] = useState<string | null>(null);
  const [res, setRes] = useState<SyncResult | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const save = async () => {
    setErr(null); setRes(null);
    const same = c && c.bucket === bucket.trim() && c.prefix === prefix.trim();
    await s.setCloud({
      bucket: bucket.trim(), region: region.trim(), prefix: prefix.trim() || 'processed/', accessKeyId: keyId.trim(), secretAccessKey: secret.trim(),
      lastSync: same ? c!.lastSync : 0, imported: same ? c!.imported : {},
    });
    try { setRes(await sync(setStep)); } catch (e) { setErr((e as Error).message); } finally { setStep(null); }
  };

  return (
    <>
      <p class="small muted" style="margin:4px">Your GitHub workflow reads the statements in your bucket and saves what they say in <code>processed/</code>. This phone reads those files with a <b>read-only</b> key. The key stays on this phone and is not included in backups.</p>
      <div class="card">
        <label class="field"><span>Bucket name</span><input value={bucket} onInput={(e) => setBucket(e.currentTarget.value)} autoCapitalize="off" autoCorrect="off" spellcheck={false} /></label>
        <div class="row">
          <label class="field grow"><span>Region</span><input value={region} onInput={(e) => setRegion(e.currentTarget.value)} autoCapitalize="off" placeholder="ap-south-1" /></label>
          <label class="field grow"><span>Folder</span><input value={prefix} onInput={(e) => setPrefix(e.currentTarget.value)} autoCapitalize="off" /></label>
        </div>
        <label class="field"><span>Access key ID</span><input value={keyId} onInput={(e) => setKeyId(e.currentTarget.value)} autoCapitalize="off" autoCorrect="off" spellcheck={false} /></label>
        <label class="field"><span>Secret access key</span><input type="password" value={secret} onInput={(e) => setSecret(e.currentTarget.value)} autoComplete="off" /></label>
        <button class="btn block" disabled={!bucket.trim() || !region.trim() || !keyId.trim() || !secret.trim() || !!step} onClick={save}>{step ?? 'Save and sync'}</button>
        {err && <p class="small danger">{err}</p>}
        {res && <p class="small">{syncMessage(res)}</p>}
      </div>
      {res && res.problems.length > 0 && <Problems list={res.problems} />}
      {c && (
        <button class="btn ghost" style="color:var(--danger)" onClick={async () => {
          if (confirm('Forget the bucket and key on this phone? Entries already brought in stay.')) { await s.setCloud(null); setBucket(''); setKeyId(''); setSecret(''); }
        }}>Forget bucket and key</button>
      )}
      <p class="tiny" style="margin:8px 4px">Setup steps (AWS role for GitHub, the read-only key, and the bucket's CORS rule) are in the repository's web/README.md.</p>
    </>
  );
}

function Problems({ list }: { list: IndexEntry[] }) {
  return (
    <div class="card">
      <h2>Not read by the workflow</h2>
      {list.map((p) => (
        <div key={p.key} class="small" style="margin:6px 0">
          <b class="ellipsis" style="display:block">{p.key}</b>
          <span class="muted">{p.message}</span>
        </div>
      ))}
    </div>
  );
}
