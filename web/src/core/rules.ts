// Port of Categorizer.kt, OpenSourceRules.kt, Spending.kt, SelfTransfers.kt, Currency.kt (Fx.find), PaymentMode.kt,
// Labels.sameSeller, Duplicates.kt, Reversals.kt and SalaryDetect.kt.

import { LocalDate, YearMonth } from './dates';
import { PayeeNames } from './statementParser';
import { TxnType, countLetters, digitsOnly, escapeRegex, parseAmountToMinor } from './text';

export const Cat = {
  FOOD: 'Food & Dining',
  GROCERIES: 'Groceries',
  SHOPPING: 'Shopping',
  TRANSPORT: 'Transport',
  FUEL: 'Fuel',
  BILLS: 'Bills & Utilities',
  ENTERTAINMENT: 'Entertainment',
  HEALTH: 'Health',
  TRAVEL: 'Travel',
  RENT: 'Rent & Housing',
  EDUCATION: 'Education',
  INVESTMENTS: 'Investments',
  EMI: 'Loans & EMI',
  CASH: 'Cash Withdrawal',
  TRANSFERS: 'Transfers',
  SALARY: 'Salary',
  INCOME: 'Other Income',
  OTHER: 'Uncategorized',
} as const;

export const DEFAULT_CATEGORIES: string[] = Object.values(Cat);

/** Normalises a merchant name so "SWIGGY ", "Swiggy" and "swiggy" share one rule. */
export function merchantKey(merchant: string): string {
  return merchant.toLowerCase().replace(/[^a-z0-9@]+/g, ' ').trim();
}

const openSourceRules: Record<string, string[]> = {
  [Cat.FOOD]: ['dominos', 'pizza hut', 'burger king', 'barbeque', 'subway', 'chai point', 'faasos', 'behrouz biryani', 'ovenstory', 'freshmenu', 'box8', 'uber eats'],
  [Cat.GROCERIES]: ['jiomart', 'nature basket', 'milkbasket', 'country delight', 'dunzo', 'licious', 'fresh to home', 'freshtohome', 'otipy'],
  [Cat.TRANSPORT]: ['yulu', 'zoomcar', 'metro parking', 'toll'],
  [Cat.FUEL]: ['hp petrol', 'shell fuel'],
  [Cat.SHOPPING]: ['zudio', 'vijay sales', 'amzn', 'ajio'],
  [Cat.BILLS]: ['adani electricity', 'mahadiscom', 'mgl', 'indane', 'bharat gas', 'act broadband', 'excitel', 'tatasky', 'tatapower', 'utilities'],
  [Cat.ENTERTAINMENT]: ['sony liv', 'youtube premium', 'jiosaavn'],
  [Cat.HEALTH]: ['apollo pharmacy', 'tata 1mg', 'dr lal pathlabs', 'lal pathlabs', 'thyrocare', 'metropolis labs', 'healthcare'],
  [Cat.TRAVEL]: ['easemytrip', 'akasa air', 'irctc', 'redbus'],
  [Cat.EDUCATION]: ['physicswallah', 'byjus', 'vedantu', 'aakash institute'],
};

