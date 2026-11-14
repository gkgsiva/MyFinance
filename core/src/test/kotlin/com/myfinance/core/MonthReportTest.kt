package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonthReportTest {
    private val zone = ZoneOffset.UTC
    private val nov = YearMonth.of(2026, 11)
    private fun at(y: Int, m: Int, d: Int, h: Int = 10) =
        LocalDate.of(y, m, d).atTime(h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun t(
        id: Long, ts: Long, rupees: Long, type: TxnType = TxnType.DEBIT, cat: String = DefaultCategories.FOOD,
        merchant: String? = "M$id", account: String? = "ICICI ••0144", mode: PaymentMode = PaymentMode.UPI, owner: String? = "Siva",
    ) = TxnLite(rupees * 100, type, cat, merchant, account, ts, owner, id = id, mode = mode)

    private val month = listOf(
        t(1, at(2026, 11, 1), 100_000, TxnType.CREDIT, DefaultCategories.SALARY, "RNTBCI"),
        t(2, at(2026, 11, 2), 2_000, merchant = "Swiggy", account = "ICICI ••5010", mode = PaymentMode.CARD),
        t(3, at(2026, 11, 2, 14), 20_000, cat = DefaultCategories.RENT, merchant = "Landlord"),
        t(4, at(2026, 11, 5), 10_000, cat = DefaultCategories.INVESTMENTS, merchant = "Zerodha SIP"),
        t(5, at(2026, 11, 9), 500, cat = DefaultCategories.OTHER, merchant = null, mode = PaymentMode.CASH, account = null, owner = "Wife"),
    )
    private val october = listOf(
        t(10, at(2026, 10, 3), 50_000, TxnType.CREDIT, DefaultCategories.SALARY, "RNTBCI"),
        t(11, at(2026, 10, 4), 10_000, cat = DefaultCategories.RENT, merchant = "Landlord"),
    )

    @Test
    fun totalsKeepInvestmentsOutOfSpendingAndSplitCardFromBank() {
        val r = MonthReport.build(month + october, nov, zone = zone, cardTails = setOf("5010"))
        assertEquals(100_000_00, r.incomeMinor)
        assertEquals(22_500_00, r.spentMinor)
        assertEquals(10_000_00, r.investedMinor)
        assertEquals(67_500_00, r.leftMinor)
        assertEquals(77, r.savingsRatePercent)
        assertEquals(2_000_00, r.cardSpentMinor)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), r.rows.map { it.txn.id })
        assertEquals(67_500_00, r.rows.last().runningMinor)
        assertEquals(listOf("Bank account / UPI", "Credit card", "Cash"), r.paidWith.map { it.key })
        assertEquals(DefaultCategories.RENT, r.categories.first().key)
        assertEquals(89, r.categories.first().percent)
        assertEquals(listOf("Zerodha SIP"), r.investments.map { it.key })
        assertEquals(listOf("RNTBCI"), r.incomeSources.map { it.key })
        assertEquals(listOf("Siva", "Wife"), r.people.map { it.key })
        assertTrue(r.merchants.none { it.merchant == "Zerodha SIP" })
    }

    @Test
    fun cardIsKnownFromModeWhenNoCardsAreSetUp() {
        val r = MonthReport.build(month, nov, zone = zone)
        assertEquals(2_000_00, r.cardSpentMinor)
        assertTrue(r.rows.single { it.txn.id == 2L }.isCard)
    }

    @Test
    fun notCountedAndHiddenRowsAreListedButOnlyHiddenCount() {
        val cardBill = t(20, at(2026, 11, 20), 15_000, cat = DefaultCategories.TRANSFERS, merchant = "ICICI card bill")
        val oldTransfer = t(21, at(2026, 10, 20), 5_000, cat = DefaultCategories.TRANSFERS)
        val r = MonthReport.build(month, nov, zone = zone, notCounted = listOf(cardBill, oldTransfer), hiddenIds = setOf(2L))
        assertEquals(listOf(20L), r.notCounted.map { it.txn.id })
        assertEquals(22_500_00, r.spentMinor) // hidden Swiggy still counts, card bill does not
        assertEquals(1, r.hiddenCount)
    }

    @Test
    fun dailyCoversEveryDayOfTheSalaryMonthAndTrendHasSixMonths() {
        val cycle = MonthCycle(startDay = 26)
        val r = MonthReport.build(month + october, nov, cycle, zone)
        assertEquals(LocalDate.of(2026, 10, 26), r.daily.first().first)
        assertEquals(LocalDate.of(2026, 11, 25), r.daily.last().first)
        assertEquals(31, r.daily.size)
        assertEquals(22_000_00, r.daily.single { it.first == LocalDate.of(2026, 11, 2) }.second)
        assertEquals(6, r.trend.size)
        assertEquals(nov, r.trend.last().month)
        assertTrue(r.rangeLabel.startsWith("26 Oct"))
    }

    @Test
    fun budgetsLabelsRepeatingAndBalances() {
        val rent = RecurringPayment(
            DefaultCategories.RENT.let { "Landlord" }, DefaultCategories.RENT, 20_000_00, Cadence.MONTHLY,
            LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 2), 3,
        )
        val r = MonthReport.build(
            month + october, nov, zone = zone,
            labelsById = mapOf(2L to listOf("Trip"), 3L to listOf("Home", "Trip")),
            budgets = mapOf(DefaultCategories.FOOD to 1_000_00, DefaultCategories.RENT to 25_000_00),
            recurring = listOf(rent),
            balanceAfter = mapOf(1L to 150_000_00, 3L to 130_000_00),
            notesById = mapOf(3L to "November rent"),
        )
        assertEquals(DefaultCategories.FOOD, r.budgets.first().category)
        assertEquals(200, r.budgets.first().percent)
        assertEquals(listOf("Trip", "Home", "No label"), r.labels.map { it.key })
        assertEquals(22_000_00, r.labels.first().totalMinor)
        val repeat = r.repeating.single()
        assertEquals("Landlord", repeat.name)
        assertEquals(LocalDate.of(2026, 12, 2), repeat.nextDate)
        val bal = r.balances.single()
        assertEquals("ICICI ••0144", bal.account)
        assertEquals(50_000_00, bal.openingMinor) // 1,50,000 after a 1,00,000 salary
        assertEquals(130_000_00, bal.closingMinor)
        assertEquals(0, bal.differenceMinor)
        assertEquals("November rent", r.rows.single { it.txn.id == 3L }.note)
        val named = MonthReport.build(month, nov, zone = zone, nicknamesById = mapOf(3L to "House rent", 2L to "swiggy"))
        assertEquals("House rent (Landlord)", named.rows.single { it.txn.id == 3L }.payee)
        assertEquals("swiggy", named.rows.single { it.txn.id == 2L }.payee)
        assertTrue(r.insights.any { it.startsWith("Over budget: ${DefaultCategories.FOOD}") })
        assertTrue(r.insights.any { "fixed costs" in it })
        assertTrue(r.insights.any { it.startsWith("Spent ₹22,500, 125% more than last month") })
    }

    @Test
    fun emptyMonth() {
        val r = MonthReport.build(october, nov, zone = zone)
        assertEquals(0, r.rows.size)
        assertNull(r.savingsRatePercent)
        assertEquals(listOf("Nothing was recorded this month."), r.insights)
        assertEquals(30, r.daily.size)
    }

    @Test
    fun householdAccountsPerAccountPersonIncomeAndEmis() {
        val emi = t(30, at(2026, 11, 6), 5_000, cat = DefaultCategories.EMI, merchant = "HDFC Home Loan")
        val cardBill = t(31, at(2026, 11, 20), 2_000, cat = DefaultCategories.TRANSFERS, merchant = "Card bill")
        val cardCredit = t(32, at(2026, 11, 20, 12), 2_000, TxnType.CREDIT, DefaultCategories.TRANSFERS, "Payment received", "ICICI ••5010", PaymentMode.CARD)
        val r = MonthReport.build(month + emi, nov, zone = zone, notCounted = listOf(cardBill, cardCredit), cardTails = setOf("5010"))
        assertEquals(listOf(DefaultCategories.SALARY), r.incomeCategories.map { it.key })
        assertEquals(listOf("HDFC Home Loan"), r.emis.map { it.key })
        val bank = r.accountLines.single { it.account == "ICICI ••0144" }
        assertEquals(100_000_00, bank.inMinor)
        assertEquals(35_000_00, bank.outMinor) // rent, investment, EMI
        assertEquals(2_000_00, bank.movedOutMinor)
        assertEquals(false, bank.isCard)
        val card = r.accountLines.single { it.account == "ICICI ••5010" }
        assertTrue(card.isCard)
        assertEquals(2_000_00, card.outMinor)
        assertEquals(2_000_00, card.movedInMinor)
        assertEquals(0, card.netMinor)
        assertTrue(r.accountLines.last().isCard) // banks first, then cards
        val siva = r.personLines.single { it.person == "Siva" }
        assertEquals(100_000_00, siva.receivedMinor)
        assertEquals(27_000_00, siva.spentMinor)
        assertEquals(10_000_00, siva.investedMinor)
        assertEquals(500_00, r.personLines.single { it.person == "Wife" }.spentMinor)
    }

    @Test
    fun bankReconciliationFlagsAMissingEntry() {
        // Balance 1,50,000 after salary, 1,25,000 after rent: 5,000 more went out than the entries show.
        val r = MonthReport.build(month, nov, zone = zone, balanceAfter = mapOf(1L to 150_000_00, 3L to 125_000_00))
        val b = r.balances.single()
        assertEquals(80_000_00, b.entriesNetMinor)
        assertEquals(-5_000_00, b.differenceMinor)
    }

    @Test
    fun tailOfLabels() {
        assertEquals("5010", MonthReport.tailOf("ICICI ••5010"))
        assertEquals("4144", MonthReport.tailOf("••034144"))
        assertNull(MonthReport.tailOf("Cash"))
    }
}
