// Port of core/SmsParser.kt, SmsDedupe.kt and the Fx conversion in Currency.kt.
//
// iOS lets no app read SMS, so on iPhone bank messages are pasted in. They are read with the same rules as on
// Android, so an entry made from a pasted SMS matches the one the Android app made from the same message.

import { sha1Hex } from './hash';
import { ForeignAmount, fxFind } from './rules';
import { Narration, PayeeNames } from './statementParser';
import { TxnType, parseAmountToMinor } from './text';

export interface ParsedSms {
  amountMinor: number;
  type: TxnType;
  merchant: string | null;
  accountTail: string | null;
  bank: string | null;
  reference: string | null;
  balanceMinor: number | null;
  timestamp: number;
  foreign: ForeignAmount | null;
  fxRate: number | null;
  /** The user's other account named in a transfer between their own accounts. */
  counterTail: string | null;
}

// ---- Fx ----

export const defaultRates: Record<string, number> = {
  USD: 88.0, EUR: 102.0, GBP: 117.0, AED: 24.0, SGD: 68.0, THB: 2.7, MYR: 21.0,
  JPY: 0.58, AUD: 58.0, CAD: 63.0, CHF: 110.0, HKD: 11.3, SAR: 23.5, QAR: 24.2,
  LKR: 0.29, NPR: 0.62, NZD: 51.0, IDR: 0.0053, VND: 0.0034,
};

export function fxRate(code: string, rates: Record<string, number> = defaultRates): number | null {
  const c = code.toUpperCase();
  return rates[c] ?? defaultRates[c] ?? null;
}

/** Rupee paise for [amount] at [rate] rupees per unit, rounded half up. */
export function toInrMinor(amount: ForeignAmount, rate: number): number {
  // Through decimal text, as BigDecimal does, so 12.99 × 88 is exact.
  const v = amount.amountMinor * rate;
  return Math.round(Number(v.toPrecision(15)));
}

function plain(minor: number): string {
  const s = (minor / 100).toFixed(2);
  return s.replace(/\.?0+$/, '');
}

/** The note kept with a converted entry, e.g. "USD 12.99 at ₹88.00". */
export function fxNote(amount: ForeignAmount, rate: number): string {
  return `${amount.code} ${plain(amount.amountMinor)} at ₹${rate.toFixed(rate < 1 ? 4 : 2)}`;
}

// ---- SmsParser ----

const CUR = String.raw`(?:rs\.?|inr|₹)`;
const AMOUNT = String.raw`${CUR}\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)`;
const amountRegex = new RegExp(AMOUNT, 'i');
const amountRegexG = new RegExp(AMOUNT, 'gi');

const bareAmountRegex = /\b(?:debited|credited)\s+(?:by|for|with)\s+([0-9][0-9,]*(?:\.[0-9]{1,2})?)\b/i;
const balanceHint = /(?:bal(?:ance)?|lmt|limit)\.?\s*(?:is|:|-|of|=)?\s*$/i;

