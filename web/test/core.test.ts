import { describe, expect, it } from 'vitest';
import { fromTable } from '../src/core/statementParser';
import { readDelimited } from '../src/core/spreadsheet';
import { candidates } from '../src/core/statementImport';
import { fromJson, toJson } from '../src/core/statementJson';
import { decode, encode, isExport, newOnly } from '../src/core/familyCsv';
import { buildLedger, MonthCycle } from '../src/core/ledger';
import { YearMonth, LocalDate } from '../src/core/dates';

const CSV = [
  'Statement of account,,,,,',
  'Account Number: XXXXXXXX1234,,,,,',
  'S No.,Value Date,Transaction Date,Transaction Remarks,Withdrawal Amount (INR ),Deposit Amount (INR ),Balance (INR )',
  '1,01/11/2026,01/11/2026,UPI/123456789012/Swiggy/swiggy@icici/Food,450.00,0.00,"10,550.00"',
  '2,02/11/2026,02/11/2026,NEFT-ACME LTD SALARY NOV,0.00,"1,20,000.00","1,30,550.00"',
  '3,03/11/2026,03/11/2026,UPI/223456789012/Amazon/amazon@apl/Shopping,"1,299.50",0.00,"1,29,250.50"',
].join('\n');

describe('statement from a CSV download', () => {
  const st = fromTable(readDelimited(CSV))!;
  it('reads every line with direction and amount', () => {
    expect(st).not.toBeNull();
    expect(st.rows.map((r) => [r.date.toString(), r.amountMinor, r.type])).toEqual([
      ['2026-11-01', 45000, 'DEBIT'], ['2026-11-02', 12000000, 'CREDIT'], ['2026-11-03', 129950, 'DEBIT'],
    ]);
  });
  it('survives the trip through the processed JSON unchanged (same keys as reading the file)', () => {
    const back = fromJson(JSON.parse(JSON.stringify(toJson(st))));
    expect(candidates(back).map((c) => [c.key, c.uid, c.timestamp])).toEqual(candidates(st).map((c) => [c.key, c.uid, c.timestamp]));
  });
});

describe('family file', () => {
  it('encodes and decodes the Android export format, skipping what is already there', () => {
    const row = { uid: 'u1', amountMinor: 12345, type: 'DEBIT' as const, merchant: 'Shop, "Big"', category: 'Shopping', accountTail: '1234', bank: 'ICICI Bank',
      timestamp: Date.UTC(2026, 10, 1, 6), note: null, owner: 'Asha', source: 'STATEMENT', smsKey: 'STMT|abc', reference: null, excluded: false, mode: 'UPI', labels: ['Goa trip'] };
    const text = encode([row]);
    expect(isExport(text)).toBe(true);
    const [rows, bad] = decode(text);
    expect(bad).toBe(0);
    expect(rows[0]).toEqual(row);
    expect(newOnly(rows, [{ ...row, uid: 'u2' }])).toEqual([]);
  });
});

describe('home ledger', () => {
  it('counts card spends when made and the bank when the bill is paid', () => {
    const at = (d: number) => LocalDate.of(2026, 11, d)!.atTime(12, 0, 0);
    const lite = (id: number, type: 'DEBIT' | 'CREDIT', a: number, d: number, account: string | null) => ({ id, type, amountMinor: a, timestamp: at(d), category: 'Other', merchant: null, account });
    const l = buildLedger([lite(1, 'CREDIT', 100000, 1, '1234'), lite(2, 'DEBIT', 20000, 2, '9876')], YearMonth.of(2026, 11), new MonthCycle(), new Set(), new Set(['9876']), [lite(3, 'DEBIT', 20000, 5, '1234')]);
    expect([l.inMinor, l.outMinor, l.bankMinor, l.cardDueMinor, l.cardBillPaidMinor]).toEqual([100000, 20000, 80000, 20000, 20000]);
  });
});