const baseRules: [string, string[]][] = [
  [Cat.FOOD, ['swiggy', 'zomato', 'eatsure', 'domino', 'pizza', 'mcdonald', 'kfc', 'burger', 'starbucks', 'cafe', 'restaurant', 'hotel saravana', 'chaayos', 'haldiram', 'biryani', 'bakery', 'eatclub']],
  [Cat.GROCERIES, ['bigbasket', 'blinkit', 'zepto', 'instamart', 'dmart', 'grofers', 'jiomart', 'more retail', 'reliance fresh', 'nature basket', 'spencer', 'supermarket', 'kirana', 'milkbasket', 'country delight']],
  [Cat.FUEL, ['petrol', 'fuel', 'hpcl', 'bpcl', 'iocl', 'indian oil', 'bharat petroleum', 'hp pay', 'shell', 'nayara']],
  [Cat.TRANSPORT, ['uber', 'ola', 'rapido', 'metro', 'irctc', 'redbus', 'fastag', 'parking', 'namma yatri', 'blusmart']],
  [Cat.TRAVEL, ['makemytrip', 'goibibo', 'cleartrip', 'indigo', 'air india', 'vistara', 'akasa', 'spicejet', 'oyo', 'airbnb', 'yatra', 'ixigo', 'booking.com', 'agoda']],
  [Cat.SHOPPING, ['amazon', 'flipkart', 'myntra', 'ajio', 'meesho', 'nykaa', 'tata cliq', 'croma', 'reliance digital', 'decathlon', 'ikea', 'lifestyle', 'westside', 'pantaloons', 'zara', 'h&m', 'uniqlo', 'lenskart']],
  [Cat.BILLS, ['electricity', 'bescom', 'tneb', 'msedcl', 'tata power', 'adani', 'airtel', 'jio', 'vodafone', ' vi ', 'bsnl', 'act fibernet', 'broadband', 'dth', 'tata play', 'gas', 'water bill', 'recharge', 'bill payment', 'billdesk', 'postpaid', 'insurance', 'lic']],
  [Cat.ENTERTAINMENT, ['netflix', 'hotstar', 'prime video', 'spotify', 'bookmyshow', 'pvr', 'inox', 'youtube', 'sonyliv', 'zee5', 'apple.com', 'google play', 'steam', 'playstation']],
  [Cat.HEALTH, ['pharmacy', 'apollo', 'medplus', 'pharmeasy', 'netmeds', '1mg', 'hospital', 'clinic', 'diagnostic', 'practo', 'cult.fit', 'cultfit', 'gym', 'dental']],
  [Cat.RENT, ['rent', 'nobroker', 'society', 'maintenance', 'housing']],
  [Cat.EDUCATION, ['school', 'college', 'university', 'udemy', 'coursera', 'byju', 'unacademy', 'tuition', 'fees']],
  [Cat.INVESTMENTS, [
    'zerodha', 'groww', 'upstox', 'kuvera', 'coin by', 'mutual fund', 'mutualfund', 'sip', 'nps', 'ppf', 'smallcase',
    'indmoney', 'angel one', 'icici direct', 'hdfc securities',
    'cams', 'camsonline', 'kfintech', 'kfin tech', 'bse star', 'bsestarmf', 'nse clearing', 'indian clearing', 'iccl',
    'paytm money', 'et money', 'etmoney', 'dhan', '5paisa', 'motilal oswal', 'sharekhan', 'kotak securities',
    'axis securities', 'sbicap', 'sbi securities', 'fyers', 'scripbox', 'fundsindia', 'mf utilities', 'mfcentral',
    'fixed deposit', 'recurring deposit', 'term deposit', 'fd booked', 'fd booking', 'fd opened', 'rd instal',
    'national pension', 'nps trust', 'sukanya', 'sovereign gold', 'elss', 'systematic investment',
  ]],
  [Cat.EMI, ['emi', 'loan', 'bajaj finance', 'home credit', 'credit card bill', 'cc payment']],
];

const keywordRules: [string, RegExp[]][] = (() => {
  const merged: [string, string[]][] = baseRules.map(([c, w]) => [c, [...new Set([...w, ...(openSourceRules[c] ?? [])])]]);
  for (const [k, v] of Object.entries(openSourceRules)) if (!merged.some((m) => m[0] === k)) merged.push([k, v]);
  return merged.map(([c, words]) => [c, words.map((w) => {
    const tail = w.trim().length <= 4 ? '(?![a-z])' : '';
    return new RegExp(`(?<![a-z])${escapeRegex(w)}${tail}`);
  })]);
})();

/** Assigns a category: the user's merchant corrections first, then the built-in keyword rules. */
export function categorize(merchant: string | null, type: TxnType, smsBody: string | null, userRules: Map<string, string>): string {
  if (merchant != null) {
    const r = userRules.get(merchantKey(merchant));
    if (r) return r;
  }
  const hay = [merchant, smsBody].filter((x) => x != null).join(' ').toLowerCase();
  if (type === 'CREDIT') {
    if (/\b(salary|sal\b|payroll)/.test(hay)) return Cat.SALARY;
    if (/\b(refund|reversal|reversed|cashback)/.test(hay)) return Cat.INCOME;
    if (/\b(interest|dividend)/.test(hay)) return Cat.INCOME;
  }
  for (const [cat, res] of keywordRules) if (res.some((re) => re.test(hay))) return cat;
  if (/\b(atm|cash withdrawal|cash wdl|withdrawn at)/.test(hay)) return Cat.CASH;
  if (/\b(neft|imps|rtgs)\b/.test(hay)) return Cat.TRANSFERS;
  return type === 'CREDIT' ? Cat.INCOME : Cat.OTHER;
}

// ---- Spending ----

const investWords = /(?<![a-z])(invest\w*|sips?|mutual funds?|mf|stocks?|shares?|equity|ppf|nps|fds?|rds?|fixed deposits?|recurring deposits?|term deposits?|sgb|gold bonds?|elss|demat|savings? plan)(?![a-z])/;

export function isInvestmentCategory(category: string | null | undefined): boolean {
  if (!category || category.trim() === '') return false;
  if (category === Cat.INVESTMENTS) return true;
  return investWords.test(category.toLowerCase());
}

export function isInvestment(type: TxnType, category: string): boolean {
  return type === 'DEBIT' && isInvestmentCategory(category);
}

export function isSpend(type: TxnType, category: string): boolean {
  return type === 'DEBIT' && !isInvestmentCategory(category);
}

