// Port of SpreadsheetReader.kt: reads the first sheet of a statement download (CSV / tab text, .xlsx, .xls and the
// HTML tables some banks save with an .xls name) into rows of cell text. No third-party library.

export type FileType = 'PDF' | 'XLSX' | 'XLS' | 'HTML' | 'TEXT';

export function detect(bytes: Uint8Array): FileType {
  const starts = (...b: number[]) => bytes.length >= b.length && b.every((v, i) => bytes[i] === v);
  if (starts(0x25, 0x50, 0x44, 0x46)) return 'PDF';
  if (starts(0x50, 0x4b, 0x03, 0x04)) return 'XLSX';
  if (starts(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) return 'XLS';
  const head = latin1(bytes.subarray(0, Math.min(bytes.length, 4096))).toLowerCase();
  if (head.includes('<table') || head.includes('<html') || head.includes('<!doctype html')) return 'HTML';
  return 'TEXT';
}

function latin1(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
  return s;
}

export function decodeText(bytes: Uint8Array): string {
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return new TextDecoder('utf-16le').decode(bytes.subarray(2));
  if (bytes[0] === 0xfe && bytes[1] === 0xff) return new TextDecoder('utf-16be').decode(bytes.subarray(2));
  const utf8 = new TextDecoder('utf-8').decode(bytes).replace(/^﻿/, '');
  return utf8.includes('�') ? latin1(bytes) : utf8;
}

export async function readTable(bytes: Uint8Array): Promise<string[][]> {
  switch (detect(bytes)) {
    case 'XLSX': return readXlsx(bytes);
    case 'XLS': return readXls(bytes);
    case 'HTML': return readHtml(decodeText(bytes));
    case 'TEXT': return readDelimited(decodeText(bytes));
    default: throw new Error('PDF is not a spreadsheet');
  }
}

// ---------------------------------------------------------------- CSV / delimited

export function readDelimited(text: string): string[][] {
  const sample = text.split(/\r\n|\n|\r/).slice(0, 60);
  let delimiter = ',';
  let best = -1;
  for (const d of [',', '\t', ';', '|']) {
    const n = sample.reduce((s, l) => s + l.split(d).length - 1, 0);
    if (n > best) { best = n; delimiter = d; }
  }
  const records: string[][] = [];
  let field = '';
  let record: string[] = [];
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else inQuotes = false;
      } else field += ch;
    } else if (ch === '"') {
      if (field.trim() === '') { field = ''; inQuotes = true; } else field += ch;
    } else if (ch === delimiter) { record.push(field.trim()); field = ''; }
    else if (ch === '\r') { /* skip */ }
    else if (ch === '\n') { record.push(field.trim()); records.push(record); record = []; field = ''; }
    else field += ch;
  }
  if (field !== '' || record.length) { record.push(field.trim()); records.push(record); }
  return records;
}

// ---------------------------------------------------------------- HTML tables

function unescape(s: string): string {
  return s.replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(parseInt(n, 10)));
}

