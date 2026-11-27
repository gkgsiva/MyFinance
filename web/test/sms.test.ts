import { describe, expect, it } from 'vitest';
import { LocalDate } from '../src/core/dates';
import { duplicateOf, parseSms, smsKey } from '../src/core/smsParser';
import { lineForSms } from '../src/core/statementImport';
import { splitSms } from '../src/core/smsSplit';

const ts = LocalDate.of(2026, 11, 20)!.atTime(10, 0);

describe('SMS reader (port of SmsParser.kt)', () => {
  it('reads a UPI debit with balance', () => {
    const p = parseSms('VM-ICICIT', 'ICICI Bank Acct XX234 debited for Rs 450.00 on 20-Nov-26; SWIGGY credited. UPI:431234567890. Call 18002662 for dispute. Avl Bal Rs 12,345.67', ts)!;
    expect(p.amountMinor).toBe(45000);
    expect(p.type).toBe('DEBIT');
    expect(p.merchant).toBe('SWIGGY');
    expect(p.accountTail).toBe('234');
    expect(p.bank).toBe('ICICI Bank');
    expect(p.reference).toBe('431234567890');
  });
  it('reads a card spend and a credit', () => {
    const c = parseSms('AD-HDFCBK', 'Spent Rs 1,299 on HDFC Bank Card x1234 at AMAZON on 2026-11-05', ts)!;
    expect([c.amountMinor, c.type, c.merchant, c.accountTail]).toEqual([129900, 'DEBIT', 'AMAZON', '1234']);
    const s = parseSms('', 'Your A/c XX9876 is credited with INR 50,000.00 on 01-11-26 by NEFT from ACME CORP', ts)!;
    expect([s.amountMinor, s.type, s.accountTail]).toEqual([5000000, 'CREDIT', '9876']);
    expect(s.merchant).toBe('ACME CORP');
  });
  it('reads SBI amounts without a currency and converts foreign spends', () => {
    const p = parseSms('', 'Dear UPI user A/C X9876 debited by 1,499.00 on date 20Nov26 trf to RAVI KUMAR Refno 431299991111', ts)!;
    expect(p.amountMinor).toBe(149900);
    expect(p.merchant).toBe('RAVI KUMAR');
    const f = parseSms('', 'Spent USD 12.99 on ICICI Bank Card XX1234 at NETFLIX.COM', ts)!;
    expect(f.amountMinor).toBe(114312);
    expect(f.foreign).toEqual({ code: 'USD', amountMinor: 1299 });
  });
  it('leaves out OTPs, offers, failures and reminders', () => {
    for (const t of [
      '123456 is your OTP for txn of Rs 500 at AMAZON. Do not share.',
      'Get flat Rs 500 cashback on your next order! Shop now',
      'Your payment of Rs 300 failed. Money will not be debited.',
      'Rs 2,000 will be debited from your a/c XX1234 on 25-Nov for SIP',
    ]) expect(parseSms('', t, ts)).toBeNull();
  });
  it('keys a message as Android does', () => {
    expect(smsKey('VM-ICICIT', ' hello ')).toBe('ICICIT|aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d');
  });
  it('finds a wallet message repeating a bank alert', () => {
    const bank = { amountMinor: 25000, type: 'DEBIT' as const, timestamp: ts, reference: null, bank: 'HDFC Bank' };
    expect(duplicateOf({ ...bank, timestamp: ts + 60_000, bank: 'PhonePe' }, [bank])).toBe(bank);
    expect(duplicateOf({ ...bank, timestamp: ts + 60_000, bank: 'SBI' }, [bank])).toBeNull();
  });
});

describe('SMS and statement lines', () => {
  const sms = { amountMinor: 45000, type: 'DEBIT' as const, timestamp: ts, accountTail: '234', reference: '431234567890', key: 'x', id: 0, fromStatement: false, text: 'UPI' };
  const line = (p: object) => ({ ...sms, accountTail: '1234', reference: null, key: 'STMT|a', id: 1, fromStatement: true, text: 'Statement: UPI/431234567890/SWIGGY', ...p });
  it('matches by reference, then account and day', () => {
    expect(lineForSms(sms, [line({ reference: '431234567890' })])).toBe(0);
    expect(lineForSms({ ...sms, reference: null }, [line({ accountTail: '5678' }), line({ timestamp: ts + 86_400_000 })])).toBe(1);
    expect(lineForSms({ ...sms, reference: null }, [line({ timestamp: ts + 5 * 86_400_000 })])).toBeNull();
  });
});