// ---- SelfTransfers ----

const cardPaymentRe =
  /credit\s*card|card\s*payment|cc\s*payment|\bcc\s*bill|card\s*bill|\bcred\b|cred\s*club|billdesk.*card|payment\s+(?:of\s+.{0,40})?(?:has\s+been\s+)?received.{0,60}card|card.{0,40}payment\s+received|money\s+received.{0,60}card|card.{0,60}money\s+received/i;
const selfWords = /\bself\b|own\s+a\/?c|\bneft\b|\bimps\b|\brtgs\b|\bupi\b|transfer|trf|\bft\b|sweep/i;
const cardSpend = /\b(spent|used|purchase|txn|transaction|debited|upi)\b.{0,80}\bcredit\s*card|credit\s*card.{0,80}\b(spent|used|purchase|txn|transaction|debited|upi)\b/i;
const billWords =
  /payment\s+(?:of\s+.{0,20})?received|paid\s+towards|\btowards\b|bill\s*(?:payment|paid|pay)|card\s*bill|cc\s*(?:bill|payment)|\bcred\b|billdesk|payment\s+(?:to|for)\s+.{0,30}credit\s*card|card\s*payment|credit\s*card\s*(?:a\/?c\s*)?(?:no\.?\s*)?[x*•]*\d{3,4}\s+(?:is\s+|has\s+been\s+)?credited/i;
const cardSideWords = /payment|money\s+received|thank\s*you|\bbbps\b|autopay|auto\s*debit|\bcred\b|billdesk|received\s+(?:on|towards|in)\s+your/i;
const payWords = /payment|\bpay\b|billpay|bill\s*pay|\bbbps\b|\bcred\b|autopay|auto\s*debit|\bsi\b|\bneft\b|\bimps\b|\bupi\b|\bach\b|transfer|\btrf\b|money\s+received|received|credited|thank\s*you|\bcard\b/i;
const ownTransferWords = /\binft\b|\binf\/|\bmmt\/|fund\s*transfer|\bto\s+self\b|\bsweep|\bown\s+a\/?c/i;
const cardStatementPayment = /payment|thank\s*you|autopay|auto\s*debit|\bbbps\b|\bneft\b|\bimps\b|\brtgs\b|\bupi\b|\bcred\b|billdesk/i;
const notPayment = /refund|reversal|reversed|cashback|cash\s*back|reward|surcharge\s*waiver|waiver|\bemi\b/i;

export interface PayRow { id: number; type: TxnType; amountMinor: number; timestamp: number; tail: string | null; text: string | null; edited: boolean }

const DAY = 86_400_000;

export const SelfTransfers = {
  isCardPayment(merchant: string | null, text: string | null): boolean {
    const texts = [merchant, text].filter((x): x is string => x != null);
    if (!texts.some((t) => cardPaymentRe.test(t))) return false;
    const spend = texts.some((t) => cardSpend.test(t));
    return !spend || texts.some((t) => billWords.test(t));
  },
  looksLikeTransfer(text: string | null): boolean {
    return text != null && (selfWords.test(text) || ownTransferWords.test(text));
  },
  isCardBillPayment(type: TxnType, merchant: string | null, text: string | null, cardStatement = false): boolean {
    if (SelfTransfers.isCardPayment(merchant, text)) return true;
    if (!cardStatement || type !== 'CREDIT') return false;
    const texts = [merchant, text].filter((x): x is string => x != null);
    return texts.some((t) => cardStatementPayment.test(t)) && !texts.some((t) => notPayment.test(t));
  },
  /** A card bill paid from a bank account, seen from both sides. Returns (debit id, credit id) pairs, closest first. */
  cardPaymentPairs(rows: PayRow[], cardTails: Set<string>, days = 4): [number, number][] {
    const tail = (r: PayRow) => (r.tail != null ? digitsOnly(r.tail).slice(-4) : null);
    const credits = new Map<number, PayRow[]>();
    for (const r of rows) {
      if (r.type === 'CREDIT' && !r.edited && cardTails.has(tail(r) ?? '') && r.amountMinor > 0) {
        const a = credits.get(r.amountMinor);
        if (a) a.push(r); else credits.set(r.amountMinor, [r]);
      }
    }
    const debits = rows.filter((r) => r.type === 'DEBIT' && !r.edited && !cardTails.has(tail(r) ?? '\u0000') && r.amountMinor > 0);
    const cands: [PayRow, PayRow, number][] = [];
    for (const d of debits) for (const c of credits.get(d.amountMinor) ?? []) {
      const gap = Math.abs(c.timestamp - d.timestamp);
      if (gap > days * DAY) continue;
      const ct = tail(c);
      const ok = (c.text != null && cardSideWords.test(c.text)) ||
        (d.text != null && payWords.test(d.text) && ct != null && d.text.includes(ct));
      if (ok && !(c.text != null && notPayment.test(c.text))) cands.push([d, c, gap]);
    }
    const used = new Set<number>();
    const out: [number, number][] = [];
    for (const [d, c] of cands.sort((a, b) => a[2] - b[2])) {
      if (used.has(d.id) || used.has(c.id)) continue;
      used.add(d.id); used.add(c.id);
      out.push([d.id, c.id]);
    }
    return out;
  },
};

