package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1.16: reconciliation finder, import health, card due alerts, family report and limits, year-end report, attachments. */
class V116FeaturesTest {
    private val zone = ZoneOffset.UTC
    private val nov = YearMonth.of(2026, 11)
    private fun at(y: Int, m: Int, d: Int, h: Int = 10) =
        LocalDate.of(y, m, d).atTime(h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun t(
        id: Long, ts: Long, rupees: Long, type: TxnType = TxnType.DEBIT, cat: String = DefaultCategories.FOOD,
        merchant: String? = "M$id", account: String? = "ICICI ••0144", owner: String? = "Siva", mode: PaymentMode = PaymentMode.UPI,
    ) = TxnLite(rupees * 100, type, cat, merchant, account, ts, owner, id = id, mode = mode)

    // ---- Reconciliation ----

    @Test
    fun missingEntryIsFoundBetweenTheRightEntries() {
        // 10,000 opening. Spends 500 and 300; a 200 spend was never recorded, so the 3rd entry's balance is 200 lower.
        val rows = listOf(
            t(1, at(2026, 11, 1), 500), t(2, at(2026, 11, 3), 300), t(3, at(2026, 11, 6), 100),
        )
        val bal = mapOf(1L to 9_500_00L, 2L to 9_200_00L, 3L to 8_900_00L)
        val s = Reconcile.suspects("ICICI ••0144", rows, bal, zone)
        assertEquals(1, s.size)
        assertEquals(SuspectKind.MISSING, s[0].kind)
        assertEquals(200_00, s[0].amountMinor)
        assertEquals(TxnType.DEBIT, s[0].type)
        assertEquals(2L, s[0].afterId)
        assertEquals(3L, s[0].beforeId)
        assertTrue(s[0].message.contains("missing"))
    }

    @Test
    fun doubledEntryIsPointedAtWithItsTwin() {
        // The 300 spend was stored twice (SMS and statement), the bank moved it once.
        val rows = listOf(t(1, at(2026, 11, 1), 500), t(2, at(2026, 11, 3), 300), t(3, at(2026, 11, 3, 12), 300), t(4, at(2026, 11, 5), 100))
        val bal = mapOf(1L to 9_500_00L, 2L to 9_200_00L, 4L to 9_100_00L)
        val s = Reconcile.suspects("a", rows, bal, zone)
        assertEquals(1, s.size)
        assertEquals(SuspectKind.DOUBLED, s[0].kind)
        assertEquals(3L, s[0].entryId)
        assertEquals(2L, s[0].twinId)
    }

    @Test
    fun sameDayEntriesReadNewestFirstStillChain() {
        // A statement listed newest first gave ids in the other order on one day.
        val d = at(2026, 11, 2)
        val rows = listOf(t(1, at(2026, 11, 1), 100), t(3, d, 200), t(2, d, 50))
        val bal = mapOf(1L to 900_00L, 2L to 850_00L, 3L to 650_00L)
        assertEquals(listOf(1L, 2L, 3L), Reconcile.order(rows, bal, zone).map { it.id })
        assertTrue(Reconcile.suspects("a", rows, bal, zone).isEmpty())
    }

    @Test
    fun monthReportNamesSuspectsOnlyWhenBalancesDisagree() {
        val rows = listOf(t(1, at(2026, 11, 1), 500), t(2, at(2026, 11, 3), 300), t(3, at(2026, 11, 6), 100))
        val ok = MonthReport.build(rows, nov, zone = zone, balanceAfter = mapOf(1L to 9_500_00L, 2L to 9_200_00L, 3L to 9_100_00L))
        assertTrue(ok.suspects.isEmpty())
        val off = MonthReport.build(rows, nov, zone = zone, balanceAfter = mapOf(1L to 9_500_00L, 2L to 9_200_00L, 3L to 8_900_00L))
        assertEquals(-200_00, off.balances.single().differenceMinor)
        assertEquals(1, off.suspects.size)
        assertEquals(3, off.entryNumber(off.suspects[0].beforeId))
    }

    // ---- Import health ----

    @Test
    fun importHealthListsMissingMonthsAndUnmatchedBankMonths() {
        val stmt = listOf(
            t(1, at(2026, 7, 5), 100), t(2, at(2026, 9, 5), 100), t(3, at(2026, 9, 9), 100), t(4, at(2026, 10, 2), 100),
            t(9, at(2026, 8, 3), 50, account = "ICICI ••5010"),
        )
        val bal = mapOf(1L to 1_000_00L, 2L to 900_00L, 3L to 700_00L, 4L to 600_00L) // Sep: 100 missing before id 3
        val h = ImportHealth.build(stmt, stmt, bal, setOf("5010"), LocalDate.of(2026, 11, 14), zone)
        val bank = h.first { it.account == "ICICI ••0144" }
        assertEquals(listOf(YearMonth.of(2026, 8)), bank.missing)
        assertEquals(listOf(YearMonth.of(2026, 9)), bank.unmatched.map { it.month })
        assertEquals(-100_00, bank.unmatched[0].differenceMinor)
        assertEquals(SuspectKind.MISSING, bank.unmatched[0].suspects.single().kind)
        val card = h.first { it.account == "ICICI ••5010" }
        assertTrue(card.isCard)
        assertTrue(card.unmatched.isEmpty())
    }

    // ---- Card due alerts ----

    @Test
    fun cardAlertsBeforeAndOnTheBillDate() {
        val card = MyAccount(tail = "5010", bank = "ICICI", billDay = 14, payDay = 2)
        val lines = listOf(
            CardLine("5010", TxnType.DEBIT, 3_000_00, at(2026, 10, 20), false),
            CardLine("5010", TxnType.DEBIT, 1_000_00, at(2026, 11, 3), false),
            CardLine("5010", TxnType.CREDIT, 200_00, at(2026, 11, 5), false),
        )
        val before = LocalDate.of(2026, 11, 12)
        val v = CardCycles.view(card, lines, before, zone)!!
        assertEquals(LocalDate.of(2026, 11, 14), v.nextBillDate)
        val pre = CardCycles.alerts(v, before)
        assertEquals(1, pre.size)
        assertTrue(pre[0].title.contains("₹3,800"), pre[0].title)
        assertTrue(pre[0].key.startsWith("cardpre|5010|2026-11-14"))

        val billDay = LocalDate.of(2026, 11, 14)
        val v2 = CardCycles.view(card, lines, billDay, zone)!!
        val made = CardCycles.alerts(v2, billDay)
        assertEquals(1, made.size)
        assertTrue(made[0].title.contains("₹3,800 due by 2 Dec"), made[0].title)
        // Too early, and nothing once paid.
        assertTrue(CardCycles.alerts(CardCycles.view(card, lines, LocalDate.of(2026, 11, 8), zone)!!, LocalDate.of(2026, 11, 8)).isEmpty())
        val dayAfter = billDay.plusDays(1)
        assertEquals(1, CardCycles.alerts(CardCycles.view(card, lines, dayAfter, zone)!!, dayAfter).size)
        val paid = lines + CardLine("5010", TxnType.CREDIT, 3_800_00, at(2026, 11, 15, 9), true)
        assertTrue(CardCycles.alerts(CardCycles.view(card, paid, dayAfter, zone)!!, dayAfter).none { it.key.startsWith("cardmade") })
    }

    // ---- Family report and limits per person ----

    private val family = listOf(
        t(1, at(2026, 11, 1), 1_00_000, TxnType.CREDIT, DefaultCategories.SALARY, owner = "Siva"),
        t(2, at(2026, 11, 2), 4_000, owner = "Siva"),
        t(3, at(2026, 11, 3), 9_000, owner = "Priya"),
        t(4, at(2026, 11, 4), 5_000, cat = DefaultCategories.INVESTMENTS, owner = "Priya"),
        t(5, at(2026, 11, 5), 1_000, cat = DefaultCategories.SHOPPING, owner = null),
        t(6, at(2026, 10, 5), 700, owner = "Asha"),
    )

    @Test
    fun familyReportKeepsEachPersonApartAndInvestmentsOutOfSpending() {
        val r = FamilyReport.build(family, nov, "Siva", zone = zone, limits = mapOf("Priya" to 10_000_00L))
        assertEquals("Siva", r.people.first().name)
        val siva = r.people.first { it.name == "Siva" }
        assertEquals(5_000_00, siva.spentMinor) // own 4,000 + 1,000 with no owner
        val priya = r.people.first { it.name == "Priya" }
        assertEquals(9_000_00, priya.spentMinor)
        assertEquals(5_000_00, priya.investedMinor)
        assertEquals(90, priya.limitPercent)
        assertEquals(14_000_00, r.spentMinor)
        assertEquals(mapOf("Siva" to 4_000_00L, "Priya" to 9_000_00L), r.categories.first { it.category == DefaultCategories.FOOD }.byPerson)
        assertEquals(LocalDate.of(2026, 10, 5), r.lastEntry["Asha"])
    }

    @Test
    fun personLimitsWarnAt80AndRoundTrip() {
        val spent = PersonBudgets.spent(family, nov, "Siva", zone = zone)
        assertEquals(9_000_00, spent["Priya"])
        val lines = PersonBudgets.lines(spent, mapOf("Priya" to 10_000_00L, "Siva" to 20_000_00L))
        assertEquals(80, lines.first { it.person == "Priya" }.level)
        assertNull(lines.first { it.person == "Siva" }.level)
        val alerts = BudgetAlerts.due(spent.mapKeys { PersonBudgets.alertKey(it.key) }, mapOf("@Priya" to 10_000_00L), emptySet(), nov)
        assertEquals("Priya's spending", alerts.single().title)
        assertTrue(alerts.single().message.startsWith("Priya has used 90%"))
        val m = mapOf("Priya" to 10_000_00L, "Siva Kumar" to 5L)
        assertEquals(m, PersonBudgets.decode(PersonBudgets.encode(m)))
        assertTrue(PersonBudgets.decode("junk\nx\tnope").isEmpty())
    }

    // ---- Year-end report and attachments ----

    @Test
    fun yearReportAddsUpTwelveMonthsAndListsReceipts() {
        val txns = listOf(
            t(1, at(2026, 4, 1), 50_000, TxnType.CREDIT, DefaultCategories.SALARY),
            t(2, at(2026, 4, 2), 10_000, cat = DefaultCategories.RENT),
            t(3, at(2026, 5, 3), 2_000),
            t(4, at(2026, 6, 4), 5_000, cat = DefaultCategories.INVESTMENTS, merchant = "Zerodha SIP"),
            t(5, at(2027, 3, 10), 1_500, cat = DefaultCategories.SHOPPING),
            t(6, at(2027, 4, 1), 9_999), // next year
        )
        val files = mapOf(3L to listOf("r-1.jpg", "r-2.pdf"))
        val y = YearReport.build(2026, true, { m -> MonthReport.build(txns, m, zone = zone, attachmentsById = files) })
        assertEquals("FY 2026-27", y.label)
        assertEquals(12, y.months.size)
        assertEquals(YearMonth.of(2026, 4), y.months.first().month)
        assertEquals(50_000_00, y.incomeMinor)
        assertEquals(13_500_00, y.spentMinor)
        assertEquals(5_000_00, y.investedMinor)
        assertEquals(1, y.attachments.size)
        assertEquals(listOf("r-1.jpg", "r-2.pdf"), y.attachments[0].attachments)
        assertEquals("Zerodha SIP", y.investments.single().key)
        assertTrue(y.insights.any { it.contains("no entries") })

        val cal = YearReport.build(2026, false, { m -> MonthReport.build(txns, m, zone = zone) })
        assertEquals("2026", cal.label)
        assertEquals(YearMonth.of(2026, 1), cal.firstMonth)
    }

    @Test
    fun monthReportIndexesAttachmentsCountedOrNot() {
        val rows = listOf(t(1, at(2026, 11, 1), 500), t(2, at(2026, 11, 3), 300))
        val moved = listOf(t(7, at(2026, 11, 4), 1_000, cat = DefaultCategories.TRANSFERS))
        val r = MonthReport.build(rows, nov, zone = zone, notCounted = moved, attachmentsById = mapOf(2L to listOf("a.jpg"), 7L to listOf("b.pdf")))
        assertEquals(listOf(2L, 7L), r.attachmentIndex.map { it.txn.id })
        assertNotNull(r.entryNumber(2L))
        assertNull(r.entryNumber(7L))
    }
}
