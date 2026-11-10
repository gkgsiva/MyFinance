package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
private fun ts(d: LocalDate) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
private fun d(amt: Long, date: LocalDate, cat: String = "Food & Dining", merchant: String? = "Shop", mode: PaymentMode = PaymentMode.UPI, owner: String? = null, account: String? = null) =
    TxnLite(amt, TxnType.DEBIT, cat, merchant, account, ts(date), owner, mode = mode)
private fun c(amt: Long, date: LocalDate, cat: String = "Salary") = TxnLite(amt, TxnType.CREDIT, cat, "Employer", null, ts(date))

class MoneyViewsTest {
    private val nov = YearMonth.of(2026, 11)

    @Test fun balancesMatchRegisteredAccountsAndCards() {
        val accounts = listOf(
            MyAccount(1, "0144", bank = "ICICI Bank", isCard = false, name = "Salary a/c"),
            MyAccount(2, "7002", bank = "ICICI Bank", isCard = true),
        )
        val readings = listOf(
            BalanceReading("ICICI Bank", "XX0144", 5_000_000, false, 1),
            BalanceReading("ICICI Bank", "XX0144", 4_000_000, false, 2),
            BalanceReading("ICICI", "7002", 15_000_000, true, 3),
            BalanceReading("SBI", "9911", 1_000_000, false, 4),
        )
        val o = AccountBalances.overview(readings, accounts, limits = mapOf(2L to 20_000_000))
        assertEquals(listOf("Salary a/c ••0144", "SBI ••9911"), o.banks.map { it.label })
        assertEquals(5_000_000, o.bankTotalMinor)
        assertEquals(5_000_000, o.cards.single().usedMinor)
        assertEquals(25, o.cards.single().utilisationPct)
        assertEquals(25, o.utilisationPct)
    }

    @Test fun cardWithoutLimitUsesThisMonthsSpends() {
        val o = AccountBalances.overview(emptyList(), listOf(MyAccount(2, "7002", bank = "ICICI Bank")), cardSpends = mapOf("7002" to 12_300))
        assertEquals(12_300, o.cards.single().usedMinor)
        assertNull(o.cards.single().utilisationPct)
    }

    @Test fun monthCompareUsesSameDayOfLastMonth() {
        val txns = listOf(
            d(1000_00, LocalDate.of(2026, 10, 3)), d(500_00, LocalDate.of(2026, 10, 25), "Shopping"),
            d(800_00, LocalDate.of(2026, 11, 2)), c(50000_00, LocalDate.of(2026, 11, 1)),
        )
        val m = MonthCompare.of(txns, nov, LocalDate.of(2026, 11, 10), zone = zone)
        assertEquals(800_00, m.spentMinor)
        assertEquals(1500_00, m.lastSpentMinor)
        assertEquals(1000_00, m.lastSameDayMinor)
        assertEquals(10, m.dayReached)
        assertEquals("Shopping", m.categories.first().category)
    }

    @Test fun modeSplitAndFixedFlexible() {
        val txns = listOf(
            d(100_00, LocalDate.of(2026, 11, 2), mode = PaymentMode.CASH), d(300_00, LocalDate.of(2026, 11, 3)),
            d(20000_00, LocalDate.of(2026, 11, 5), DefaultCategories.RENT, "Landlord", PaymentMode.NETBANKING),
            d(499_00, LocalDate.of(2026, 11, 6), DefaultCategories.ENTERTAINMENT, "Netflix", PaymentMode.CARD),
        )
        val split = ModeSplit.of(txns, setOf(nov), zone = zone)
        assertEquals(PaymentMode.NETBANKING, split.first().mode)
        assertEquals(4, split.size)
        val ff = FixedSpend.of(txns, nov, setOf(Categorizer.merchantKey("Netflix")), zone = zone)
        assertEquals(20499_00, ff.fixedMinor)
        assertEquals(400_00, ff.flexibleMinor)
        assertTrue(ff.fixedPct in 97..99)
    }

