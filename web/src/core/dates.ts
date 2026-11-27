// Date helpers mirroring java.time's LocalDate / YearMonth as used by the Android app's core.

const DAY_MS = 86_400_000;

export class LocalDate {
  readonly year: number;
  readonly month: number; // 1..12
  readonly day: number;

  private constructor(y: number, m: number, d: number) {
    this.year = y;
    this.month = m;
    this.day = d;
  }

  /** A valid date, or null (like LocalDate.of throwing). */
  static of(y: number, m: number, d: number): LocalDate | null {
    if (!Number.isInteger(y) || !Number.isInteger(m) || !Number.isInteger(d)) return null;
    if (m < 1 || m > 12 || d < 1 || d > daysInMonth(y, m)) return null;
    return new LocalDate(y, m, d);
  }

  static mustOf(y: number, m: number, d: number): LocalDate {
    const v = LocalDate.of(y, m, d);
    if (!v) throw new Error(`Invalid date ${y}-${m}-${d}`);
    return v;
  }

  static ofEpochDay(n: number): LocalDate {
    const dt = new Date(n * DAY_MS);
    return new LocalDate(dt.getUTCFullYear(), dt.getUTCMonth() + 1, dt.getUTCDate());
  }

  /** "2024-04-05". */
  static parse(s: string): LocalDate | null {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
    return m ? LocalDate.of(+m[1], +m[2], +m[3]) : null;
  }

  /** The local calendar date of an instant. */
  static fromMillis(ms: number): LocalDate {
    const d = new Date(ms);
    return new LocalDate(d.getFullYear(), d.getMonth() + 1, d.getDate());
  }

  static today(): LocalDate {
    return LocalDate.fromMillis(Date.now());
  }

  toEpochDay(): number {
    return Math.round(Date.UTC(this.year, this.month - 1, this.day) / DAY_MS);
  }

  plusDays(n: number): LocalDate {
    return LocalDate.ofEpochDay(this.toEpochDay() + n);
  }

  minusDays(n: number): LocalDate {
    return this.plusDays(-n);
  }

  plusMonths(n: number): LocalDate {
    const total = this.year * 12 + (this.month - 1) + n;
    const y = Math.floor(total / 12);
    const m = (total % 12 + 12) % 12 + 1;
    return new LocalDate(y, m, Math.min(this.day, daysInMonth(y, m)));
  }

  plusYears(n: number): LocalDate {
    return this.plusMonths(12 * n);
  }

  withDayOfMonth(d: number): LocalDate {
    return LocalDate.mustOf(this.year, this.month, d);
  }

  /** 1 = Monday … 7 = Sunday, as java.time.DayOfWeek. */
  get dayOfWeek(): number {
    const w = new Date(Date.UTC(this.year, this.month - 1, this.day)).getUTCDay();
    return w === 0 ? 7 : w;
  }

  lengthOfMonth(): number {
    return daysInMonth(this.year, this.month);
  }

  isBefore(o: LocalDate): boolean {
    return this.toEpochDay() < o.toEpochDay();
  }

  isAfter(o: LocalDate): boolean {
    return this.toEpochDay() > o.toEpochDay();
  }

  equals(o: LocalDate | null | undefined): boolean {
    return !!o && this.year === o.year && this.month === o.month && this.day === o.day;
  }

  compareTo(o: LocalDate): number {
    return this.toEpochDay() - o.toEpochDay();
  }

  /** Local time instant for this date at [h]:[min]:[s]. */
  atTime(h = 12, min = 0, s = 0): number {
    return new Date(this.year, this.month - 1, this.day, h, min, s).getTime();
  }

  atStartOfDay(): number {
    return this.atTime(0, 0, 0);
  }

  toString(): string {
    return `${String(this.year).padStart(4, '0')}-${pad2(this.month)}-${pad2(this.day)}`;
  }

  get ym(): YearMonth {
    return YearMonth.of(this.year, this.month);
  }
}

export class LocalTime {
  constructor(readonly hour: number, readonly minute: number, readonly second: number = 0) {}

  static of(h: number, m: number, s = 0): LocalTime | null {
    if (h < 0 || h > 23 || m < 0 || m > 59 || s < 0 || s > 59) return null;
    return new LocalTime(h, m, s);
  }

  static ofSecondOfDay(n: number): LocalTime {
    return new LocalTime(Math.floor(n / 3600), Math.floor((n % 3600) / 60), n % 60);
  }

  static readonly NOON = new LocalTime(12, 0, 0);
}

export class YearMonth {
  private constructor(readonly year: number, readonly month: number) {}

  static of(y: number, m: number): YearMonth {
    const total = y * 12 + (m - 1);
    return new YearMonth(Math.floor(total / 12), (total % 12 + 12) % 12 + 1);
  }

