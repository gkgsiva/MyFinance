package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Investments (SIP, mutual funds, FD ...) are shown as "Invested", never as spending. */
class InvestmentsTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 10) =
        LocalDate.of(y, m, d).atTime(h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun t(id: Long, ts: Long, rupees: Long, type: TxnType, cat: String = DefaultCategories.FOOD, merchant: String? = "M$id") =
        TxnLite(rupees * 100, type, cat, merchant, null, ts, id = id)

    private val nov = listOf(
        t(1, at(2026, 11, 1), 1_00_000, TxnType.CREDIT, DefaultCategories.SALARY),
        t(2, at(2026, 11, 2), 2_000, TxnType.DEBIT),
        t(3, at(2026, 11, 5), 10_000, TxnType.DEBIT, DefaultCategories.INVESTMENTS, "Zerodha Coin"),
        t(4, at(2026, 11, 5, 12), 5_000, TxnType.DEBIT, "PPF", "SBI PPF"),
        t(5, at(2026, 11, 6), 3_000, TxnType.DEBIT, DefaultCategories.SHOPPING),
    )

    @Test
    fun investmentCategories() {
        assertTrue(Spending.isInvestmentCategory(DefaultCategories.INVESTMENTS))
        for (c in listOf("SIP", "Mutual funds", "PPF", "NPS", "FD", "Stocks", "Recurring deposit", "My investments")) {
            assertTrue(Spending.isInvestmentCategory(c), c)
        }
        for (c in listOf(DefaultCategories.FOOD, DefaultCategories.SHOPPING, DefaultCategories.EMI, "Shared costs", "Gifts", "Grocery"))
            assertFalse(Spending.isInvestmentCategory(c), c)
        // A redemption coming back is money in, not an investment.
        assertFalse(Spending.isInvestment(TxnType.CREDIT, DefaultCategories.INVESTMENTS))
    }

    @Test
    fun ledgerShowsInvestedSeparatelyButBalanceStillMoves() {
        val l = MonthLedger.build(nov, YearMonth.of(2026, 11), MonthCycle.CALENDAR, zone)
        assertEquals(1_00_000_00, l.inMinor)
        assertEquals(5_000_00, l.outMinor) // food + shopping only
        assertEquals(15_000_00, l.investedMinor)
        assertEquals(80_000_00, l.netMinor) // the investments still left the account
        val day5 = l.days.first { it.date == LocalDate.of(2026, 11, 5) }
        assertEquals(0, day5.outMinor)
        assertEquals(15_000_00, day5.investedMinor)
        assertEquals(listOf("Zerodha Coin", "SBI PPF"), l.investments.map { it.key })
        assertTrue(day5.rows.all { it.isInvestment })
        assertEquals(80_000_00, l.days.first().closingMinor)
    }

    @Test
    fun summariesLeaveInvestmentsOutOfSpending() {
        val s = FinanceSummary.of(nov)
        assertEquals(5_000_00, s.expenseMinor)
        assertEquals(15_000_00, s.investedMinor)
        assertEquals(95_000_00, s.netMinor) // investments count as saved
        assertEquals(95, s.savingsRatePercent)
        assertEquals(80_000_00, s.leftMinor)
        assertTrue(s.byCategory.none { Spending.isInvestmentCategory(it.key) })
        assertEquals(setOf(DefaultCategories.INVESTMENTS, "PPF"), s.byInvestment.map { it.key }.toSet())

        val m = Reports.monthly(nov, YearMonth.of(2026, 11), 1, zone).single()
        assertEquals(5_000_00, m.expenseMinor)
        assertEquals(15_000_00, m.investedMinor)
        assertTrue(Reports.topMerchants(nov).first.none { it.merchant == "Zerodha Coin" })
        assertTrue(Reports.categoryTrends(nov, YearMonth.of(2026, 11), 1, zone).none { it.key == "PPF" })

        val close = MonthClose.summary(YearMonth.of(2026, 11), nov)
        assertEquals(5_000_00, close.spentMinor)
        assertEquals(15_000_00, close.investedMinor)

        val d = Dashboard.compute(nov, YearMonth.of(2026, 11), DashPeriod.MONTH, zone = zone)
        assertEquals(5_000_00, d.spentMinor)
        assertEquals(15_000_00, d.investedMinor)
        assertEquals(5_000_00, d.trend.last().second)

        val cmp = MonthCompare.of(nov, YearMonth.of(2026, 11), LocalDate.of(2026, 11, 30), zone = zone)
        assertEquals(5_000_00, cmp.spentMinor)

        val text = FamilySummary.text(nov, YearMonth.of(2026, 11), "Siva", zone = zone)
        assertTrue("Spent ₹5,000 · Invested ₹15,000" in text, text)
    }

    @Test
    fun askSpendingLeavesInvestmentsOutUnlessAsked() {
        val all = Ask.answer(AskQuery(AskMetric.SPEND, null, null, null, TxnType.DEBIT), nov, emptyMap(), zone)
        assertEquals(5_000_00, all.totalMinor)
        val sip = Ask.answer(
            AskQuery(AskMetric.SPEND, AskSubject(AskSubjectKind.CATEGORY, DefaultCategories.INVESTMENTS, DefaultCategories.INVESTMENTS), null, null, TxnType.DEBIT),
            nov, emptyMap(), zone,
        )
        assertEquals(10_000_00, sip.totalMinor)
    }

    @Test
    fun investmentPaymentsAreDetected() {
        val c = Categorizer()
        for (m in listOf("CAMS", "KFINTECH", "BSE STAR MF", "Indian Clearing Corp", "Paytm Money", "Groww", "SBI Fixed Deposit", "NPS Trust", "Sukanya Samriddhi")) {
            assertEquals(DefaultCategories.INVESTMENTS, c.categorize(m, TxnType.DEBIT), m)
        }
        assertEquals(DefaultCategories.INVESTMENTS, c.categorize("ACH D- ICICI PRU MF", TxnType.DEBIT, "Rs 5000 debited towards SIP"))
    }
}