const ignoreRegex = new RegExp([
  String.raw`\bdeclined\b`, String.raw`\bfailed\b`, String.raw`\bunsuccessful\b`,
  String.raw`will\s+be\s+(?:auto[\s-]?)?(?:debited|credited|deducted|charged)`, String.raw`\b(?:will|shall)\s+(?:auto[\s-]?)?debit\b`,
  String.raw`\bis\s+due\b`, String.raw`\bdue\s+(?:on|by)\b`,
  String.raw`payment\s+request`, String.raw`\brequested\b`, String.raw`\bpre-?approved\b`, String.raw`\boffer\b`,
  String.raw`\bminimum\s+amount\s+due\b`, String.raw`\bauto-?pay\b.*\bscheduled\b`,
  String.raw`\b(?:coupon|voucher|lucky\s+draw|scratch\s+card|spin\s+(?:the\s+wheel|to\s+win)|refer\s*(?:and|&)\s*earn)\b`,
  String.raw`\b(?:returned\s+unpaid|dishono(?:u)?red|bounced)\b`,
  String.raw`\bmatures\s+on\b|\bmaturity\s+date\b`,
  String.raw`(?:rs\.?|inr|₹)\s?[\d,]+(?:\.\d{1,2})?\s*off\b`,
  String.raw`\b(?:get|earn|win|avail|assured|flat|upto|up\s+to)\s+(?:(?:rs\.?|inr|₹)\s?[\d,]+(?:\.\d{1,2})?\s+)?cashback\b|\bcashback\s+(?:offer|waiting)\b`,
  String.raw`\b(?:flat|upto|up\s+to|at\s+just|starting\s+(?:at|from))\s+(?:rs\.?|inr|₹)`,
  String.raw`\b(?:shop\s+now|buy\s+now|claim\s+now|apply\s+now|t\s*&\s*c\s+apply|tnc\s+apply)\b`,
  String.raw`\b(?:congratulations|you\s+have\s+won|you've\s+won)\b`,
  String.raw`\bspent\b.{0,80}\bthis\s+(?:month|week|year)\b`, String.raw`\bin\s+the\s+(?:last|past)\s+\d+\s+days\b`,
  String.raw`\brefund\s+(?:has\s+been\s+|is\s+)?initiated\b`, String.raw`\bwill\s+(?:be\s+)?reflect`,
  String.raw`\b(?:credit\s+)?limit\s+(?:has\s+been\s+)?(?:increased|enhanced|upgraded)\b`,
  String.raw`\b(?:collect|mandate|autopay)\s+request\b`,
  String.raw`\b(?:mandate|auto[\s-]?pay)\b.{0,80}\b(?:created|registered|set\s*up|modified|revoked|paused|cancell?ed|approved|activated)\b`,
  String.raw`\bpre-?debit\s+(?:notification|intimation|alert|notice)\b`,
  String.raw`\b(?:blocked|unblocked|on\s+hold)\b.{0,60}\b(?:mandate|auto[\s-]?pay)\b|\b(?:mandate|auto[\s-]?pay)\b.{0,60}\b(?:blocked|unblocked|on\s+hold)\b`,
  String.raw`\b(?:is|was|remains)\s+pending\b|\bin\s+progress\b|\bprocessing\s+(?:your\s+)?(?:payment|request|transaction)\b`,
].join('|'), 'i');

const otpRegex = new RegExp([
  String.raw`\b\d{3,8}\s+is\s+(?:the\s+|your\s+|an?\s+)?(?:otp|one[\s-]time\s+password|verification\s+code|secret\s+code)\b`,
  String.raw`\b(?:otp|one[\s-]time\s+password|verification\s+code)\b.{0,80}?(?:\bis\b|:)\s*\d{3,8}\b`,
  String.raw`\b(?:use|enter)\s+(?:otp|one[\s-]time\s+password)\s*:?\s*\d{3,8}\b`,
].join('|'), 'i');

const cardBillRegex = /\btotal\s+(?:amount\s+)?due\b|\bmin(?:imum)?\.?\s+(?:amt|amount)\s+(?:due|of)\b|\bbill\s+(?:has\s+been\s+|is\s+)?generated\b|\bstatement\s+(?:has\s+been\s+|is\s+)?generated\b/i;
const completedMoney = /\b(?:received|credited|thank\s+you|debited|spent)\b/i;

const debitWords = /\b(?:debited|debit|spent|paid|withdrawn|withdrawal|sent|purchase|purchased|deducted|txn\s+of|transferred\s+to|dr|charged|used\s+(?:for|at)|(?:a\s+)?transaction\s+of)\b/i;
const creditWords = /\b(?:credited|credit(?!\s+card)|received|deposited|refund(?:ed)?|reversed|cashback|cr)\b/i;

const accountRegex = /(?:a\/c|acct|account|card|ac)\s*(?:no\.?|number)?\s*(?:ending\s*(?:with|in)?\s*)?[:\-]?\s*[xX*.•●]*\s?([0-9]{3,6})\b/i;
const maskedRegex = /[xX*•●]{2,}\s?([0-9]{3,6})\b/;