// ---- Fx ----

const fxCodes = ['USD', 'EUR', 'GBP', 'AED', 'SGD', 'THB', 'MYR', 'JPY', 'AUD', 'CAD', 'CHF', 'HKD', 'SAR', 'QAR', 'LKR', 'NPR', 'NZD', 'IDR', 'VND'];
const codeAmount = new RegExp(String.raw`\b(${fxCodes.join('|')})\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)\b`);
const symbolAmount = /(US\$|\$|€|£)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)/;

export interface ForeignAmount { code: string; amountMinor: number }

export function fxFind(text: string): [ForeignAmount, number] | null {
  const hits: [ForeignAmount, number][] = [];
  const a = codeAmount.exec(text);
  if (a) { const v = parseAmountToMinor(a[2]); if (v != null) hits.push([{ code: a[1].toUpperCase(), amountMinor: v }, a.index]); }
  const b = symbolAmount.exec(text);
  if (b) {
    const code = b[1] === '€' ? 'EUR' : b[1] === '£' ? 'GBP' : 'USD';
    const v = parseAmountToMinor(b[2]);
    if (v != null) hits.push([{ code, amountMinor: v }, b.index]);
  }
  if (hits.length === 0) return null;
  hits.sort((x, y) => x[1] - y[1]);
  return hits[0][0].amountMinor > 0 ? hits[0] : null;
}

export function sameForeign(a: ForeignAmount, b: ForeignAmount): boolean {
  return a.code === b.code && a.amountMinor === b.amountMinor;
}

// ---- PaymentMode ----

export type PaymentMode = 'UPI' | 'CARD' | 'CASH' | 'NETBANKING' | 'OTHER';
export const modeLabel: Record<PaymentMode, string> = { UPI: 'UPI', CARD: 'Card', CASH: 'Cash', NETBANKING: 'Net banking', OTHER: 'Other' };
const upiMode = /\bupi\b|\bvpa\b|@[a-z]{2,}\b|\bgpay\b|\bphonepe\b|\bpaytm\b|\bbhim\b/i;
const cardMode = /\bcard\b|\bpos\b|\bswipe/i;
const cashMode = /\batm\b|cash\s+(?:withdrawal|wdl)|withdrawn\s+at|\bcash\b/i;
const netMode = /\bneft\b|\bimps\b|\brtgs\b|net\s*banking|\bnetbanking\b|\bnach\b|\bach\b|\becs\b|auto\s*debit|standing\s+instruction|\bbill\s*desk\b|\bbillpay\b/i;

export function detectMode(text: string | null, manual: boolean, category: string | null): PaymentMode {
  if (text == null || text.trim() === '') return category === Cat.CASH || manual ? 'CASH' : 'OTHER';
  if (cashMode.test(text) && !upiMode.test(text)) return 'CASH';
  if (upiMode.test(text)) return 'UPI';
  if (cardMode.test(text)) return 'CARD';
  if (netMode.test(text)) return 'NETBANKING';
  return 'OTHER';
}

// ---- Labels.sameSeller ----

const sellerGeneric = new Set([
  'upi', 'pay', 'payment', 'payments', 'refund', 'refunds', 'reversal', 'cashback', 'cancel', 'cancelled', 'from', 'to', 'for',
  'the', 'pvt', 'ltd', 'private', 'limited', 'india', 'bank', 'transfer', 'imps', 'neft', 'rtgs', 'credit', 'debit', 'online', 'services',
]);

export function sellerWord(merchant: string): string | null {
  return merchantKey(merchant).split(' ').find((w) => w.length >= 4 && !sellerGeneric.has(w) && !/^\d+$/.test(w)) ?? null;
}

export function sameSeller(a: string, b: string): boolean {
  if (merchantKey(a) === merchantKey(b)) return true;
  const wa = sellerWord(a);
  if (wa == null) return false;
  return wa === sellerWord(b);
}

// ---- CheckRow, used by duplicates, reversals and transfers ----

export interface CheckRow {
  id: number;
  uid: string;
  type: TxnType;
  amountMinor: number;
  account: string | null;
  timestamp: number;
  merchant: string | null;
  text: string | null;
  category: string;
  owner: string | null;
  manual: boolean;
  statement: boolean;
  reference: string | null;
  edited: boolean;
}

// ---- Duplicates ----

