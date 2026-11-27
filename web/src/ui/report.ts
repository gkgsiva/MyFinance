// The printable monthly report (as Android's PDF report): totals, where the money went, every entry, card bills.
// jsPDF's built-in fonts have no rupee sign, so amounts are written "Rs 1,234.00".

import { YearMonth, formatDate, formatDateMs, LocalDate } from '../core/dates';
import { check, statusLabel } from '../core/cardBills';
import { formatInr } from '../core/text';
import { store } from '../store/store';
import { byCategory, byPayee, inPeriods, totalsOf } from '../store/stats';

const rs = (minor: number) => formatInr(minor).replace('₹', 'Rs ');
const BRAND: [number, number, number] = [15, 118, 110];

/** Right-aligns the headings of the number columns, like their values. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const alignHead = (cols: number[]) => (d: any) => { if (d.section === 'head' && cols.includes(d.column.index)) d.cell.styles.halign = 'right'; };

export async function monthlyReport(ym: YearMonth, owner?: string | null): Promise<Blob> {
  const [{ jsPDF }, { default: autoTable }] = await Promise.all([import('jspdf'), import('jspdf-autotable')]);
  const doc = new jsPDF({ unit: 'pt', format: 'a4' });
  const W = doc.internal.pageSize.getWidth();
  const M = 40;
  const cycle = store.cycle;
  const list = inPeriods(ym, ym, owner).sort((a, b) => a.timestamp - b.timestamp);
  const all = store.txns.filter((t) => cycle.periodOf(t).equals(ym) && (owner === undefined || (t.owner ?? '') === (owner ?? '')));
  const tot = totalsOf(list);
  const who = owner === undefined ? (store.me ? `${store.me} and family` : 'Household') : owner || store.me || 'Me';

  doc.setFillColor(...BRAND);
  doc.rect(0, 0, W, 80, 'F');
  doc.setTextColor(255, 255, 255);
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(22);
  doc.text(`MyFinance · ${ym.label()}`, M, 42);
  doc.setFont('helvetica', 'normal');
  doc.setFontSize(11);
  doc.text(`${who} · ${formatDate(cycle.start(ym))} to ${formatDate(cycle.end(ym))} · made ${formatDate(LocalDate.today())}`, M, 62);

  doc.setTextColor(20, 25, 28);
  const boxes: [string, number][] = [['Money in', tot.inMinor], ['Spent', tot.spentMinor], ['Invested', tot.investedMinor], ['Left', tot.inMinor - tot.spentMinor - tot.investedMinor]];
  const bw = (W - 2 * M - 30) / 4;
  boxes.forEach(([label, v], i) => {
    const x = M + i * (bw + 10);
    doc.setFillColor(238, 241, 243);
    doc.roundedRect(x, 100, bw, 54, 6, 6, 'F');
    doc.setFontSize(9);
    doc.setTextColor(91, 101, 109);
    doc.text(label, x + 10, 118);
    doc.setFontSize(13);
    doc.setTextColor(20, 25, 28);
    doc.setFont('helvetica', 'bold');
    doc.text(rs(v), x + 10, 140);
    doc.setFont('helvetica', 'normal');
  });

  const head = { fillColor: BRAND, textColor: 255, fontStyle: 'bold' as const };
  const cats = byCategory(list);
  const budgets = store.settings.budgets;
  autoTable(doc, {
    startY: 176, margin: { left: M, right: M }, headStyles: head, styles: { fontSize: 9 },
    head: [['Where it went', 'Entries', 'Amount', 'Share', 'Budget']],
    body: cats.map((c) => [c.name, String(c.count), rs(c.totalMinor), `${Math.round((c.totalMinor / Math.max(1, tot.spentMinor)) * 100)}%`, budgets[c.name] ? `${rs(budgets[c.name])}${c.totalMinor > budgets[c.name] ? ' (over)' : ''}` : '']),
    columnStyles: { 1: { halign: 'right' }, 2: { halign: 'right' }, 3: { halign: 'right' }, 4: { halign: 'right' } }, didParseCell: alignHead([1, 2, 3, 4]),
  });
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const after = () => (doc as any).lastAutoTable.finalY + 18;

  const payees = byPayee(list).slice(0, 10);
  if (payees.length) {
    autoTable(doc, {
      startY: after(), margin: { left: M, right: M }, headStyles: head, styles: { fontSize: 9 },
      head: [['Top payees', 'Times', 'Amount']],
      body: payees.map((p) => [p.name, String(p.count), rs(p.totalMinor)]),
      columnStyles: { 1: { halign: 'right' }, 2: { halign: 'right' } }, didParseCell: alignHead([1, 2]),
    });
  }

  const bills = check(
    store.bills.map((b) => [{ title: store.accountName(b.tail, b.bank), accountTail: b.tail, dueDate: LocalDate.parse(b.due)!, amountMinor: b.totalMinor, minDueMinor: b.minMinor }, LocalDate.parse(b.arrived)!, b.markedPaid]),
    store.txns.map((t) => ({ id: t.id, type: t.type, amountMinor: t.amountMinor, timestamp: t.timestamp, account: t.accountTail, merchant: [t.merchant, t.rawText].filter(Boolean).join(' ') })),
    LocalDate.today(),
  ).filter((c) => !c.bill.dueDate.isBefore(cycle.start(ym)) && !c.bill.dueDate.isAfter(cycle.end(ym).plusDays(25)));
  if (bills.length) {
    autoTable(doc, {
      startY: after(), margin: { left: M, right: M }, headStyles: head, styles: { fontSize: 9 },
      head: [['Card bill', 'Due', 'Bill', 'Paid', 'Status']],
      body: bills.map((c) => [c.bill.title, formatDate(c.bill.dueDate), rs(c.bill.amountMinor), rs(c.paidByDueMinor + c.paidLateMinor), statusLabel[c.status]]),
      columnStyles: { 2: { halign: 'right' }, 3: { halign: 'right' } }, didParseCell: alignHead([2, 3]),
    });
  }

  doc.addPage();
  autoTable(doc, {
    startY: M, margin: { left: M, right: M }, headStyles: head, styles: { fontSize: 8, cellPadding: 3 },
    head: [['Date', 'Paid to / from', 'Category', 'Account', 'Out', 'In']],
    body: all.sort((a, b) => a.timestamp - b.timestamp).map((t) => [
      formatDateMs(t.timestamp, false), (t.merchant ?? '') + (t.note ? ` (${t.note})` : '') + (t.excluded ? ' [not counted]' : ''), t.category,
      store.accountName(t.accountTail, t.bank) + (t.owner ? ` · ${t.owner}` : ''),
      t.type === 'DEBIT' ? rs(t.amountMinor) : '', t.type === 'CREDIT' ? rs(t.amountMinor) : '',
    ]),
    columnStyles: { 1: { cellWidth: 150 }, 4: { halign: 'right' }, 5: { halign: 'right', textColor: [11, 122, 59] } },
    didParseCell: (d) => { alignHead([4, 5])(d); if (d.section === 'body' && String(d.row.raw && (d.row.raw as string[])[1]).includes('[not counted]')) d.cell.styles.textColor = [135, 145, 154]; },
  });

  const pages = doc.getNumberOfPages();
  for (let i = 1; i <= pages; i++) {
    doc.setPage(i);
    doc.setFontSize(8);
    doc.setTextColor(135, 145, 154);
    doc.text(`MyFinance · ${ym.label()} · page ${i} of ${pages}`, M, doc.internal.pageSize.getHeight() - 20);
  }
  return doc.output('blob');
}