export function readHtml(html: string): string[][] {
  const rows: string[][] = [];
  const tr = /<tr\b[^>]*>([\s\S]*?)<\/tr\s*>/gi;
  let m: RegExpExecArray | null;
  while ((m = tr.exec(html)) !== null) {
    const cells: string[] = [];
    const td = /<t[dh]\b([^>]*)>([\s\S]*?)<\/t[dh]\s*>/gi;
    let c: RegExpExecArray | null;
    while ((c = td.exec(m[1])) !== null) {
      cells.push(unescape(c[2].replace(/<br\s*\/?>/gi, ' ').replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim());
      const span = /colspan\s*=\s*["']?(\d+)/i.exec(c[1]);
      const n = span ? parseInt(span[1], 10) : 1;
      for (let k = 0; k < Math.min(20, Math.max(0, n - 1)); k++) cells.push('');
    }
    if (cells.length) rows.push(cells);
  }
  return rows;
}

// ---------------------------------------------------------------- .xlsx

async function inflateRaw(data: Uint8Array): Promise<Uint8Array> {
  const ds = new DecompressionStream('deflate-raw');
  const stream = new Blob([data]).stream().pipeThrough(ds);
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** The .xml and .rels parts of a zip, by name. */
async function unzip(bytes: Uint8Array): Promise<Map<string, string>> {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('Not a valid Excel file');
  const count = dv.getUint16(eocd + 10, true);
  let p = dv.getUint32(eocd + 16, true);
  const out = new Map<string, string>();
  const dec = new TextDecoder('utf-8');
  for (let n = 0; n < count; n++) {
    if (dv.getUint32(p, true) !== 0x02014b50) break;
    const method = dv.getUint16(p + 10, true);
    const csize = dv.getUint32(p + 20, true);
    const nameLen = dv.getUint16(p + 28, true);
    const extraLen = dv.getUint16(p + 30, true);
    const commentLen = dv.getUint16(p + 32, true);
    const local = dv.getUint32(p + 42, true);
    const name = dec.decode(bytes.subarray(p + 46, p + 46 + nameLen)).replace(/^\//, '');
    p += 46 + nameLen + extraLen + commentLen;
    if (!(name.endsWith('.xml') || name.endsWith('.rels'))) continue;
    const lNameLen = dv.getUint16(local + 26, true);
    const lExtraLen = dv.getUint16(local + 28, true);
    const start = local + 30 + lNameLen + lExtraLen;
    const raw = bytes.subarray(start, start + csize);
    const data = method === 0 ? raw : method === 8 ? await inflateRaw(raw) : null;
    if (data) out.set(name, dec.decode(data));
  }
  return out;
}

function xmlText(s: string): string {
  return unescape(s.replace(/<[^>]+>/g, ''));
}

function attr(tag: string, name: string): string {
  const m = new RegExp(`\\s${name}\\s*=\\s*"([^"]*)"`).exec(tag) ?? new RegExp(`\\s${name}\\s*=\\s*'([^']*)'`).exec(tag);
  return m ? m[1] : '';
}

/** Elements named [name] (any prefix), as [opening tag, inner text]. */
function elements(xml: string, name: string): [string, string][] {
  const re = new RegExp(`<((?:\\w+:)?${name})(\\s[^>]*)?(\\/>|>([\\s\\S]*?)<\\/\\1>)`, 'g');
  const out: [string, string][] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(xml)) !== null) out.push([`<${m[1]}${m[2] ?? ''}>`, m[4] ?? '']);
  return out;
}

function columnIndex(ref: string): number {
  let n = 0;
  for (const ch of ref) {
    if (!/[A-Za-z]/.test(ch)) break;
    n = n * 26 + (ch.toUpperCase().charCodeAt(0) - 64);
  }
  return n - 1;
}

/** "450" for 450.0, keeping real decimals; no "4.5E2". */
function cleanNumber(v: string): string {
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(v.trim())) return v;
  const d = parseFloat(v);
  let s = (Math.round(d * 1e6) / 1e6).toFixed(6);
  s = s.replace(/\.?0+$/, '');
  return s === '-0' ? '0' : s;
}

export async function readXlsx(bytes: Uint8Array): Promise<string[][]> {
  const parts = await unzip(bytes);
  const sharedXml = parts.get('xl/sharedStrings.xml');
  const shared = sharedXml ? elements(sharedXml, 'si').map(([, inner]) => elements(inner, 't').map(([, t]) => xmlText(t)).join('')) : [];
  let sheetPath: string | null = null;
  const wb = parts.get('xl/workbook.xml');
  const rels = parts.get('xl/_rels/workbook.xml.rels');
  if (wb && rels) {
    const sheet = elements(wb, 'sheet')[0];
    if (sheet) {
      const rid = attr(sheet[0], 'r:id') || (/\s\w+:id\s*=\s*"([^"]*)"/.exec(sheet[0])?.[1] ?? '');
      const rel = elements(rels, 'Relationship').find(([t]) => attr(t, 'Id') === rid);
      if (rel) {
        const target = attr(rel[0], 'Target');
        sheetPath = target.startsWith('/') ? target.slice(1) : 'xl/' + target.replace(/^\.\//, '');
      }
    }
  }
  if (!sheetPath || !parts.has(sheetPath)) sheetPath = [...parts.keys()].filter((k) => k.startsWith('xl/worksheets/sheet')).sort()[0] ?? null;
  if (!sheetPath) throw new Error('No worksheet in this Excel file');
  const doc = parts.get(sheetPath)!;
  const rows: string[][] = [];
  for (const [rowTag, rowInner] of elements(doc, 'row')) {
    const r = parseInt(attr(rowTag, 'r'), 10);
    const rIndex = Number.isFinite(r) ? r - 1 : rows.length;
    while (rows.length < rIndex) rows.push([]);
    const cells: string[] = [];
    for (const [cTag, cInner] of elements(rowInner, 'c')) {
      const ref = attr(cTag, 'r');
      const col = ref ? columnIndex(ref) : cells.length;
      while (cells.length < col) cells.push('');
      const type = attr(cTag, 't');
      const v = elements(cInner, 'v')[0]?.[1];
      let text: string;
      if (type === 's') text = v != null ? shared[parseInt(v, 10)] ?? '' : '';
      else if (type === 'inlineStr') text = elements(cInner, 't').map(([, t]) => xmlText(t)).join('');
      else if (type === 'b') text = v === '1' ? 'TRUE' : 'FALSE';
      else text = v != null ? cleanNumber(xmlText(v)) : '';
      cells.push(text.trim());
    }
    rows.push(cells);
  }
  return rows;
}

// ---------------------------------------------------------------- .xls (BIFF8 inside an OLE2 compound file)

class Cfb {
  private dv: DataView;
  private sectorSize: number;
  private miniSectorSize: number;
  private miniCutoff: number;
  private fat: Int32Array;
  private miniFat: Int32Array;
  private entries: { name: string; type: number; start: number; size: number }[];

  constructor(private data: Uint8Array) {
    this.dv = new DataView(data.buffer, data.byteOffset, data.byteLength);
    const dv = this.dv;
    this.sectorSize = 1 << dv.getUint16(0x1e, true);
    this.miniSectorSize = 1 << dv.getUint16(0x20, true);
    this.miniCutoff = dv.getInt32(0x38, true);
    const numFat = dv.getInt32(0x2c, true);
    const fatSectors: number[] = [];
    for (let i = 0; i < 109; i++) { const s = dv.getInt32(0x4c + i * 4, true); if (s >= 0) fatSectors.push(s); }
    let difat = dv.getInt32(0x44, true);
    let guard = 0;
    while (difat >= 0 && fatSectors.length < numFat && guard++ < 10_000) {
      const off = this.offset(difat);
      const per = this.sectorSize / 4 - 1;
      for (let i = 0; i < per; i++) { const s = dv.getInt32(off + i * 4, true); if (s >= 0) fatSectors.push(s); }
      difat = dv.getInt32(off + per * 4, true);
    }
    const perSector = this.sectorSize / 4;
    this.fat = new Int32Array(fatSectors.length * perSector);
    fatSectors.forEach((s, i) => {
      const off = this.offset(s);
      for (let j = 0; j < perSector; j++) if (off + j * 4 + 4 <= data.length) this.fat[i * perSector + j] = dv.getInt32(off + j * 4, true);
    });
    this.miniFat = new Int32Array(0);
    this.entries = [];
    const dir = this.chain(dv.getInt32(0x30, true));
    const ddv = new DataView(dir.buffer, dir.byteOffset, dir.byteLength);
    for (let i = 0; i < Math.floor(dir.length / 128); i++) {
      const nameLen = Math.min(64, Math.max(0, ddv.getUint16(i * 128 + 0x40, true)));
      const name = new TextDecoder('utf-16le').decode(dir.subarray(i * 128, i * 128 + Math.max(0, nameLen - 2)));
      this.entries.push({ name, type: dir[i * 128 + 0x42], start: ddv.getInt32(i * 128 + 0x74, true), size: ddv.getUint32(i * 128 + 0x78, true) });
    }
    const mf = this.chain(dv.getInt32(0x3c, true));
    const mdv = new DataView(mf.buffer, mf.byteOffset, mf.byteLength);
    this.miniFat = new Int32Array(Math.floor(mf.length / 4));
    for (let i = 0; i < this.miniFat.length; i++) this.miniFat[i] = mdv.getInt32(i * 4, true);
  }

  private offset(sector: number) { return (sector + 1) * this.sectorSize; }

  private chain(start: number): Uint8Array {
    const parts: Uint8Array[] = [];
    let s = start;
    let guard = 0;
    while (s >= 0 && s < Math.max(1, this.fat.length) && guard++ < 1_000_000) {
      const off = this.offset(s);
      if (off >= this.data.length) break;
      parts.push(this.data.subarray(off, Math.min(off + this.sectorSize, this.data.length)));
      s = this.fat.length === 0 ? -2 : this.fat[s];
    }
    return concat(parts);
  }

  stream(name: string): Uint8Array | null {
    const e = this.entries.find((x) => x.type === 2 && x.name.toLowerCase() === name.toLowerCase());
    if (!e) return null;
    if (e.size < this.miniCutoff) {
      const rootEntry = this.entries.find((x) => x.type === 5);
      if (!rootEntry) return null;
      const root = this.chain(rootEntry.start);
      const parts: Uint8Array[] = [];
      let got = 0;
      let s = e.start;
      let guard = 0;
      while (s >= 0 && s < this.miniFat.length && guard++ < 1_000_000 && got < e.size) {
        const off = s * this.miniSectorSize;
        if (off >= root.length) break;
        const piece = root.subarray(off, Math.min(off + this.miniSectorSize, root.length));
        parts.push(piece);
        got += piece.length;
        s = this.miniFat[s];
      }
      return fit(concat(parts), e.size);
    }
    return fit(this.chain(e.start), e.size);
  }
}

function concat(parts: Uint8Array[]): Uint8Array {
  const n = parts.reduce((s, p) => s + p.length, 0);
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

function fit(b: Uint8Array, size: number): Uint8Array {
  if (b.length === size) return b;
  const out = new Uint8Array(size);
  out.set(b.subarray(0, Math.min(size, b.length)));
  return out;
}

function biffNumber(d: number): string {
  return cleanNumber(String(d));
}

function firstSheet(data: Uint8Array): string[][] {
  const dv = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const u16 = (o: number) => dv.getUint16(o, true);
  const sst: string[] = [];
  const cells = new Map<number, string>();
  let maxRow = -1;
  let substream = 0;
  let inFirst = false;
  let done = false;
  let pos = 0;
  let pendingFormula: number | null = null;
  const put = (r: number, c: number, v: string) => {
    if (!inFirst || done) return;
    cells.set(r * 65536 + c, v);
    if (r > maxRow) maxRow = r;
  };
  const shortString = (lenOffset: number, cch: number) => {
    const flagsAt = lenOffset + 2;
    const wide = (data[flagsAt] & 1) === 1;
    const from = flagsAt + 1;
    return wide ? new TextDecoder('utf-16le').decode(data.subarray(from, from + cch * 2)) : latin1(data.subarray(from, from + cch));
  };
  const rk = (v: number) => {
    let d: number;
    if (v & 2) d = v >> 2;
    else {
      const b = new DataView(new ArrayBuffer(8));
      b.setUint32(0, (v & 0xfffffffc) >>> 0, false);
      b.setUint32(4, 0, false);
      d = b.getFloat64(0, false);
    }
    return v & 1 ? d / 100 : d;
  };
  while (pos + 4 <= data.length) {
    const type = u16(pos);
    const len = u16(pos + 2);
    const start = pos + 4;
    if (start + len > data.length) break;
    switch (type) {
      case 0x0809: {
        substream++;
        const dt = len >= 4 ? u16(start + 2) : 0;
        if (substream > 1 && dt === 0x0010 && !done && !inFirst) inFirst = true;
        break;
      }
      case 0x000a: if (inFirst) { done = true; inFirst = false; } break;
      case 0x00fc: {
        const pieces: [number, number][] = [[start, start + len]];
        let next = start + len;
        while (next + 4 <= data.length && u16(next) === 0x003c) {
          const l = u16(next + 2);
          pieces.push([next + 4, next + 4 + l]);
          next += 4 + l;
        }
        readSst(data, pieces, sst);
        break;
      }
      case 0x00fd: put(u16(start), u16(start + 2), sst[dv.getInt32(start + 6, true)] ?? ''); break;
      case 0x0204: put(u16(start), u16(start + 2), shortString(start + 6, u16(start + 6))); break;
      case 0x0203: put(u16(start), u16(start + 2), biffNumber(dv.getFloat64(start + 6, true))); break;
      case 0x027e: put(u16(start), u16(start + 2), biffNumber(rk(dv.getInt32(start + 6, true)))); break;
      case 0x00bd: {
        const r = u16(start);
        const first = u16(start + 2);
        const count = Math.floor((len - 6) / 6);
        for (let i = 0; i < count; i++) put(r, first + i, biffNumber(rk(dv.getInt32(start + 4 + i * 6 + 2, true))));
        break;
      }
      case 0x0006: {
        const r = u16(start);
        const c = u16(start + 2);
        if (u16(start + 12) === 0xffff) { if (data[start + 6] === 0) pendingFormula = r * 65536 + c; }
        else put(r, c, biffNumber(dv.getFloat64(start + 6, true)));
        break;
      }
      case 0x0207:
        if (pendingFormula != null) {
          put(Math.floor(pendingFormula / 65536), pendingFormula % 65536, shortString(start, u16(start)));
          pendingFormula = null;
        }
        break;
    }
    pos = start + len;
  }
  const out: string[][] = [];
  for (let r = 0; r <= maxRow; r++) {
    let width = 0;
    for (const k of cells.keys()) if (Math.floor(k / 65536) === r) width = Math.max(width, (k % 65536) + 1);
    const row: string[] = [];
    for (let c = 0; c < width; c++) row.push(cells.get(r * 65536 + c) ?? '');
    out.push(row);
  }
  return out;
}

function readSst(data: Uint8Array, pieces: [number, number][], out: string[]) {
  let p = 0;
  let off = pieces[0][0];
  const remaining = () => pieces[p][1] - off;
  const advance = () => { if (p + 1 >= pieces.length) return false; p++; off = pieces[p][0]; return true; };
  const byte = () => { if (remaining() <= 0 && !advance()) throw new RangeError(); return data[off++]; };
  const u16r = () => byte() | (byte() << 8);
  const u32r = () => (u16r() | (u16r() << 16)) >>> 0;
  const skip = (n: number) => { let k = n; while (k > 0) { if (remaining() <= 0 && !advance()) return; const t = Math.min(k, remaining()); off += t; k -= t; } };
  try {
    byte(); byte(); byte(); byte();
    const unique = u32r();
    for (let u = 0; u < unique; u++) {
      const cch = u16r();
      let flags = byte();
      const runs = flags & 0x08 ? u16r() : 0;
      const ext = flags & 0x04 ? u32r() : 0;
      let s = '';
      let left = cch;
      while (left > 0) {
        if (remaining() <= 0) {
          if (!advance()) break;
          flags = byte();
        }
        const wide = (flags & 1) === 1;
        const canTake = wide ? Math.floor(remaining() / 2) : remaining();
        const take = Math.min(left, canTake);
        if (take <= 0) { off = pieces[p][1]; continue; }
        s += wide ? new TextDecoder('utf-16le').decode(data.subarray(off, off + take * 2)) : latin1(data.subarray(off, off + take));
        off += wide ? take * 2 : take;
        left -= take;
      }
      skip(runs * 4 + ext);
      out.push(s);
    }
  } catch {
    // A truncated table still gives the strings read so far.
  }
}

export function readXls(bytes: Uint8Array): string[][] {
  const cfb = new Cfb(bytes);
  const stream = cfb.stream('Workbook') ?? cfb.stream('Book');
  if (!stream) throw new Error('No workbook in this .xls file');
  return firstSheet(stream);
}