const MINUTE = 60_000;
const ID_WINDOW = 3 * DAY;
const feeLine = /\b(IGST|CGST|SGST|GST|DCC\s+Fee|Markup|Mark-up|Fuel\s+Surcharge|Interest\s+Amount|Principal\s+Amount|Late\s+Pay|Finance\s+Charge|Service\s+Tax|Cess)\b/i;

export interface DuplicateGroup { rows: CheckRow[]; keep: CheckRow; sameId: boolean; key: string }

export const Duplicates = {
  id(reference: string | null | undefined): string | null {
    if (reference == null) return null;
    const r = reference.trim().toUpperCase().replace(/\s+/g, '');
    if (r.length < 8 || (r.match(/\d/g) ?? []).length < 6) return null;
    if ([...r].every((c) => c === r[0])) return null;
    return r;
  },
  sameId(a: string | null, b: string | null): boolean {
    const x = Duplicates.id(a);
    const y = Duplicates.id(b);
    if (x == null || y == null) return false;
    if (x === y) return true;
    const [s, l] = x.length <= y.length ? [x, y] : [y, x];
    return s.length >= 10 && l.endsWith(s);
  },
  same(a: CheckRow, b: CheckRow, sameLine: (a: string, b: string | null) => boolean): boolean {
    if (a.type !== b.type || a.amountMinor !== b.amountMinor || a.uid === b.uid) return false;
    const gap = Math.abs(a.timestamp - b.timestamp);
    if (Duplicates.sameId(a.reference, b.reference)) return gap <= ID_WINDOW;
    const x = Duplicates.id(a.reference);
    const y = Duplicates.id(b.reference);
    if (x != null && y != null && x.length === y.length && !Duplicates.sameId(x, y)) return false;
    if (gap > DAY) return false;
    if (!dupSameAccount(a.account, b.account)) return false;
    if (a.owner !== b.owner && !(a.manual || b.manual)) return false;
    const sameDay = LocalDate.fromMillis(a.timestamp).equals(LocalDate.fromMillis(b.timestamp));
    if (a.statement && b.statement) return sameDay && sameLine(a.text ?? '', b.text) && !feeLine.test(a.text ?? '');
    return gap <= 10 * MINUTE || ((a.manual || b.manual) && sameDay);
  },
  keeper(rows: CheckRow[]): CheckRow {
    const score = (r: CheckRow): (number)[] => [
      r.edited ? 0 : 1, r.statement ? 0 : 1, !r.manual ? 0 : 1, Duplicates.id(r.reference) != null ? 0 : 1,
      r.category !== Cat.OTHER ? 0 : 1, -(r.text?.length ?? 0), r.id,
    ];
    return [...rows].sort((a, b) => {
      const x = score(a), y = score(b);
      for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return x[i] - y[i];
      return 0;
    })[0];
  },
  pairKey(a: CheckRow, b: CheckRow): string {
    return 'dup:' + [a.uid, b.uid].sort().join('+');
  },
  find(rows: CheckRow[], dismissed: Set<string>, sameLine: (a: string, b: string | null) => boolean): DuplicateGroup[] {
    const sorted = [...rows].sort((a, b) => a.timestamp - b.timestamp);
    const parent = sorted.map((_, i) => i);
    const root = (i: number) => { let x = i; while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; };
    const groupId: (string | null)[] = sorted.map((r) => Duplicates.id(r.reference));
    const members = new Map<number, number[]>();
    const membersOf = (r: number) => { let m = members.get(r); if (!m) { m = [r]; members.set(r, m); } return m; };
    const byAmount = new Map<string, number[]>();
    sorted.forEach((r, i) => { const k = `${r.amountMinor}|${r.type}`; const a = byAmount.get(k); if (a) a.push(i); else byAmount.set(k, [i]); });
    for (const idx of byAmount.values()) {
      if (idx.length < 2) continue;
      for (let x = 0; x < idx.length; x++) {
        const a = sorted[idx[x]];
        let y = x + 1;
        while (y < idx.length && sorted[idx[y]].timestamp - a.timestamp <= ID_WINDOW) {
          const b = sorted[idx[y]];
          y++;
          if (!Duplicates.same(a, b, sameLine)) continue;
          if (dismissed.has(Duplicates.pairKey(a, b))) continue;
          const ra = root(idx[x]);
          const rb = root(idx[y - 1]);
          if (ra === rb) continue;
          const ia = groupId[ra];
          const ib = groupId[rb];
          if (ia != null && ib != null && ia.length === ib.length && !Duplicates.sameId(ia, ib)) continue;
          const ma = membersOf(ra);
          const mb = membersOf(rb);
          if (ma.some((p) => mb.some((q) => dismissed.has(Duplicates.pairKey(sorted[p], sorted[q]))))) continue;
          const [big, small] = ma.length >= mb.length ? [ra, rb] : [rb, ra];
          parent[small] = big;
          membersOf(big).push(...membersOf(small));
          members.delete(small);
          groupId[big] = groupId[big] ?? groupId[small];
        }
      }
    }
    return [...members.values()].filter((m) => m.length > 1).map((m) => {
      const list = m.map((i) => sorted[i]).sort((a, b) => a.timestamp - b.timestamp);
      const ids = list.map((r) => Duplicates.id(r.reference));
      const certain = ids.every((i) => i != null) && ids.every((i) => Duplicates.sameId(i, ids[0]));
      return { rows: list, keep: Duplicates.keeper(list), sameId: certain, key: 'dup:' + list.map((r) => r.uid).sort().join('+') };
    }).filter((gp) => !dismissed.has(gp.key)).sort((a, b) => Math.max(...b.rows.map((r) => r.timestamp)) - Math.max(...a.rows.map((r) => r.timestamp)));
  },
};

