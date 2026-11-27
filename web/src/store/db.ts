// A small IndexedDB wrapper: entries in one store, everything else as values in a key/value store. Nothing leaves
// the device.

const DB_NAME = 'myfinance';
const VERSION = 1;

let dbp: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  if (!dbp) {
    dbp = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('txns')) db.createObjectStore('txns', { keyPath: 'id' });
        if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbp;
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('aborted'));
  });
}

export async function getAll<T>(store: string): Promise<T[]> {
  const db = await open();
  return new Promise((resolve, reject) => {
    const req = db.transaction(store).objectStore(store).getAll();
    req.onsuccess = () => resolve(req.result as T[]);
    req.onerror = () => reject(req.error);
  });
}

export async function allKv(): Promise<Map<string, unknown>> {
  const db = await open();
  return new Promise((resolve, reject) => {
    const out = new Map<string, unknown>();
    const req = db.transaction('kv').objectStore('kv').openCursor();
    req.onsuccess = () => {
      const c = req.result;
      if (c) { out.set(String(c.key), c.value); c.continue(); } else resolve(out);
    };
    req.onerror = () => reject(req.error);
  });
}

/** Writes and deletes entries and values in one transaction, so a big change can't stop half way. */
export async function write(put: object[], del: number[], kv: Record<string, unknown> = {}): Promise<void> {
  const db = await open();
  const tx = db.transaction(['txns', 'kv'], 'readwrite');
  const ts = tx.objectStore('txns');
  for (const p of put) ts.put(p);
  for (const d of del) ts.delete(d);
  const ks = tx.objectStore('kv');
  for (const [k, v] of Object.entries(kv)) {
    if (v === undefined) ks.delete(k); else ks.put(v, k);
  }
  await done(tx);
}

export async function clearAll(): Promise<void> {
  const db = await open();
  const tx = db.transaction(['txns', 'kv'], 'readwrite');
  tx.objectStore('txns').clear();
  tx.objectStore('kv').clear();
  await done(tx);
}

/** Asks the browser to keep the data even when storage is low (Safari may otherwise clear it after weeks unused). */
export async function persist(): Promise<boolean> {
  try {
    return (await navigator.storage?.persist?.()) ?? false;
  } catch {
    return false;
  }
}