  static parse(s: string): YearMonth | null {
    const m = /^(\d{4})-(\d{2})$/.exec(s);
    return m ? YearMonth.of(+m[1], +m[2]) : null;
  }

  static from(d: LocalDate): YearMonth {
    return YearMonth.of(d.year, d.month);
  }

  static fromMillis(ms: number): YearMonth {
    return YearMonth.from(LocalDate.fromMillis(ms));
  }

  plusMonths(n: number): YearMonth {
    return YearMonth.of(this.year, this.month + n);
  }

  minusMonths(n: number): YearMonth {
    return this.plusMonths(-n);
  }

  atDay(d: number): LocalDate {
    return LocalDate.mustOf(this.year, this.month, d);
  }

  atEndOfMonth(): LocalDate {
    return this.atDay(this.lengthOfMonth());
  }

  lengthOfMonth(): number {
    return daysInMonth(this.year, this.month);
  }

  get index(): number {
    return this.year * 12 + this.month - 1;
  }

  equals(o: YearMonth | null | undefined): boolean {
    return !!o && o.year === this.year && o.month === this.month;
  }

  compareTo(o: YearMonth): number {
    return this.index - o.index;
  }

  toString(): string {
    return `${this.year}-${pad2(this.month)}`;
  }

  /** "November 2026". */
  label(): string {
    return `${MONTH_NAMES[this.month - 1]} ${this.year}`;
  }

  /** "Nov 26". */
  short(): string {
    return `${MONTH_NAMES[this.month - 1].slice(0, 3)} ${String(this.year % 100).padStart(2, '0')}`;
  }
}

export const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

export function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

export function daysInMonth(y: number, m: number): number {
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

/** "5 Apr 2026". */
export function formatDate(d: LocalDate, withYear = true): string {
  const base = `${d.day} ${MONTH_NAMES[d.month - 1].slice(0, 3)}`;
  return withYear ? `${base} ${d.year}` : base;
}

export function formatDateMs(ms: number, withYear = true): string {
  return formatDate(LocalDate.fromMillis(ms), withYear);
}

export function weekdayShort(d: LocalDate): string {
  return ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][d.dayOfWeek - 1];
}

/** Indian financial year (April to March) a date falls in, given by its starting calendar year. */
export function financialYearStart(date: LocalDate): number {
  return date.month >= 4 ? date.year : date.year - 1;
}

/** "FY 2024-25". */
export function financialYearLabel(startYear: number): string {
  return `FY ${startYear}-${String((startYear + 1) % 100).padStart(2, '0')}`;
}

// ---- Dates.findDate: the first date in a text (bill due dates) ----

const MONTHS: Record<string, number> = {
  jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12,
};
const MON = '(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*';
const dmyNumeric = /\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b/;
const ymdRe = /\b(\d{4})-(\d{1,2})-(\d{1,2})\b/;
const dMonY = new RegExp(String.raw`\b(\d{1,2})(?:st|nd|rd|th)?[\s\-/]*` + MON + String.raw`\.?[\s\-/,]*(\d{2,4})?\b`, 'i');
const monDY = new RegExp(String.raw`\b` + MON + String.raw`\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s*(\d{4})?\b`, 'i');

function yearOf(s: string): number {
  const n = parseInt(s, 10);
  return s.length <= 2 ? 2000 + n : n;
}

function safe2000(y: number, m: number, d: number): LocalDate | null {
  if (y < 2000 || y > 2100) return null;
  return LocalDate.of(y, m, d);
}

function withYear(y: string | undefined, month: number, day: number, reference: LocalDate): LocalDate | null {
  if (y) return safe2000(yearOf(y), month, day);
  const thisYear = safe2000(reference.year, month, day);
  if (!thisYear) return null;
  return thisYear.isBefore(reference.minusDays(7)) ? thisYear.plusYears(1) : thisYear;
}

export function findDate(text: string, reference: LocalDate): LocalDate | null {
  const hits: { start: number; date: LocalDate | null }[] = [];
  let m = ymdRe.exec(text);
  if (m) hits.push({ start: m.index, date: safe2000(+m[1], +m[2], +m[3]) });
  m = dmyNumeric.exec(text);
  if (m) hits.push({ start: m.index, date: safe2000(yearOf(m[3]), +m[2], +m[1]) });
  m = dMonY.exec(text);
  if (m) hits.push({ start: m.index, date: withYear(m[3], MONTHS[m[2].toLowerCase().slice(0, 3)], +m[1], reference) });
  m = monDY.exec(text);
  if (m) hits.push({ start: m.index, date: withYear(m[3], MONTHS[m[1].toLowerCase().slice(0, 3)], +m[2], reference) });
  const ok = hits.filter((h) => h.date != null).sort((a, b) => a.start - b.start);
  return ok.length ? ok[0].date : null;
}
