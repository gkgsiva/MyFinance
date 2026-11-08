package com.myfinance.core

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V11FeaturesTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val nov5 = at(2024, 11, 5)

    // ---- Bills ----

    @Test
    fun hdfcCardStatement() {
        val b = assertNotNull(
            BillParser.parse(
                "VM-HDFCBK",
                "Your HDFC Bank Credit Card XX1234 statement is generated. Total Amt Due: Rs.12,345.00, Min Amt Due: Rs.620.00, Payment Due Date: 15-Nov-24.",
                nov5, zone,
            ),
        )
        assertEquals(BillKind.CREDIT_CARD, b.kind)
        assertEquals(1234500, b.amountMinor)
        assertEquals(62000, b.minDueMinor)
        assertEquals(LocalDate.of(2024, 11, 15), b.dueDate)
        assertEquals("1234", b.accountTail)
        assertEquals("HDFC Bank", b.bank)
    }

    @Test
    fun iciciCardStatementWithMinFirst() {
        val b = assertNotNull(
            BillParser.parse(
                "AD-ICICIB",
                "ICICI Bank Credit Card XX5678: Minimum amount due INR 430.00; Total amount due INR 8,450.50; Due date 20-NOV-24.",
                nov5, zone,
            ),
        )
        assertEquals(845050, b.amountMinor)
        assertEquals(43000, b.minDueMinor)
        assertEquals(LocalDate.of(2024, 11, 20), b.dueDate)
    }

    @Test
    fun sbiCardPayableBy() {
        val b = assertNotNull(
            BillParser.parse(
                "BX-SBICRD",
                "Your SBI Card statement for card ending 9012 is ready. Total Amt Due Rs 5,600; Min Amt Due Rs 280; Payable by 22/11/2024.",
                nov5, zone,
            ),
        )
        assertEquals(560000, b.amountMinor)
        assertEquals(LocalDate.of(2024, 11, 22), b.dueDate)
        assertEquals("9012", b.accountTail)
    }

    @Test
    fun emiDue() {
        val b = assertNotNull(
            BillParser.parse(
                "VM-BAJAJF",
                "Dear Customer, your Bajaj Finance EMI of Rs.2,499 for loan a/c XX7788 is due on 03/12/2024. Please maintain sufficient balance.",
                nov5, zone,
            ),
        )
        assertEquals(BillKind.EMI, b.kind)
        assertEquals(249900, b.amountMinor)
        assertEquals(LocalDate.of(2024, 12, 3), b.dueDate)
        assertEquals("Bajaj Finance", b.bank)
    }

    @Test
    fun emiWillBeDebited() {
        val b = assertNotNull(
            BillParser.parse(
                "JM-HDFCBK",
                "Your loan EMI of INR 18,500 for Loan Account XX4455 will be debited on 10-Dec-24 from your account.",
                nov5, zone,
            ),
        )
        assertEquals(1850000, b.amountMinor)
        assertEquals(LocalDate.of(2024, 12, 10), b.dueDate)
    }

    @Test
    fun paymentConfirmationsAndSpendsAreNotBills() {
        assertNull(BillParser.parse("VM-HDFCBK", "Payment of Rs 12,345.00 received towards your HDFC Bank Credit Card XX1234. Thank you.", nov5, zone))
        assertNull(BillParser.parse("VM-HDFCBK", "Spent Rs 1,299 on HDFC Bank Credit Card XX1234 at AMAZON on 2024-11-05", nov5, zone))
        assertNull(BillParser.parse("VM-HDFCBK", "Get a pre-approved loan of Rs 5,00,000 at 10.5%. Apply by 30-Nov-24", nov5, zone))
        assertNull(BillParser.parse("VM-HDFCBK", "Your OTP for credit card payment is 123456", nov5, zone))
    }

    @Test
    fun cardBillPaidDetection() {
        val bill = BillDue(BillKind.CREDIT_CARD, 1234500, 62000, LocalDate.of(2024, 11, 15), "1234", "HDFC Bank")
        val payment = TxnLite(1234500, TxnType.CREDIT, "Other Income", null, "HDFC Bank ••1234", at(2024, 11, 12))
        assertTrue(BillParser.isPaid(bill, listOf(payment), zone))
        assertFalse(BillParser.isPaid(bill, listOf(payment.copy(account = "SBI ••9999")), zone))
    }

    // ---- Recurring ----

    @Test
    fun monthlySubscriptionFoundAndNextDatePredicted() {
        val txns = (1..4).map { TxnLite(64900, TxnType.DEBIT, "Entertainment", "NETFLIX", null, at(2024, it + 6, 12)) } +
            listOf(TxnLite(25000, TxnType.DEBIT, "Food", "SWIGGY", null, at(2024, 9, 3)))
        val found = RecurringDetector.detect(txns, LocalDate.of(2024, 11, 1), zone)
        assertEquals(1, found.size)
        val r = found.single()
        assertEquals("NETFLIX", r.merchant)
        assertEquals(Cadence.MONTHLY, r.cadence)
        assertEquals(LocalDate.of(2024, 11, 12), r.nextDate)
        assertEquals(4, r.occurrences)
    }

    @Test
    fun irregularOrStoppedPaymentsAreNotRecurring() {
        val random = listOf(3, 9, 40, 41, 90).map { TxnLite(30000, TxnType.DEBIT, "Food", "ZOMATO", null, at(2024, 1, 1) + it * 86_400_000L) }
        assertTrue(RecurringDetector.detect(random, LocalDate.of(2024, 4, 15), zone).isEmpty())
        val stopped = (1..3).map { TxnLite(19900, TxnType.DEBIT, "Entertainment", "HOTSTAR", null, at(2024, it, 5)) }
        assertTrue(RecurringDetector.detect(stopped, LocalDate.of(2024, 10, 1), zone).isEmpty())
    }

    // ---- Budgets ----

    @Test
    fun budgetAlertLevels() {
        val month = YearMonth.of(2024, 11)
        assertNull(BudgetAlerts.level(7900, 10000))
        assertEquals(80, BudgetAlerts.level(8000, 10000))
        assertEquals(100, BudgetAlerts.level(10500, 10000))

        val first = BudgetAlerts.due(mapOf("Food" to 8500), mapOf("Food" to 10000, "Fuel" to 5000), emptySet(), month)
        assertEquals(listOf(80), first.map { it.percent })
        val sent = BudgetAlerts.keysToRecord(first.single()).toSet()
        assertTrue(BudgetAlerts.due(mapOf("Food" to 9000), mapOf("Food" to 10000), sent, month).isEmpty())
        val over = BudgetAlerts.due(mapOf("Food" to 12000), mapOf("Food" to 10000), sent, month).single()
        assertEquals(100, over.percent)
        assertTrue(over.message.contains("over budget"))
    }

    // ---- Balances ----

    @Test
    fun balances() {
        val a = assertNotNull(BalanceParser.parse("VM-HDFCBK", "Rs.500 debited from a/c XX1234 on 05-11-24. Avl Bal: INR 10,250.50", 1))
        assertEquals(1025050, a.amountMinor)
        assertFalse(a.isCardLimit)
        assertEquals("1234", a.accountTail)

        val b = assertNotNull(BalanceParser.parse("AD-SBIINB", "Avl Bal in A/c XX5678 is Rs 42,000.00 as on 05-11-24", 2))
        assertEquals(4200000, b.amountMinor)
        assertEquals("SBI", b.bank)

        val c = assertNotNull(BalanceParser.parse("JD-HDFCBK", "Spent Rs 1,299.00 on HDFC Bank Card x4321 at AMAZON. Avl Lmt: Rs 85,000.00", 3))
        assertTrue(c.isCardLimit)

        assertNull(BalanceParser.parse("VM-HDFCBK", "Maintain minimum balance of Rs 10,000 to avoid charges", 4))

        val newer = a.copy(amountMinor = 900000, timestamp = 10)
        val latest = Balances.latest(listOf(a, b, c, newer))
        assertEquals(3, latest.size)
        assertEquals(900000, latest.first { it.accountTail == "1234" }.amountMinor)
        assertEquals(900000 + 4200000, Balances.totalBankMinor(latest))
    }

    // ---- Filters & export ----

    @Test
    fun filters() {
        val t = TxnLite(150000, TxnType.DEBIT, "Shopping", "Amazon", "HDFC Bank ••1234", at(2024, 11, 5), "Priya", mode = PaymentMode.CARD)
        assertTrue(TxnFilter().matches(t))
        assertTrue(TxnFilter(text = "amaz", owner = "Priya", minAmountMinor = 100000).matches(t))
        assertFalse(TxnFilter(maxAmountMinor = 100000).matches(t))
        assertFalse(TxnFilter(fromMillis = at(2024, 11, 6)).matches(t))
        assertFalse(TxnFilter(owner = "Siva").matches(t))
        assertTrue(TxnFilter(text = "1500").matches(t))
        assertFalse(TxnFilter(mode = PaymentMode.UPI).matches(t))
        assertTrue(TxnFilter(text = "gift", account = "HDFC Bank ••1234").matches(t, note = "Birthday gift"))
    }

    @Test
    fun xlsxIsAValidZipWithTheSheet() {
        val out = ByteArrayOutputStream()
        val rows = StatementExport.rows(
            listOf(TxnLite(150050, TxnType.DEBIT, "Shopping", "A & B <Store>", null, at(2024, 11, 5)) to "note"),
            zone,
        )
        XlsxWriter.write(out, "Statement", StatementExport.header, rows)
        val names = HashMap<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue("[Content_Types].xml" in names)
        val sheet = names.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("A &amp; B &lt;Store&gt;"))
        assertTrue(sheet.contains("<v>1500.50</v>"))
        assertEquals("AA", XlsxWriter.column(26))
    }

    @Test
    fun csvCarriesModeAndReadsOldFiles() {
        val row = SharedTxn("u1", 100, TxnType.DEBIT, "X", "Food", null, null, 0, null, "Siva", "SMS", null, null, false, "UPI")
        assertEquals("UPI", CsvCodec.decode(CsvCodec.encode(listOf(row))).first.single().mode)
        val old = "uid,date,amount,type,merchant,category,account,bank,note,owner,source,sms_key,reference,excluded\n" +
            "u2,2024-11-05T10:00:00Z,10.00,DEBIT,X,Food,,,,,MANUAL,,,false\n"
        assertNull(CsvCodec.decode(old).first.single().mode)
    }

    @Test
    fun paymentModes() {
        assertEquals(PaymentMode.UPI, PaymentMode.detect("Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY UPI Ref 431", manual = false))
        assertEquals(PaymentMode.CARD, PaymentMode.detect("Spent Rs 1,299 on HDFC Bank Card x4321 at AMAZON", manual = false))
        assertEquals(PaymentMode.CASH, PaymentMode.detect("Rs.5000 withdrawn at SBI ATM", manual = false))
        assertEquals(PaymentMode.NETBANKING, PaymentMode.detect("INR 50,000 credited by NEFT from ACME", manual = false))
        assertEquals(PaymentMode.CASH, PaymentMode.detect(null, manual = true))
    }

    // ---- Reports ----

    private val history = listOf(
        TxnLite(100_00000, TxnType.CREDIT, "Salary", "ACME", null, at(2024, 10, 1), "Siva"),
        TxnLite(20_00000, TxnType.DEBIT, "Rent & Housing", "Landlord", null, at(2024, 10, 3), "Siva", mode = PaymentMode.NETBANKING),
        TxnLite(5_00000, TxnType.DEBIT, "Food & Dining", "Swiggy", null, at(2024, 10, 10), "Priya", mode = PaymentMode.UPI),
        TxnLite(100_00000, TxnType.CREDIT, "Salary", "ACME", null, at(2024, 11, 1), "Siva"),
        TxnLite(20_00000, TxnType.DEBIT, "Rent & Housing", "Landlord", null, at(2024, 11, 3), "Siva", mode = PaymentMode.NETBANKING),
        TxnLite(10_00000, TxnType.DEBIT, "Food & Dining", "Swiggy", null, at(2024, 11, 10), "Priya", mode = PaymentMode.UPI),
        TxnLite(2_00000, TxnType.DEBIT, "Food & Dining", "swiggy ", null, at(2024, 11, 11), "Priya", mode = PaymentMode.UPI),
        TxnLite(50_00000, TxnType.CREDIT, "Other Income", "Bonus", null, at(2024, 3, 20), "Siva"),
    )

    @Test
    fun monthlyAndSavings() {
        val m = Reports.monthly(history, YearMonth.of(2024, 11), 2, zone)
        assertEquals(listOf(YearMonth.of(2024, 10), YearMonth.of(2024, 11)), m.map { it.month })
        assertEquals(25_00000, m[0].expenseMinor)
        assertEquals(32_00000, m[1].expenseMinor)
        assertEquals(68, m[1].savingsRatePercent)
        assertEquals(28, Reports.changePercent(m[1].expenseMinor, m[0].expenseMinor))
    }

    @Test
    fun categoryAndPersonTrends() {
        val cats = Reports.categoryTrends(history, YearMonth.of(2024, 11), 2, zone)
        val food = cats.first { it.key == "Food & Dining" }
        assertEquals(listOf(5_00000L, 12_00000L), food.values)
        assertEquals(140, food.trendPercent)
        val people = Reports.perPerson(history, YearMonth.of(2024, 11), 2, zone)
        assertEquals(listOf("Siva", "Priya"), people.map { it.key })
    }

    @Test
    fun merchantsModesAndFinancialYears() {
        val (byAmount, byCount) = Reports.topMerchants(history)
        assertEquals("Landlord", byAmount.first().merchant)
        assertEquals(3, byCount.first().count)
        assertEquals("Swiggy", byCount.first().merchant)

        val modes = Reports.paymentModes(history)
        assertEquals(PaymentMode.NETBANKING, modes.first().mode)

        val fys = Reports.financialYears(history, zone)
        assertEquals(listOf("FY 2024-25", "FY 2023-24"), fys.map { it.label })
        assertEquals(50_00000, fys[1].incomeMinor)
    }

    @Test
    fun unusualSpends() {
        val usual = (1..6).map { TxnLite(50000, TxnType.DEBIT, "Food & Dining", "Cafe", null, at(2024, 10, it)) }
        val big = TxnLite(400000, TxnType.DEBIT, "Food & Dining", "Wedding caterer", null, at(2024, 10, 20))
        val flagged = Reports.unusual(usual + big)
        assertEquals(listOf("Wedding caterer"), flagged.map { it.txn.merchant })
        assertEquals(8.0, flagged.single().times)
        assertTrue(Reports.unusual(usual.take(3) + big).isEmpty())
    }

    @Test
    fun dates() {
        val ref = LocalDate.of(2024, 12, 28)
        assertEquals(LocalDate.of(2025, 1, 5), Dates.findDate("due on 5th Jan", ref))
        assertEquals(LocalDate.of(2024, 11, 15), Dates.findDate("Nov 15, 2024", ref))
        assertEquals(LocalDate.of(2024, 11, 15), Dates.findDate("on 2024-11-15", ref))
        assertEquals(2024, Dates.financialYearStart(LocalDate.of(2025, 3, 31)))
        assertEquals("FY 2009-10", Dates.financialYearLabel(2009))
    }
}