    @Test fun savingsTrend() {
        val txns = listOf(c(100000_00, LocalDate.of(2026, 10, 1)), d(60000_00, LocalDate.of(2026, 10, 5)), c(100000_00, LocalDate.of(2026, 11, 1)), d(80000_00, LocalDate.of(2026, 11, 5)))
        val p = SavingsTrend.of(txns, nov, 3, zone = zone)
        assertEquals(listOf(null, 40, 20), p.map { it.ratePct })
        assertEquals(30, SavingsTrend.averagePct(p))
    }

    @Test fun familySummaryText() {
        val txns = listOf(d(1000_00, LocalDate.of(2026, 11, 2)), d(500_00, LocalDate.of(2026, 11, 3), "Shopping", owner = "Priya"), c(10000_00, LocalDate.of(2026, 11, 1)))
        val t = FamilySummary.text(txns, nov, "Siva", zone = zone)
        assertTrue(t.contains("November 2026"))
        assertTrue(t.contains("Siva: ₹1,000"), t)
        assertTrue(t.contains("Priya: ₹500"), t)
        assertTrue(t.contains("Saved 85%"), t)
    }

    @Test fun instrumentPages() {
        val rows = listOf(
            Triple(d(40_00, LocalDate.of(2026, 11, 5), merchant = "Southern De", account = "Pluxee meal card ••2793"), "Rs. 40.00 spent from Pluxee Meal Card", 13137_00L),
            Triple(d(130_00, LocalDate.of(2026, 11, 9), merchant = "Express avenue Mall parking", account = "FASTag ••D7C0"), "Equitas FasTag Toll charge", 100_00L),
            Triple(d(500_00, LocalDate.of(2026, 11, 9)), "UPI", null),
        )
        val meal = Instruments.view(Instrument.MEAL_CARD, rows, nov, zone)
        assertEquals(1, meal.entries.size)
        assertEquals(13137_00, meal.balanceMinor)
        val tag = Instruments.view(Instrument.FASTAG, rows, nov, zone)
        assertEquals(130_00, tag.spentThisMonthMinor)
        assertEquals(100_00, tag.balanceMinor)
    }

    @Test fun weeklyDigest() {
        val today = LocalDate.of(2026, 11, 9) // Monday
        val txns = listOf(d(1000_00, LocalDate.of(2026, 11, 3)), d(200_00, LocalDate.of(2026, 11, 8), "Shopping"), d(500_00, LocalDate.of(2026, 10, 28)), d(99_00, today))
        val w = Weekly.digest(txns, today, listOf(Triple("School fees", 25000_00L, today.plusDays(3)), Triple("Later", null, today.plusDays(20))), zone)
        assertEquals(LocalDate.of(2026, 11, 2), w.weekStart)
        assertEquals(1200_00, w.spentMinor)
        assertEquals(500_00, w.previousWeekMinor)
        assertEquals(1, w.dueNext.size)
        assertTrue(w.body.contains("₹700 more"), w.body)
    }
}

class OldStatementTest {
    /** ICICI savings statement from 2018: a "B/F" opening line with only a balance, and a cheque line split over two lines. */
    @Test fun broughtForwardLineDoesNotBreakReading() {
        val text = """
            Statement of Transactions in Savings Account XXXXXXXX4144 in INR for the period April 01, 2018 - March 31, 2019
            DATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE
            01-04-2018  B/F  1,75,321.70
            03-04-2018 NET BANKING BIL/001422771321/ChgRs10 GSTRs1.80 IRCTC/10000121 5,711.80 1,69,609.90
            04-04-2018 NET BANKING BIL/001425083379/RBI-NEFT/134410400002574 1,000.00 1,68,609.90
            30-04-2018  SAL/Salary - Apr 2018 1,55,803.00  3,24,412.90
        """.trimIndent()
        val st = StatementParser.fromText(text)!!
        assertEquals(StatementKind.BANK, st.kind)
        assertEquals(3, st.rows.size)
        assertEquals(TxnType.CREDIT, st.rows.last().type)
        assertTrue(st.check?.ok == true, st.check.toString())
    }
}