function sameAccountDigits(a: string, b: string): boolean {
  if (a === b) return true;
  const [s, l] = a.length <= b.length ? [a, b] : [b, a];
  return s.length >= 3 && l.endsWith(s);
}

function dupSameAccount(a: string | null, b: string | null): boolean {
  if (a == null || b == null || a === b) return true;
  const da = digitsOnly(a);
  const db = digitsOnly(b);
  if (da === '' || db === '') return false;
  const bankA = (a.split('••')[0] ?? '').trim();
  const bankB = (b.split('••')[0] ?? '').trim();
  if (bankA !== '' && bankB !== '' && bankA.toLowerCase() !== bankB.toLowerCase()) return false;
  return sameAccountDigits(da, db);
}

// ---- Reversals ----

export type ReversalKind = 'FAILED' | 'REFUND' | 'HOLD';
export const reversalLabel: Record<ReversalKind, string> = {
  FAILED: 'Payment failed, money came back', REFUND: 'Refunded', HOLD: 'Autopay hold released',
};
export interface ReversalPair { debit: CheckRow; credit: CheckRow; kind: ReversalKind; key: string }

const HOUR = 60 * MINUTE;
const reversalWords = /\brevers(?:al|ed|e)\b|\brvsl\b|\brev\b|\bfailed\b|\bunsuccessful\b|\bdeclined\b|\breturned\b|\brefund(?:ed)?\b|\bchargeback\b|credited\s+back|money\s+back|\bcancel(?:led|lation)?\b/i;
const failedWords = /\brevers(?:al|ed|e)\b|\brvsl\b|\brev\b|\bfailed\b|\bunsuccessful\b|\bdeclined\b|\breturned\b|credited\s+back/i;
const mandateWords = /\bmandate\b|\bauto[\s-]?pay\b|\bautopay\b|\bama\b|\bumrn\b|\bblock(?:ed)?\b|\bhold\b|\bunblock(?:ed)?\b|\breleased?\b/i;
const salaryWords = /\bsalary\b|\bsal\b|\bpayroll\b/i;
const neverPaired = new Set<string>([Cat.SALARY, Cat.TRANSFERS, Cat.INVESTMENTS]);

function texts(r: CheckRow): string[] {
  return [r.merchant, r.text].filter((x): x is string => x != null);
}

function revSameAccount(a: string | null, b: string | null): boolean {
  const t = (s: string | null) => { if (s == null) return null; const d = digitsOnly(s); return d.length >= 3 ? d : null; };
  const x = t(a);
  const y = t(b);
  if (x == null || y == null) return true;
  const n = Math.min(x.length, y.length);
  return x.endsWith(y.slice(-n)) && y.endsWith(x.slice(-n));
}

function eligible(r: CheckRow): boolean {
  return !r.manual && r.amountMinor > 0 && !neverPaired.has(r.category) &&
    !texts(r).some((t) => SelfTransfers.isCardPayment(r.merchant, r.text) || salaryWords.test(t));
}

