// Splits pasted text, or a .txt / PDF file holding many messages, into single SMS with a date for each.
//
// Text copied from Messages has no date of its own. A saved or exported conversation usually has a date / time
// line above each message ("27 Nov 2026 at 10:15 AM", "Yesterday 9:02 PM") and sometimes the sender
// ("VM-ICICIT"). Those lines start a new message and date it. Otherwise the date in the message itself
// ("on 05-11-26") is used, and failing that today, which the user can change before importing.

import { LocalDate, findDate } from './dates';

export type DatedBy = 'HEADER' | 'MESSAGE' | 'NONE';
export interface SplitSms { body: string; sender: string | null; timestamp: number; datedBy: DatedBy }

const money = /(?:rs\.?|inr|₹)\s*\d|\b(?:debited|credited|spent|received|paid|sent)\b/i;
const timeRe = /\b(\d{1,2}):(\d{2})(?::\d{2})?\s*([ap])?\.?\s*m?\.?(?![\w])/i;
const relDay = /^\W*(today|yesterday|mon|tue|wed|thu|fri|sat|sun)[a-z]*\b/i;
const dltSender = /^(?:from:\s*)?([A-Z]{2}-[A-Z0-9]{3,9}(?:-[A-Z])?)$/i;
const fromLine = /^from:\s*(.{2,40})$/i;
/** Lines that usually open a bank message, for files with no blank line between messages. */
const opener = /^(?:dear\b|your\s+(?:a\/c|ac|acct|account|card|upi)\b|a\/c\b|acct\b|(?:rs\.?|inr)\s?\d|₹\s?\d|sent\s+(?:rs|inr|₹)|spent\b|received\b|txn\b|you(?:'ve|\s+have)\s+(?:spent|paid|received)|icici\s+bank\s+acc|alert\s*:|update\s*:|money\s+(?:sent|received))/i;

const WEEK = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];

function dayOfHeader(line: string, today: LocalDate): LocalDate | null {
  const r = relDay.exec(line);
  const d = findDate(line, today);
  if (d) return d;
  if (!r) return null;
  const w = r[1].toLowerCase();
  if (w === 'today') return today;
  if (w === 'yesterday') return today.minusDays(1);
  const target = WEEK.indexOf(w.slice(0, 3));
  const now = new Date(today.atTime(12)).getDay();
  return today.minusDays(((now - target) + 7) % 7 || 7);
}

function timeOf(line: string): [number, number] | null {
  const m = timeRe.exec(line);
  if (!m) return null;
  let h = +m[1];
  const min = +m[2];
  if (m[3]) { const pm = m[3].toLowerCase() === 'p'; if (h === 12) h = pm ? 12 : 0; else if (pm) h += 12; }
  return h < 24 && min < 60 ? [h, min] : null;
}

/** A line that only dates the message below it. */
function header(line: string, today: LocalDate): { day: LocalDate | null; time: [number, number] | null } | null {
  if (line.length > 60 || money.test(line)) return null;
  const rest = line.replace(/[\d\s,:./\-[\]()]|\b(?:am|pm|at|on)\b/gi, '');
  // Mostly a date and time: a few words left at most (month and weekday names, "Today").
  if (rest.length > 24) return null;
  const day = dayOfHeader(line, today);
  const time = timeOf(line);
  if (!day && !time) return null;
  return { day, time };
}

export function splitSms(text: string, now: number = Date.now()): SplitSms[] {
  const today = LocalDate.fromMillis(now);
  const out: SplitSms[] = [];
  let lines: string[] = [];
  let hDay: LocalDate | null = null;
  let hTime: [number, number] | null = null;
  let hFresh = false;
  let sender: string | null = null;

  const flush = () => {
    const body = lines.join('\n').trim();
    lines = [];
    if (body.length < 12) return;
    let day: LocalDate | null = null;
    let datedBy: DatedBy = 'NONE';
    if (hDay) { day = hDay; datedBy = 'HEADER'; }
    else {
      const d = findDate(body, today);
      // A date after today is a due date or similar, not when the message came.
      if (d && !d.isAfter(today) && !d.isBefore(today.minusDays(3650))) { day = d; datedBy = 'MESSAGE'; }
    }
    let ts: number;
    if (!day) ts = now;
    else if (hFresh && hTime) ts = day.atTime(hTime[0], hTime[1]);
    else ts = day.equals(today) ? now : day.atTime(12, 0);
    out.push({ body, sender, timestamp: ts, datedBy });
    hFresh = false;
  };

  for (const raw of text.replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.trim();
    if (line === '') { flush(); continue; }
    const s = dltSender.exec(line) ?? fromLine.exec(line);
    if (s && !money.test(line)) { flush(); sender = s[1].trim(); continue; }
    const h = header(line, today);
    if (h) {
      flush();
      if (h.day) hDay = h.day;
      else if (!hDay) hDay = today; // a time alone is today's, as in Messages
      hTime = h.time;
      hFresh = true;
      continue;
    }
    if (lines.length && opener.test(line) && lines.join(' ').length >= 40) flush();
    lines.push(line);
  }
  flush();
  return out;
}

/**
 * A table of messages (CSV exported by an SMS backup tool): a column of message text, and if there is one a date
 * and a sender column. Null when [text] is not such a table.
 */
export function splitSmsTable(rows: string[][], now: number = Date.now()): SplitSms[] | null {
  if (rows.length < 2) return null;
  const head = rows[0].map((h) => h.trim().toLowerCase());
  const col = (re: RegExp) => head.findIndex((h) => re.test(h));
  const textCol = col(/^(?:text|body|message|content|sms|message\s*text)$/);
  if (textCol < 0) return null;
  const dateCol = col(/^(?:date|time|date\s*\/?\s*time|timestamp|sent|received|date\s*sent|date\s*received)$/);
  const fromCol = col(/^(?:from|sender|address|contact|number|phone)$/);
  const today = LocalDate.fromMillis(now);
  const out: SplitSms[] = [];
  for (const r of rows.slice(1)) {
    const body = (r[textCol] ?? '').trim();
    if (body.length < 12) continue;
    const when = dateCol >= 0 ? (r[dateCol] ?? '').trim() : '';
    let ts: number | null = null;
    let datedBy: DatedBy = 'NONE';
    if (/^\d{12,13}$/.test(when)) { ts = +when; datedBy = 'HEADER'; }
    else if (when) {
      const d = findDate(when, today);
      const t = timeOf(when);
      if (d) { ts = t ? d.atTime(t[0], t[1]) : d.atTime(12, 0); datedBy = 'HEADER'; }
    }
    if (ts == null) {
      const d = findDate(body, today);
      if (d && !d.isAfter(today)) { ts = d.equals(today) ? now : d.atTime(12, 0); datedBy = 'MESSAGE'; } else ts = now;
    }
    out.push({ body, sender: fromCol >= 0 ? (r[fromCol] ?? '').trim() || null : null, timestamp: ts, datedBy });
  }
  return out;
}