describe('Splitting pasted text and files', () => {
  const now = LocalDate.of(2026, 11, 27)!.atTime(9, 0);
  it('splits on blank lines and dates from the message', () => {
    const r = splitSms('Rs 450.00 debited from A/c XX1234 on 20-11-26 to VPA swiggy@icici\n\nSpent Rs 99 on card XX9876 at ZOMATO', now);
    expect(r.length).toBe(2);
    expect(LocalDate.fromMillis(r[0].timestamp).toString()).toBe('2026-11-20');
    expect(r[0].datedBy).toBe('MESSAGE');
    expect(r[1].datedBy).toBe('NONE');
    expect(r[1].timestamp).toBe(now);
  });
  it('uses date lines and senders from an exported conversation', () => {
    const r = splitSms([
      'VM-ICICIT',
      '25 Nov 2026 at 10:15 PM',
      'ICICI Bank Acct XX234 debited for Rs 450.00; SWIGGY credited. UPI:431234567890.',
      'Yesterday 8:05 AM',
      'Dear Customer, Acct XX234 is credited with Rs 1,000.00 from ASHA. UPI:431234567891.',
      'Dear Customer, Acct XX234 debited for Rs 20.00; TEA credited. UPI:431234567892.',
    ].join('\n'), now);
    expect(r.length).toBe(3);
    expect(r[0].sender).toBe('VM-ICICIT');
    expect(new Date(r[0].timestamp).getHours()).toBe(22);
    expect(LocalDate.fromMillis(r[0].timestamp).toString()).toBe('2026-11-25');
    expect(LocalDate.fromMillis(r[1].timestamp).toString()).toBe('2026-11-26');
    expect(new Date(r[1].timestamp).getHours()).toBe(8);
    expect(LocalDate.fromMillis(r[2].timestamp).toString()).toBe('2026-11-26');
  });
});

// The cases of the Android app's SmsParserTest.kt, so both apps read a message the same way.
describe('Android SmsParserTest cases', () => {
  const p = (s: string, b: string) => parseSms(s, b, 1_700_000_000_000);
  it('reads the same fields', () => {
    const cases: [string, string, Record<string, unknown>][] = [
      ['VM-HDFCBK', 'Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 05/11/24 Ref 431012345678 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808',
        { amountMinor: 25000, type: 'DEBIT', accountTail: '1234', merchant: 'SWIGGY', bank: 'HDFC Bank', reference: '431012345678' }],
      ['AD-SBIINB-S', 'Dear UPI user A/C X5678 debited by 1,499.00 on date 04Nov24 trf to ZEPTO Refno 430912345678. If not u? call 1800111109. -SBI',
        { amountMinor: 149900, type: 'DEBIT', accountTail: '5678', merchant: 'ZEPTO', bank: 'SBI' }],
      ['JD-HDFCBK', 'Spent Rs 1,299.00 on HDFC Bank Card x4321 at AMAZON PAY INDIA on 2024-11-05:10:22:31. Avl Lmt: Rs 85,000.00. Not you? Call 18002586161',
        { amountMinor: 129900, type: 'DEBIT', accountTail: '4321', merchant: 'AMAZON PAY INDIA', balanceMinor: 8500000 }],
      ['VK-ICICIB', 'Dear Customer, your Acct XX9012 is credited with INR 85,000.00 on 01-Nov-24 by NEFT from ACME TECHNOLOGIES PVT LTD. Avl Bal INR 1,23,456.78',
        { amountMinor: 8500000, type: 'CREDIT', accountTail: '9012', merchant: 'ACME TECHNOLOGIES PVT LTD', bank: 'ICICI Bank', balanceMinor: 12345678 }],
      ['AX-AXISBK', 'INR 120.00 debited from A/c no. XX3456 on 06-11-24 for UPI/P2M/431112223334/VPA blinkit.payu@hdfcbank. Not you? SMS BLOCKUPI',
        { amountMinor: 12000, type: 'DEBIT', accountTail: '3456', merchant: 'blinkit.payu', bank: 'Axis Bank' }],
      ['BZ-KOTAKB', 'Received Rs.2000.00 in your Kotak Bank a/c XX7788 from RAHUL KUMAR on 07-11-24.UPI Ref:431234560000.',
        { amountMinor: 200000, type: 'CREDIT', accountTail: '7788', merchant: 'RAHUL KUMAR', bank: 'Kotak Bank' }],
      ['VM-SBIINB', 'Rs.5000 withdrawn at SBI ATM S1BW000123 from A/cX1111 on 03Nov24 Txn# 1234. Avl Bal Rs.20000.00',
        { amountMinor: 500000, type: 'DEBIT', balanceMinor: 2000000 }],
    ];
    for (const [s, b, want] of cases) expect(p(s, b)).toMatchObject(want);
  });
  it('leaves out the same messages', () => {
    for (const [s, b] of [
      ['VM-HDFCBK', 'OTP for txn of Rs 2,000.00 at AMAZON on card x4321 is 123456. Valid for 5 mins. Do not share.'],
      ['VM-HDFCBK', 'Rs 999.00 will be debited from your a/c XX1234 on 10-11-24 towards Netflix autopay mandate.'],
      ['VM-HDFCBK', 'Get a pre-approved personal loan of Rs 5,00,000 at 10.5% p.a. Click here'],
      ['VM-ICICIB', 'Txn of Rs 500.00 on ICICI Card XX1111 at FLIPKART was declined due to insufficient limit.'],
      ['VM-HDFCBK', 'Your HDFC Bank statement for October is ready. Login to netbanking to view.'],
    ]) expect(p(s, b)).toBeNull();
  });
});