export const Reversals = {
  keyOf: (d: string, c: string) => `rev:${d}+${c}`,
  kindOf(debit: CheckRow, credit: CheckRow): ReversalKind | null {
    if (debit.type !== 'DEBIT' || credit.type !== 'CREDIT') return null;
    if (debit.amountMinor !== credit.amountMinor) return null;
    if (!eligible(debit) || !eligible(credit)) return null;
    if (!revSameAccount(debit.account, credit.account)) return null;
    if (debit.owner !== credit.owner) return null;
    const gap = credit.timestamp - debit.timestamp;
    if (gap < -10 * MINUTE) return null;
    const creditSays = texts(credit).some((t) => reversalWords.test(t));
    if (creditSays && gap <= 10 * DAY) return texts(credit).some((t) => failedWords.test(t)) ? 'FAILED' : 'REFUND';
    const ss = debit.merchant != null && credit.merchant != null && !PayeeNames.isCode(debit.merchant) && sameSeller(debit.merchant, credit.merchant);
    if (ss && gap <= 7 * DAY) return gap <= HOUR ? 'FAILED' : 'REFUND';
    const otherPayee = credit.merchant != null && debit.merchant != null && !ss && !PayeeNames.isCode(credit.merchant);
    if (otherPayee) return null;
    const mandate = [...texts(debit), ...texts(credit)].some((t) => mandateWords.test(t));
    if (mandate && gap <= 3 * DAY) return 'HOLD';
    if (gap <= HOUR) return 'FAILED';
    if (gap <= DAY && debit.amountMinor % 100 !== 0) return 'FAILED';
    return null;
  },
  find(rows: CheckRow[], dismissed: Set<string>): ReversalPair[] {
    const debits = new Map<number, CheckRow[]>();
    for (const r of rows) if (r.type === 'DEBIT') { const a = debits.get(r.amountMinor); if (a) a.push(r); else debits.set(r.amountMinor, [r]); }
    const cands: ReversalPair[] = [];
    for (const c of rows) {
      if (c.type !== 'CREDIT') continue;
      for (const d of debits.get(c.amountMinor) ?? []) {
        const kind = Reversals.kindOf(d, c);
        if (kind == null) continue;
        const key = Reversals.keyOf(d.uid, c.uid);
        if (!dismissed.has(key)) cands.push({ debit: d, credit: c, kind, key });
      }
    }
    const gapOf = (p: ReversalPair) => p.credit.timestamp - p.debit.timestamp;
    cands.sort((a, b) => {
      const x = Math.abs(gapOf(a)) + (gapOf(a) < 0 ? 30 * MINUTE : 0);
      const y = Math.abs(gapOf(b)) + (gapOf(b) < 0 ? 30 * MINUTE : 0);
      return x - y || a.debit.timestamp - b.debit.timestamp;
    });
    const used = new Set<string>();
    const out: ReversalPair[] = [];
    for (const p of cands) {
      if (used.has(p.debit.uid) || used.has(p.credit.uid)) continue;
      used.add(p.debit.uid); used.add(p.credit.uid);
      out.push(p);
    }
    return out.sort((a, b) => b.credit.timestamp - a.credit.timestamp);
  },
};

// ---- SalaryDetect ----

export interface Lite { id: number; type: TxnType; amountMinor: number; timestamp: number; category: string; merchant: string | null; account: string | null }

export interface SalaryGuess {
  fromDay: number;
  payer: string | null;
  ids: number[];
  months: number;
  alreadySalary: boolean;
  typicalDay: number;
  toCategorise: number[];
}

const salaryGeneric = new Set(['neft', 'imps', 'rtgs', 'upi', 'salary', 'sal', 'transfer', 'payment', 'credit', 'by', 'from', 'the', 'mr', 'ms']);
const salaryAliases: Record<string, string> = { rntbci: 'renault', rnaipl: 'renault', rntbc: 'renault' };
const notSalary = /own account|credit card|card bill|card payment|refund|reversal|cashback|interest|dividend/i;
const MIN_SALARY = 10_000_00;

export function salaryPayer(merchant: string | null): string | null {
  if (merchant == null) return null;
  const words = merchantKey(merchant).split(' ').filter((w) => w.trim() !== '');
  const w = words.find((x) => !salaryGeneric.has(x) && countLetters(x) >= 3);
  if (!w) return null;
  return salaryAliases[w] ?? Object.entries(salaryAliases).find(([k]) => w.startsWith(k))?.[1] ?? w;
}

const isSalaryRow = (t: Lite) => t.category.toLowerCase() === Cat.SALARY.toLowerCase();
const ymOf = (t: Lite) => YearMonth.fromMillis(t.timestamp);
const monthsOf = (l: Lite[]) => new Set(l.map((t) => ymOf(t).toString())).size;

