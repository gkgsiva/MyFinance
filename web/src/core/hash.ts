// SHA-1 and MD5 (for name-based UUIDs), synchronous, so statement keys and uids match the Android app exactly.

function utf8(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

function hex(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += b.toString(16).padStart(2, '0');
  return s;
}

export function sha1Bytes(data: Uint8Array): Uint8Array {
  const ml = data.length * 8;
  const withOne = data.length + 1;
  const total = Math.ceil((withOne + 8) / 64) * 64;
  const buf = new Uint8Array(total);
  buf.set(data);
  buf[data.length] = 0x80;
  const dv = new DataView(buf.buffer);
  dv.setUint32(total - 8, Math.floor(ml / 0x100000000));
  dv.setUint32(total - 4, ml >>> 0);
  let h0 = 0x67452301, h1 = 0xefcdab89, h2 = 0x98badcfe, h3 = 0x10325476, h4 = 0xc3d2e1f0;
  const w = new Uint32Array(80);
  for (let off = 0; off < total; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4);
    for (let i = 16; i < 80; i++) {
      const x = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
      w[i] = (x << 1) | (x >>> 31);
    }
    let a = h0, b = h1, c = h2, d = h3, e = h4;
    for (let i = 0; i < 80; i++) {
      let f: number, k: number;
      if (i < 20) { f = (b & c) | (~b & d); k = 0x5a827999; }
      else if (i < 40) { f = b ^ c ^ d; k = 0x6ed9eba1; }
      else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8f1bbcdc; }
      else { f = b ^ c ^ d; k = 0xca62c1d6; }
      const t = (((a << 5) | (a >>> 27)) + f + e + k + w[i]) >>> 0;
      e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = t;
    }
    h0 = (h0 + a) >>> 0; h1 = (h1 + b) >>> 0; h2 = (h2 + c) >>> 0; h3 = (h3 + d) >>> 0; h4 = (h4 + e) >>> 0;
  }
  const out = new Uint8Array(20);
  const ov = new DataView(out.buffer);
  [h0, h1, h2, h3, h4].forEach((h, i) => ov.setUint32(i * 4, h));
  return out;
}

export function sha1Hex(s: string): string {
  return hex(sha1Bytes(utf8(s)));
}

const S = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
const K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0);

export function md5Bytes(data: Uint8Array): Uint8Array {
  const ml = data.length * 8;
  const total = Math.ceil((data.length + 1 + 8) / 64) * 64;
  const buf = new Uint8Array(total);
  buf.set(data);
  buf[data.length] = 0x80;
  const dv = new DataView(buf.buffer);
  dv.setUint32(total - 8, ml >>> 0, true);
  dv.setUint32(total - 4, Math.floor(ml / 0x100000000), true);
  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  for (let off = 0; off < total; off += 64) {
    const m = new Uint32Array(16);
    for (let i = 0; i < 16; i++) m[i] = dv.getUint32(off + i * 4, true);
    let a = a0, b = b0, c = c0, d = d0;
    for (let i = 0; i < 64; i++) {
      let f: number, gi: number;
      if (i < 16) { f = (b & c) | (~b & d); gi = i; }
      else if (i < 32) { f = (d & b) | (~d & c); gi = (5 * i + 1) % 16; }
      else if (i < 48) { f = b ^ c ^ d; gi = (3 * i + 5) % 16; }
      else { f = c ^ (b | ~d); gi = (7 * i) % 16; }
      f = (f + a + K[i] + m[gi]) >>> 0;
      a = d; d = c; c = b;
      b = (b + ((f << S[i]) | (f >>> (32 - S[i])))) >>> 0;
    }
    a0 = (a0 + a) >>> 0; b0 = (b0 + b) >>> 0; c0 = (c0 + c) >>> 0; d0 = (d0 + d) >>> 0;
  }
  const out = new Uint8Array(16);
  const ov = new DataView(out.buffer);
  [a0, b0, c0, d0].forEach((h, i) => ov.setUint32(i * 4, h, true));
  return out;
}

/** java.util.UUID.nameUUIDFromBytes(s.toByteArray()). */
export function nameUuid(s: string): string {
  const b = md5Bytes(utf8(s));
  b[6] = (b[6] & 0x0f) | 0x30;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = hex(b);
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export function randomUuid(): string {
  const c = globalThis.crypto as Crypto;
  if (typeof c.randomUUID === 'function') return c.randomUUID();
  const b = new Uint8Array(16);
  c.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = hex(b);
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
