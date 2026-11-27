// Small helpers so the Kotlin rules port line for line.

export type TxnType = 'DEBIT' | 'CREDIT';

/** Formats minor units as an Indian-rupee string, e.g. 123456 -> "₹1,234.56". */
export function formatInr(amountMinor: number, withSymbol = true): string {
  const negative = amountMinor < 0;
  const abs = Math.abs(Math.round(amountMinor));
  const rupees = Math.floor(abs / 100);
  const paise = abs % 100;
  const digits = String(rupees);
  let grouped: string;
  if (digits.length <= 3) grouped = digits;
  else {
    const last3 = digits.slice(-3);
    const rest = digits.slice(0, -3);
    grouped = rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',') + ',' + last3;
  }
  const body = paise === 0 ? grouped : `${grouped}.${String(paise).padStart(2, '0')}`;
  return (negative ? '-' : '') + (withSymbol ? '₹' : '') + body;
}

/** Whole rupees, "₹1,234". */
export function formatInrShort(amountMinor: number): string {
  return formatInr(Math.round(amountMinor / 100) * 100);
}

/** Decimal text to minor units with HALF_UP rounding, exact for the digits given. */
export function decimalToMinor(s: string): number | null {
  const m = /^(\d*)(?:\.(\d*))?$/.exec(s);
  if (!m || (m[1] === '' && (m[2] ?? '') === '')) return null;
  const whole = m[1] === '' ? 0 : parseInt(m[1], 10);
  const frac = (m[2] ?? '') + '000';
  let minor = whole * 100 + parseInt(frac.slice(0, 2), 10);
  if (parseInt(frac[2], 10) >= 5) minor += 1;
  return minor;
}

/** Parses a user-entered amount like "1,234.5" into minor units. Returns null when invalid. */
export function parseAmountToMinor(text: string): number | null {
  const cleaned = text.replace(/,/g, '').replace(/₹/g, '').trim();
  if (cleaned === '') return null;
  if (cleaned.startsWith('-')) return null;
  const c = cleaned.startsWith('+') ? cleaned.slice(1) : cleaned;
  return decimalToMinor(c);
}

/** Minor units as a plain decimal, like BigDecimal.valueOf(minor, 2).toPlainString(): "450.00". */
export function minorToPlain(minor: number): string {
  const neg = minor < 0;
  const a = Math.abs(minor);
  return (neg ? '-' : '') + Math.floor(a / 100) + '.' + String(a % 100).padStart(2, '0');
}

export function isLetter(c: string): boolean {
  return /\p{L}/u.test(c);
}

export function isDigit(c: string): boolean {
  return /\p{Nd}/u.test(c);
}

export function countLetters(s: string): number {
  return (s.match(/\p{L}/gu) ?? []).length;
}

export function countDigits(s: string): number {
  return (s.match(/\p{Nd}/gu) ?? []).length;
}

export function digitsOnly(s: string): string {
  return (s.match(/\p{Nd}/gu) ?? []).join('');
}

/** Kotlin's String.trim(vararg chars). */
export function trimChars(s: string, chars: string): string {
  let a = 0;
  let b = s.length;
  while (a < b && chars.includes(s[a])) a++;
  while (b > a && chars.includes(s[b - 1])) b--;
  return s.slice(a, b);
}

/** Kotlin's String.lines(). */
export function lines(s: string): string[] {
  return s.split(/\r\n|\n|\r/);
}

/** Kotlin's Regex.matches / matchEntire: the whole string. */
export function full(re: RegExp): RegExp {
  return new RegExp(`^(?:${re.source})$`, re.flags.replace('g', ''));
}

/** Kotlin's Regex.find(text, startIndex). */
export function findFrom(re: RegExp, text: string, start: number): RegExpExecArray | null {
  const g = new RegExp(re.source, re.flags.includes('g') ? re.flags : re.flags + 'g');
  g.lastIndex = start;
  return g.exec(text);
}

/** Kotlin's Regex.findAll. */
export function findAll(re: RegExp, text: string): RegExpExecArray[] {
  const g = new RegExp(re.source, re.flags.includes('g') ? re.flags : re.flags + 'g');
  const out: RegExpExecArray[] = [];
  let m: RegExpExecArray | null;
  while ((m = g.exec(text)) !== null) {
    out.push(m);
    if (m[0].length === 0) g.lastIndex++;
  }
  return out;
}

/** End index (exclusive) of a match: Kotlin's range.last + 1. */
export function end(m: RegExpExecArray): number {
  return m.index + m[0].length;
}

export function g(m: RegExpExecArray, i: number): string {
  return m[i] ?? '';
}

/** Kotlin's String.substringBefore(delimiter). */
export function before(s: string, delim: string): string {
  const i = s.indexOf(delim);
  return i < 0 ? s : s.slice(0, i);
}

export function after(s: string, delim: string, missing: string = s): string {
  const i = s.indexOf(delim);
  return i < 0 ? missing : s.slice(i + delim.length);
}

export function removePrefix(s: string, p: string): string {
  return s.startsWith(p) ? s.slice(p.length) : s;
}

export function title(s: string): string {
  return s
    .toLowerCase()
    .split(' ')
    .filter((w) => w.length > 0)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

export function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function groupBy<T, K>(list: T[], key: (t: T) => K): Map<K, T[]> {
  const m = new Map<K, T[]>();
  for (const t of list) {
    const k = key(t);
    const a = m.get(k);
    if (a) a.push(t);
    else m.set(k, [t]);
  }
  return m;
}

export function sumOf<T>(list: T[], f: (t: T) => number): number {
  let s = 0;
  for (const t of list) s += f(t);
  return s;
}

export function minBy<T>(list: T[], f: (t: T) => number): T | undefined {
  let best: T | undefined;
  let bv = Infinity;
  for (const t of list) {
    const v = f(t);
    if (v < bv) {
      bv = v;
      best = t;
    }
  }
  return best;
}

export function maxBy<T>(list: T[], f: (t: T) => number): T | undefined {
  let best: T | undefined;
  let bv = -Infinity;
  for (const t of list) {
    const v = f(t);
    if (v > bv) {
      bv = v;
      best = t;
    }
  }
  return best;
}