export function sameSalary(credits: Lite[]): Lite[] {
  const salary = credits.filter(isSalaryRow);
  if (salary.length < 2) return [];
  const tail3 = (a: string | null) => { if (a == null) return null; const d = digitsOnly(a).slice(-3); return d.length === 3 ? d : null; };
  const payers = new Set(salary.map((t) => salaryPayer(t.merchant)).filter((x): x is string => x != null));
  const accounts = new Set(salary.map((t) => tail3(t.account)).filter((x): x is string => x != null));
  const usual = salary.map((t) => t.amountMinor).sort((a, b) => a - b)[Math.floor(salary.length / 2)];
  const days = salary.filter((t) => t.amountMinor * 2 >= usual).map((t) => LocalDate.fromMillis(t.timestamp).day).sort((a, b) => a - b);
  const usualDay = days[Math.floor(days.length / 2)];
  const others = credits.filter((t) => !isSalaryRow(t) && !(t.merchant != null && notSalary.test(t.merchant)));
  const out = new Set<Lite>();
  for (const t of others) {
    const p = salaryPayer(t.merchant);
    if (p != null && payers.has(p) && t.amountMinor * 10 >= usual * 6 && t.amountMinor <= usual * 3) out.add(t);
  }
  const sorted = [...salary].sort((a, b) => a.timestamp - b.timestamp);
  const nearest = (t: Lite) => sorted.reduce((best, s) => (Math.abs(s.timestamp - t.timestamp) < Math.abs(best.timestamp - t.timestamp) ? s : best), sorted[0]);
  const payMonth = (t: Lite) => {
    const d = LocalDate.fromMillis(t.timestamp);
    return usualDay >= 15 && d.day < usualDay - 15 ? YearMonth.from(d).minusMonths(1) : YearMonth.from(d);
  };
  const nearDay = (t: Lite) => {
    const d = LocalDate.fromMillis(t.timestamp);
    const pm = payMonth(t);
    const target = pm.atDay(Math.min(usualDay, pm.lengthOfMonth()));
    return Math.abs(d.toEpochDay() - target.toEpochDay()) <= 7;
  };
  const paid = new Set([...salary, ...out].filter((t) => t.amountMinor * 2 >= usual).map((t) => payMonth(t).toString()));
  const first = sorted[0].timestamp - 40 * DAY;
  const groups = new Map<string, Lite[]>();
  for (const c of others) {
    if (out.has(c) || c.timestamp < first || c.amountMinor < MIN_SALARY) continue;
    const ct = tail3(c.account);
    if (!(accounts.size === 0 || ct == null || accounts.has(ct))) continue;
    const n = nearest(c).amountMinor;
    if (!(c.amountMinor * 4 >= n * 3 && c.amountMinor * 3 <= n * 4)) continue;
    if (!nearDay(c)) continue;
    const pm = payMonth(c).toString();
    if (paid.has(pm)) continue;
    const a = groups.get(pm);
    if (a) a.push(c); else groups.set(pm, [c]);
  }
  for (const l of groups.values()) {
    const n = nearest(l[0]).amountMinor;
    out.add(l.reduce((b, t) => (Math.abs(t.amountMinor - n) < Math.abs(b.amountMinor - n) ? t : b), l[0]));
  }
  return [...out];
}

export function guessSalary(txns: Lite[], today: LocalDate): SalaryGuess | null {
  const since = today.plusMonths(-13);
  const allCredits = txns.filter((t) => t.type === 'CREDIT');
  const credits = allCredits.filter((t) => LocalDate.fromMillis(t.timestamp).isAfter(since));
  const salary = credits.filter(isSalaryRow);
  let rows: Lite[];
  let already: boolean;
  if (monthsOf(salary) >= 2) {
    rows = [...salary, ...sameSalary(allCredits)];
    already = true;
  } else {
    const byPayer = new Map<string, Lite[]>();
    for (const t of credits) {
      const k = salaryPayer(t.merchant);
      if (t.amountMinor < MIN_SALARY || k == null || t.category === Cat.TRANSFERS) continue;
      const a = byPayer.get(k); if (a) a.push(t); else byPayer.set(k, [t]);
    }
    const ok = [...byPayer.values()].filter((l) => monthsOf(l) >= 3 && Math.max(...l.map((t) => t.amountMinor)) <= 2 * Math.min(...l.map((t) => t.amountMinor)));
    if (ok.length === 0) return null;
    rows = ok.reduce((b, l) => (monthsOf(l) > monthsOf(b) ? l : b), ok[0]);
    already = false;
  }
  const recent = rows.filter((t) => LocalDate.fromMillis(t.timestamp).isAfter(since));
  const byMonth = new Map<string, Lite>();
  for (const t of recent) {
    const k = ymOf(t).toString();
    const b = byMonth.get(k);
    if (!b || t.amountMinor > b.amountMinor) byMonth.set(k, t);
  }
  const days = [...byMonth.values()].map((t) => LocalDate.fromMillis(t.timestamp).day);
  const late = days.filter((d) => d >= 20);
  if (late.length === 0 || late.length * 2 < days.length) return null;
  const fromDay = Math.min(25, Math.max(18, Math.min(...late) - 3));
  const counts = new Map<string, number>();
  for (const t of rows) if (t.merchant) counts.set(t.merchant, (counts.get(t.merchant) ?? 0) + 1);
  let payer: string | null = null;
  let pc = 0;
  for (const [k, v] of counts) if (v > pc) { pc = v; payer = k; }
  const sortedLate = [...late].sort((a, b) => a - b);
  return {
    fromDay, payer, ids: rows.map((t) => t.id), months: monthsOf(recent), alreadySalary: already,
    typicalDay: sortedLate[Math.floor(late.length / 2)], toCategorise: rows.filter((t) => !isSalaryRow(t)).map((t) => t.id),
  };
}