const refRegex = /(?:upi\s*ref(?:\.|erence)?\s*(?:no\.?)?|ref(?:\.|erence)?\s*(?:no\.?|number|#)?|rrn|txn\s*id|utr)\s*[:\-]?\s*([A-Za-z0-9]{6,})/i;

const STOP = String.raw`(?=\s+on\s|\s+ref|\s+upi\b|\s+via\b|\s+using\b|\s+avl\b|\s+avail|\s+bal\b|\s+dated\b|\s+at\s|\s+txn\b|\s*[.,;(]|\s*$|\s+for\s|\s+if\s|\s+not\s|\s+from\s|\s+by\s)`;
const NAME = String.raw`([A-Za-z0-9][A-Za-z0-9 &'_./@\-]{1,40}?)`;

const vpaRegex = /(?:vpa|upi\s*id)\s*[:\-]?\s*([A-Za-z0-9._\-]+@[A-Za-z]+)/i;
const atRegex = new RegExp(String.raw`\bat\s+${NAME}${STOP}`, 'gi');
const toRegex = new RegExp(String.raw`\b(?:to|towards|trf\s+to|paid\s+to)\s+${NAME}${STOP}`, 'gi');
const fromRegex = new RegExp(String.raw`\b(?:from|by)\s+${NAME}${STOP}`, 'gi');
const infoRegex = new RegExp(String.raw`\binfo\s*[:\-]\s*${NAME}${STOP}`, 'gi');

const bankCodes: [string, string][] = [
  ['HDFC', 'HDFC Bank'], ['SBI', 'SBI'], ['ICICI', 'ICICI Bank'], ['AXIS', 'Axis Bank'],
  ['KOTAK', 'Kotak Bank'], ['KMB', 'Kotak Bank'], ['YES', 'Yes Bank'], ['IDFC', 'IDFC First'],
  ['INDUS', 'IndusInd Bank'], ['PNB', 'PNB'], ['BOB', 'Bank of Baroda'], ['BARODA', 'Bank of Baroda'],
  ['CANARA', 'Canara Bank'], ['CANBNK', 'Canara Bank'], ['UNION', 'Union Bank'], ['UBOI', 'Union Bank'],
  ['FEDERAL', 'Federal Bank'], ['FEDBNK', 'Federal Bank'], ['AUBANK', 'AU Bank'], ['RBL', 'RBL Bank'],
  ['PAYTM', 'Paytm'], ['AMEX', 'Amex'], ['CITI', 'Citi'], ['SCB', 'Standard Chartered'],
  ['HSBC', 'HSBC'], ['IOB', 'Indian Overseas Bank'], ['INDBNK', 'Indian Bank'], ['BOI', 'Bank of India'],
  ['PAYZAP', 'HDFC Bank'], ['ICBANK', 'ICICI Bank'], ['IDBI', 'IDBI Bank'], ['BANDHN', 'Bandhan Bank'],
  ['UCOBNK', 'UCO Bank'], ['KVBANK', 'Karur Vysya Bank'], ['SIBSMS', 'South Indian Bank'], ['DBSBNK', 'DBS Bank'],
  ['CSBBNK', 'CSB Bank'], ['EQUTAS', 'Equitas Bank'], ['ONECRD', 'OneCard'], ['ONECARD', 'OneCard'],
  ['SLCEIT', 'slice'], ['SLICE', 'slice'], ['SCAPIA', 'Scapia'], ['JUPITR', 'Jupiter'],
  ['AXSFIN', 'Axis Bank'], ['KTKREM', 'Kotak Bank'], ['CAANBK', 'Canara Bank'], ['FEDBK', 'Federal Bank'],
  ['AMZPAY', 'Amazon Pay'], ['PHONPE', 'PhonePe'], ['MOBIKW', 'MobiKwik'], ['FRCHRG', 'Freecharge'],
];
const bankBodyRes: [RegExp, string][] = bankCodes.map(([k, v]) => [new RegExp(String.raw`\b${k}\b`), v]);

const junkMerchant = /^(?:your|you|a\/c|ac|acct|account|card|the|bank|upi|imps|neft|rtgs|mobile|beneficiary|vpa|self|xx\w*|\d+)$/i;

const receiptWords = /\bpayment\s+(?:of\s+)?(?:rs\.?|inr|₹)\s?[\d,]+(?:\.\d{1,2})?.{0,140}?\b(?:has\s+been\s+|is\s+|was\s+)?received\b|\bthank\s+you\s+for\s+(?:your\s+|the\s+)?payment\b|\bwe\s+have\s+received\s+(?:your\s+|the\s+)?payment\b|\breceived\s+your\s+payment\b/i;
const ownAccountWords = /\b(?:a\/c|acct|account|card|wallet)\b|\bcredited\s+(?:to|in)\b|\bin\s+your\s+(?:bank|a\/c|account)\b/i;

const mealCard = /\b(?:pluxee|sodexo)\b|\bmeal\s+card\b|\bzeta\b.*\bmeal\b/i;
const fastag = /\bfas\s*tag\b/i;
const fastagTag = /\btag\s*id\s*[:\-]?\s*([A-Za-z0-9]{4,})\b/i;

const channelRef = /\b(?:upi|imps|neft)\s*[:\-/]\s*(\d{12})\b/i;
const toOwnAccount = /(?:&|\band\b)\s*(?:your\s+)?(?:a\/c|acct|account)\s*(?:no\.?)?\s*[xX*•]*\s?(\d{3,6})\s+(?:is\s+)?credited/i;
const fromOwnAccount = /\bfrom\s+(?:your\s+)?(?:a\/c|acct|account)\s*(?:no\.?)?\s*[xX*•]+\s?(\d{3,6})\b/i;

const balanceClauses = [
  /\b(?:avl|avbl|avail|available|total|updated|remaining|current|curr|clr|ledger|closing)(?:\.?\s*avail)?\.?\s*(?:credit\s+)?(?:bal(?:ance)?|lmt|limit)\b[^\d\n]{0,30}(?:rs\.?|inr|₹)?\s*[\d,]+(?:\.\d{1,2})?/gi,
  /\b(?:balance|credit\s+limit)\s*(?:is|:)[^\d\n]{0,20}(?:rs\.?|inr|₹)?\s*[\d,]+(?:\.\d{1,2})?/gi,
  /\b(?:total\s+)?outstanding\b[^\d\n]{0,20}(?:rs\.?|inr|₹)?\s*[\d,]+(?:\.\d{1,2})?/gi,
];
const transactionOfRegex = /\btransaction\s+of\s+([0-9][0-9,]*(?:\.[0-9]{1,2})?)\b/i;

const upiDash = /\bUPI-\d{6,}-([A-Za-z][A-Za-z0-9 &'._@\-]{1,40}?)(?=\s*\.\s|\s*\.$|\s*\/|\s+(?:to|on|ref|avl)\b|$)/i;
const iciciPayee = /;\s*([A-Za-z0-9][A-Za-z0-9 &'._@\-]{1,40}?)\s+credited\b/i;
const upiNarration = /\bUPI\/(?:(?:DR|CR|P2A|P2M|P2P|REV)\/)?\d{6,}\/([A-Za-z][A-Za-z0-9 &.'\-]{1,40}?)(?=\s*\/|\s*\.\s|\s*\.$|\s+(?:avl|available|bal|ref|upi|on|not|if|call|sms)\b|$)/i;
// Kotlin uses a lookbehind (?<![\w.@-]); here the character before is matched instead, for older Safari.
const anyVpa = /(?:^|[^\w.@-])([A-Za-z0-9][A-Za-z0-9._\-]{1,40}@(?:ybl|ibl|axl|paytm|ptyes|ptaxis|pthdfc|ptsbi|okaxis|okhdfcbank|okicici|oksbi|upi|apl|yapl|icici|sbi|hdfcbank|axisbank|kotak|federal|indus|rbl|idfcbank|aubank|barodampay|uboi|unionbank|yesbank|jupiteraxis|fbl|airtel|freecharge|ikwik|naviaxis|slc|waaxis|wahdfcbank|wasbi|waicici))\b/i;

function normalize(body: string): string {
  return body.replace(/\n/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
}

/** Reads one SMS. Null for anything that is not a completed transaction (OTPs, offers, reminders, failures). */
export function parseSms(sender: string, body: string, timestamp: number, rates: Record<string, number> = defaultRates): ParsedSms | null {
  const text = normalize(body);
  if (text === '') return null;
  if (otpRegex.test(text)) return null;
  if (ignoreRegex.test(text)) return null;
  if (cardBillRegex.test(text) && !completedMoney.test(text)) return null;
  if (isBillerReceipt(sender, text)) return null;

  const amountMatch = findTransactionAmount(text);
  const fx = fxFind(text);
  const foreign = fx != null && (amountMatch == null || fx[1] < amountMatch.index) ? fx[0] : null;
  const rate = foreign != null ? fxRate(foreign.code, rates) : null;
  let amount: number | null;
  if (foreign != null && rate != null) amount = toInrMinor(foreign, rate);
  else {
    if (amountMatch == null) return null;
    amount = parseAmountToMinor(amountMatch[1]);
    if (amount == null) return null;
  }
  if (amount <= 0) return null;

  const type = detectType(text);
  if (type == null) return null;

  const tag = fastag.test(text) ? fastagTag.exec(text) : null;
  const tail = tag != null ? tag[1].toUpperCase() : accountTail(text);

  return {
    amountMinor: amount,
    type,
    merchant: PayeeNames.better(extractMerchant(text, type), text),
    accountTail: tail,
    bank: instrument(text) ?? bankFromSender(sender) ?? bankFromBody(text),
    reference: refRegex.exec(text)?.[1] ?? channelRef.exec(text)?.[1] ?? null,
    balanceMinor: findBalance(text),
    timestamp,
    foreign: rate != null ? foreign : null,
    fxRate: rate,
    counterTail: (type === 'DEBIT' ? toOwnAccount : fromOwnAccount).exec(text)?.[1] ?? null,
  };
}

/** A biller's receipt for a payment the user made: the bank's debit is the transaction, this is not income. */
export function isBillerReceipt(sender: string, body: string): boolean {
  const text = body.replace(/\n/g, ' ').replace(/\s+/g, ' ').trim();
  if (!receiptWords.test(text)) return false;
  if (bankFromSender(sender) != null || ownAccountWords.test(text) || accountTail(text) != null) return false;
  return true;
}

function instrument(text: string): string | null {
  if (fastag.test(text)) return 'FASTag';
  if (mealCard.test(text)) return /\bpluxee\b/i.test(text) ? 'Pluxee meal card' : 'Meal card';
  return null;
}

/** Last digits of the account or card the message is about, e.g. "1234" from "A/c XX1234". */
export function accountTail(text: string): string | null {
  return maskedRegex.exec(text)?.[1] ?? accountRegex.exec(text)?.[1] ?? null;
}

function findTransactionAmount(text: string): RegExpExecArray | null {
  const masked = maskBalances(text);
  const found: RegExpExecArray[] = [];
  for (const m of masked.matchAll(amountRegexG)) {
    if (!isBalance(masked, m)) { found.push(m as RegExpExecArray); break; }
  }
  const b = bareAmountRegex.exec(masked); if (b) found.push(b);
  const t = transactionOfRegex.exec(masked); if (t) found.push(t);
  if (found.length === 0) return null;
  return found.reduce((a, x) => (x.index < a.index ? x : a));
}

/** Blanks balance and limit clauses with spaces, keeping the text's length. */
export function maskBalances(text: string): string {
  return balanceClauses.reduce((t, re) => t.replace(re, (m) => ' '.repeat(m.length)), text);
}

function findBalance(text: string): number | null {
  for (const m of text.matchAll(amountRegexG)) if (isBalance(text, m)) return parseAmountToMinor(m[1]);
  return null;
}

function isBalance(text: string, m: RegExpMatchArray): boolean {
  const at = m.index ?? 0;
  return balanceHint.test(text.substring(Math.max(0, at - 25), at));
}

function detectType(text: string): TxnType | null {
  const d = debitWords.exec(text)?.index;
  const c = creditWords.exec(text)?.index;
  if (d == null && c == null) return null;
  if (c == null) return 'DEBIT';
  if (d == null) return 'CREDIT';
  return d <= c ? 'DEBIT' : 'CREDIT';
}

function extractMerchant(text: string, type: TxnType): string | null {
  const v = vpaRegex.exec(text);
  if (v) return cleanVpa(v[1]);
  const ordered = type === 'DEBIT' ? [atRegex, toRegex, infoRegex] : [fromRegex, infoRegex, atRegex];
  const n = upiNarration.exec(text);
  if (n) { const c = cleanName(n[1]); if (c != null) return c; }
  const u = upiDash.exec(text);
  if (u) { const c = cleanName(u[1]); if (c != null) return c; }
  if (type === 'DEBIT') {
    const o = toOwnAccount.exec(text);
    if (o) return 'A/c ••' + o[1];
    const p = iciciPayee.exec(text);
    if (p) { const c = cleanName(p[1]); if (c != null) return c; }
  }
  for (const re of ordered) {
    for (const m of text.matchAll(re)) {
      const cleaned = cleanName(m[1]);
      if (cleaned == null) continue;
      if ((cleaned.match(/\//g) ?? []).length >= 2) return Narration.merchant(cleaned) ?? cleaned;
      return cleaned;
    }
  }
  const any = anyVpa.exec(text);
  if (any) return cleanVpa(any[1]);
  return null;
}

function cleanVpa(vpa: string): string {
  const at = vpa.indexOf('@');
  const handle = at < 0 ? vpa : vpa.slice(0, at);
  return /^\d*$/.test(handle) ? vpa.toLowerCase() : handle.toLowerCase();
}

function cleanName(raw: string): string | null {
  let name = raw.trim().replace(/[.\-/ ]+$/, '');
  if (name.includes('@')) return cleanVpa(name);
  name = name.replace(/\s+/g, ' ');
  if (name.length < 2) return null;
  if (junkMerchant.test(name)) return null;
  if (junkMerchant.test(name.split(' ')[0])) return null;
  if (amountRegex.test(name)) return null;
  return name;
}

/** The longest part of a DLT sender id ("VM-HDFCBK" → "HDFCBK"). */
function senderCore(sender: string): string | null {
  const parts = sender.toUpperCase().split('-');
  if (parts.length === 0) return null;
  return parts.reduce((a, p) => (p.length > a.length ? p : a), parts[0]);
}

export function bankFromSender(sender: string): string | null {
  const core = senderCore(sender);
  if (core == null || /^[\d+]*$/.test(core)) return null;
  return bankCodes.find(([k]) => core.includes(k))?.[1] ?? null;
}

function bankFromBody(text: string): string | null {
  const upper = text.toUpperCase();
  return bankBodyRes.find(([re]) => re.test(upper))?.[1] ?? null;
}

/** The key Android stores for an SMS: the sender's core and a hash of the message. */
export function smsKey(sender: string, body: string): string {
  return `${senderCore(sender) ?? sender}|${sha1Hex(body.trim())}`;
}

// ---- SmsDedupe ----

export interface SeenSms { amountMinor: number; type: TxnType; timestamp: number; reference: string | null; bank: string | null }

export const wallets = new Set(['Paytm', 'PhonePe', 'Amazon Pay', 'MobiKwik', 'Freecharge']);
const REF_WINDOW_MS = 3 * 24 * 60 * 60 * 1000;
const WALLET_WINDOW_MS = 5 * 60 * 1000;

function ref(r: string | null): string | null {
  const t = r?.trim();
  return t != null && t.length >= 8 ? t.toLowerCase() : null;
}

/** The stored SMS entry [n] repeats (a bank's alert and a wallet's message for one payment), or null. */
export function duplicateOf<T extends SeenSms>(n: SeenSms, existing: T[]): T | null {
  const nr = ref(n.reference);
  const same = existing.filter((e) => e.amountMinor === n.amountMinor && e.type === n.type);
  if (nr != null) {
    const hit = same.find((e) => ref(e.reference) === nr && Math.abs(e.timestamp - n.timestamp) <= REF_WINDOW_MS);
    if (hit) return hit;
  }
  const newWallet = n.bank != null && wallets.has(n.bank);
  return same.find((e) => {
    const oneWallet = newWallet !== (e.bank != null && wallets.has(e.bank));
    const er = ref(e.reference);
    const distinct = nr != null && er != null && er !== nr;
    return oneWallet && !distinct && Math.abs(e.timestamp - n.timestamp) <= WALLET_WINDOW_MS;
  }) ?? null;
}
