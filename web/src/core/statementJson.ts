// A read statement as plain JSON: what the GitHub Actions job writes to the bucket and the app reads back. Only
// what the statement says is stored (dates as written, no time zone), so the app works out keys and times itself
// exactly as for a file opened on the phone.

import { LocalDate, LocalTime } from './dates';
import { ParsedStatement, StatementRow } from './statementParser';

export const FORMAT = 1;

export interface StatementJson {
  format: number;
  kind: ParsedStatement['kind'];
  bank: string | null;
  accountTail: string | null;
  billTotalMinor: number | null;
  billMinMinor: number | null;
  billDueDate: string | null;
  check: ParsedStatement['check'];
  numbersSeen: [string | null, string][];
  /** date, seconds of day or null, description, amount, type, balance, reference, card tail, serial, foreign. */
  rows: [string, number | null, string, number, 'DEBIT' | 'CREDIT', number | null, string | null, string | null, string | null, string | null][];
}

export function toJson(s: ParsedStatement): StatementJson {
  return {
    format: FORMAT, kind: s.kind, bank: s.bank, accountTail: s.accountTail, billTotalMinor: s.billTotalMinor, billMinMinor: s.billMinMinor,
    billDueDate: s.billDueDate?.toString() ?? null, check: s.check, numbersSeen: s.numbersSeen,
    rows: s.rows.map((r) => [
      r.date.toString(), r.time ? r.time.hour * 3600 + r.time.minute * 60 + r.time.second : null, r.description, r.amountMinor, r.type,
      r.balanceMinor, r.reference, r.cardTail, r.serial, r.foreign,
    ]),
  };
}

export function fromJson(j: StatementJson): ParsedStatement {
  if (j.format !== FORMAT) throw new Error(`Unknown statement format ${j.format}`);
  const rows: StatementRow[] = j.rows.map((r) => {
    const date = LocalDate.parse(r[0]);
    if (!date) throw new Error(`Bad date ${r[0]}`);
    return {
      date, time: r[1] != null ? LocalTime.ofSecondOfDay(r[1]) : null, description: r[2], amountMinor: r[3], type: r[4],
      balanceMinor: r[5], reference: r[6], cardTail: r[7], serial: r[8], foreign: r[9],
    };
  });
  return {
    rows, kind: j.kind, bank: j.bank, accountTail: j.accountTail, billTotalMinor: j.billTotalMinor, billMinMinor: j.billMinMinor,
    billDueDate: j.billDueDate ? LocalDate.parse(j.billDueDate) : null, check: j.check, numbersSeen: j.numbersSeen ?? [],
  };
}

/** processed/index.json: one entry per statement file in the bucket. */
export interface IndexJson {
  format: number;
  generatedAt: string;
  files: IndexEntry[];
}

export interface IndexEntry {
  /** The statement's key in the bucket, e.g. "statements/2026/sav-oct.pdf". */
  key: string;
  etag: string;
  /** Where its JSON is, e.g. "processed/files/<hash>.json"; null when it could not be read. */
  path: string | null;
  status: 'OK' | 'NEEDS_PASSWORD' | 'FAILED';
  message: string | null;
  kind: ParsedStatement['kind'] | null;
  rows: number;
  from: string | null;
  to: string | null;
}
