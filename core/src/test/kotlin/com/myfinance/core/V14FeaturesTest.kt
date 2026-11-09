package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
private fun ts(d: LocalDate) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
private var nextId = 1L
private fun debit(rupees: Long, merchant: String?, d: LocalDate, category: String = DefaultCategories.OTHER, account: String? = null, owner: String? = null) =
    TxnLite(rupees * 100, TxnType.DEBIT, category, merchant, account, ts(d), owner, id = nextId++)
private fun credit(rupees: Long, merchant: String?, d: LocalDate, category: String = DefaultCategories.INCOME, account: String? = null, owner: String? = null) =
    TxnLite(rupees * 100, TxnType.CREDIT, category, merchant, account, ts(d), owner, id = nextId++)

class LabelSuggestTest {
    private val d = LocalDate.of(2026, 10, 1)

    @Test fun suggestsSameSellerAndSharedWord() {
        val a = debit(120, "SRI BALAJI VEGETABLES", d, DefaultCategories.GROCERIES)
        val b = debit(80, "Murugan Vegetables", d.plusDays(2), DefaultCategories.GROCERIES)
        val sameSeller = debit(150, "SRI BALAJI VEGETABLES", d.plusDays(5), DefaultCategories.GROCERIES)
        val word = debit(90, "Kumar Vegetables Shop", d.plusDays(6), DefaultCategories.GROCERIES)
        val other = debit(5000, "Amazon", d.plusDays(7), DefaultCategories.SHOPPING)
        val labels = mapOf(a.id to listOf("Vegetables"), b.id to listOf("Vegetables"))
        val s = LabelSuggest.suggest(listOf(a, b, sameSeller, word, other), labels).associateBy { it.txnId }
        assertEquals("Vegetables", s[sameSeller.id]?.label)
        assertEquals(3, s[sameSeller.id]?.strength)
        assertEquals("Vegetables", s[word.id]?.label)
        assertNull(s[other.id])
    }

    @Test fun labelNameInPayee() {
        val a = debit(100, "ravi.k@okaxis", d)
        val b = debit(60, "lakshmi@ybl", d)
        val veg = debit(70, "ravi.veg@okicici", d)
        val s = LabelSuggest.suggest(listOf(a, b, veg), mapOf(a.id to listOf("Vegetables"), b.id to listOf("Vegetables")))
        assertEquals(listOf(veg.id), s.map { it.txnId })
    }

    @Test fun needsTwoExamplesAndRespectsDismissed() {
        val a = debit(100, "Amazon", d)
        val b = debit(200, "Amazon", d.plusDays(1))
        assertTrue(LabelSuggest.suggest(listOf(a, b), mapOf(a.id to listOf("Amazon"))).isEmpty())
        val c = debit(300, "AMAZON PAY INDIA", d.plusDays(2))
        val labels = mapOf(a.id to listOf("Amazon"), b.id to listOf("Amazon"))
        assertEquals(1, LabelSuggest.suggest(listOf(a, b, c), labels).size)
        assertTrue(LabelSuggest.suggest(listOf(a, b, c), labels, dismissed = setOf(c.id to "Amazon")).isEmpty())
    }
}

class CardBillsTest {
    private val due = LocalDate.of(2026, 10, 15)
    private val bill = BillDue(BillKind.CREDIT_CARD, 20_000_00, 1_000_00, due, "1234", "HDFC Bank")
    private val arrived = due.minusDays(18)

    @Test fun paidInFullOnTime() {
        val pay = credit(20_000, "Payment received", due.minusDays(2), account = "HDFC Bank ••1234")
        val c = CardBills.check(listOf(Triple(bill, arrived, false)), listOf(pay), due.plusDays(10), zone).single()
        assertEquals(CardBillStatus.PAID, c.status)
        assertEquals(0, c.gapMinor)
    }

    @Test fun lateAndPartial() {
        val late = credit(20_000, "Payment", due.plusDays(4), account = "••1234")
        assertEquals(CardBillStatus.PAID_LATE, CardBills.check(listOf(Triple(bill, arrived, false)), listOf(late), due.plusDays(10), zone).single().status)

        val part = credit(8_000, "Payment", due.minusDays(1), account = "••1234")
        val c = CardBills.check(listOf(Triple(bill, arrived, false)), listOf(part), due.plusDays(10), zone).single()
        assertEquals(CardBillStatus.PARTLY_PAID, c.status)
        assertEquals(12_000_00, c.gapMinor)
        assertEquals(10, c.daysLate)

        val min = credit(1_000, "Payment", due.minusDays(1), account = "••1234")
        assertEquals(CardBillStatus.MIN_ONLY, CardBills.check(listOf(Triple(bill, arrived, false)), listOf(min), due.plusDays(3), zone).single().status)
        assertEquals(CardBillStatus.UNPAID, CardBills.check(listOf(Triple(bill, arrived, false)), emptyList(), due.plusDays(3), zone).single().status)
        assertEquals(CardBillStatus.NOT_DUE, CardBills.check(listOf(Triple(bill, arrived, false)), emptyList(), due.minusDays(3), zone).single().status)
    }

    @Test fun paidFromBankAccountCountsAndPaymentsAreNotReused() {
        val fromBank = debit(20_000, "CC PAYMENT XX1234 BILLDESK", due.minusDays(3), account = "SBI ••5678")
        val next = bill.copy(dueDate = due.plusMonths(1))
        val checks = CardBills.check(listOf(Triple(bill, arrived, false), Triple(next, arrived.plusMonths(1), false)), listOf(fromBank), due.plusMonths(1).plusDays(5), zone)
        assertEquals(CardBillStatus.PAID, checks.first { it.bill.dueDate == due }.status)
        assertEquals(CardBillStatus.UNPAID, checks.first { it.bill.dueDate == next.dueDate }.status)
    }

    @Test fun readsSummaryFromStatementText() {
        val inline = "HDFC Bank Credit Card Statement\nTotal Amount Due: Rs. 12,345.60\nMinimum Amount Due: 620.00\nPayment Due Date: 05/12/2026"
        assertEquals(Triple(12_345_60L, 620_00L, LocalDate.of(2026, 12, 5)), CardBills.summaryFromText(inline, LocalDate.of(2026, 11, 15)))
        val table = "Payment Due Date Total Amount Due Minimum Amount Due\n05/12/2026 12,345.60 620.00"
        assertEquals(Triple(12_345_60L, 620_00L, LocalDate.of(2026, 12, 5)), CardBills.summaryFromText(table, LocalDate.of(2026, 11, 15)))
        assertNull(CardBills.summaryFromText("Opening balance 1,000.00", LocalDate.of(2026, 11, 15)))
    }
}

class LoanPayoffTest {
    @Test fun emiMathRoundTrips() {
        // ₹10 lakh at 9% for 120 months: EMI about ₹12,668.
        val emi = LoanPayoff.emiFor(10_00_000_00, 9.0, 120)
        assertTrue(emi in 12_660_00..12_675_00, "emi $emi")
        val pv = LoanPayoff.presentValue(emi, 9.0, 120)
        assertTrue(kotlin.math.abs(pv - 10_00_000_00) < 100_00)
        assertEquals(120, LoanPayoff.monthsFor(10_00_000_00, 9.0, emi))
    }

    @Test fun prepaymentSavesInterestAndMonths() {
        val emi = LoanPayoff.emiFor(10_00_000_00, 9.0, 120)
        val s = LoanPayoff.status(emi, 9.0, instalmentsLeft = 84, instalmentsPaid = 36, endDate = LocalDate.of(2033, 10, 5))
        assertTrue(s.interestPaidMinor > 0 && s.interestLeftMinor > 0)
        val p = LoanPayoff.prepay(s, 2_00_000_00)
        assertTrue(p.monthsSaved in 15..30, "months saved ${p.monthsSaved}")
        assertTrue(p.interestSavedMinor > 50_000_00, "saved ${p.interestSavedMinor}")
        assertTrue(p.reducedEmiMinor < emi)
        assertEquals(s.endDate!!.minusMonths(p.monthsSaved.toLong()), p.newEndDate)
    }

    @Test fun endingsAndFreedMoney() {
        val items = listOf(
            AutoDebitItem("plan:a", "Car loan", AutoDebitKind.EMI, 15_000_00, "Monthly", LocalDate.of(2026, 12, 5), 20, DueState.UPCOMING, AutoDebitSource.MANUAL,
                remaining = 6, endDate = LocalDate.of(2027, 5, 5), planId = "a"),
            AutoDebitItem("plan:b", "Phone EMI", AutoDebitKind.EMI, 3_000_00, "Monthly", LocalDate.of(2026, 12, 10), 25, DueState.UPCOMING, AutoDebitSource.MANUAL,
                remaining = 2, endDate = LocalDate.of(2027, 1, 10), planId = "b"),
        )
        val ends = LoanPayoff.endings(items)
        assertEquals(listOf("Phone EMI", "Car loan"), ends.map { it.name })
        assertEquals(90_000_00, ends.last().totalLeftMinor)
        assertEquals(listOf(YearMonth.of(2027, 2) to 3_000_00L, YearMonth.of(2027, 6) to 15_000_00L), LoanPayoff.freedByMonth(ends))
    }
}

class SalaryBreakdownTest {
    @Test fun findsRaiseBonusAndGrowth() {
        val rows = ArrayList<TxnLite>()
        var m = YearMonth.of(2024, 4)
        while (m <= YearMonth.of(2026, 3)) {
            val base = if (m >= YearMonth.of(2025, 4)) 1_10_000L else 1_00_000L
            val amt = if (m == YearMonth.of(2025, 3)) base + 2_00_000 else base
            rows += credit(amt, "ACME PAYROLL", m.atDay(1), DefaultCategories.SALARY)
            rows += debit(60_000, "Spend", m.atDay(10))
            m = m.plusMonths(1)
        }
        val r = SalaryBreakdown.report(rows, emptyMap(), financialYear = true, zone = zone)
        assertEquals(listOf(YearMonth.of(2025, 3)), r.bonuses.map { it.month })
        assertEquals(2_00_000_00, r.bonuses.single().extraMinor)
        assertEquals(YearMonth.of(2025, 4), r.raises.single().month)
        assertEquals(10.0, r.raises.single().pct, 0.01)
        assertEquals(2, r.years.size)
        assertNotNull(r.years.last().incomeGrowthPct)
        assertEquals(1_10_000_00, r.currentMonthlyMinor)
    }
}

class RefundsTest {
    private val d = LocalDate.of(2026, 10, 1)

    @Test fun matchesRefundFromSameSeller() {
        val ticket = debit(2_000, "IRCTC", d)
        val refund = credit(1_800, "IRCTC REFUND", d.plusDays(4))
        val items = Refunds.track(listOf(RefundWatch(ticket.id, 1_800_00, ts(d))), listOf(ticket, refund), d.plusDays(5), 10, zone)
        assertEquals(RefundState.RECEIVED, items.single().state)
        assertEquals(4, items.single().daysWaiting)
    }

    @Test fun flagsOverdueAndPartial() {
        val order = debit(3_000, "Amazon", d)
        val w = RefundWatch(order.id, null, ts(d))
        assertEquals(RefundState.WAITING, Refunds.track(listOf(w), listOf(order), d.plusDays(5), 10, zone).single().state)
        assertEquals(RefundState.OVERDUE, Refunds.track(listOf(w), listOf(order), d.plusDays(12), 10, zone).single().state)
        val part = credit(1_000, "AMAZON PAY", d.plusDays(2))
        val i = Refunds.track(listOf(w), listOf(order, part), d.plusDays(5), 10, zone).single()
        assertEquals(RefundState.PARTLY, i.state)
        assertEquals(2_000_00, i.pendingMinor)
    }

    @Test fun candidatesAreRecentRefundSellers() {
        val t = debit(500, "IRCTC", LocalDate.now().minusDays(3))
        val shop = debit(500, "Local kirana", LocalDate.now().minusDays(3))
        assertEquals(listOf(t.id), Refunds.candidates(listOf(t, shop), emptySet(), LocalDate.now()).map { it.id })
    }
}

class YearReviewTest {
    @Test fun buildsReviewAndShareText() {
        val rows = ArrayList<TxnLite>()
        for (m in 1..12) {
            rows += credit(1_00_000, "Salary", LocalDate.of(2025, m, 1), DefaultCategories.SALARY)
            for (k in 0 until 8) rows += debit(300, "Swiggy", LocalDate.of(2025, m, 2 + k), DefaultCategories.FOOD)
            rows += debit(if (m == 10) 80_000 else 30_000, "Amazon", LocalDate.of(2025, m, 20), DefaultCategories.SHOPPING)
        }
        val r = YearReview.build(rows, emptyMap(), 2025, financialYear = false, zone = zone)
        assertEquals(12_00_000_00, r.incomeMinor)
        assertEquals("Amazon", r.topSellers.first().name)
        assertEquals("Swiggy", r.mostVisited.first().name)
        assertEquals(YearMonth.of(2025, 10), r.biggestMonth?.first)
        assertTrue(r.facts.any { it.contains("Swiggy 96 times") })
        val text = YearReview.shareText(r, "Siva")
        assertTrue(text.startsWith("*Siva's 2025 in review*"))
        assertTrue(text.contains("Top sellers"))
        assertEquals(listOf(2025), YearReview.years(rows, false, zone))
    }
}

class StatementRemindersTest {
    @Test fun gapsAndDue() {
        val today = LocalDate.of(2026, 11, 6)
        val rows = listOf(
            debit(100, "x", LocalDate.of(2026, 10, 30), account = "HDFC Bank ••1234"),
            debit(100, "y", LocalDate.of(2026, 9, 25), account = "ICICI card ••9999"),
            debit(100, "z", LocalDate.of(2024, 1, 5), account = "Old ••0000"),
        )
        assertEquals(listOf("ICICI card ••9999"), StatementReminders.gaps(rows, today, zone).map { it.account })
        assertTrue(StatementReminders.isDue(today, 5, YearMonth.of(2026, 10)))
        assertTrue(!StatementReminders.isDue(today, 5, YearMonth.of(2026, 11)))
        assertTrue(!StatementReminders.isDue(today, 0, null))
        assertEquals("Import your October statements", StatementReminders.message(emptyList(), today).first)
    }
}

class FamilyLabelsTest {
    @Test fun labelByPersonNetsRefunds() {
        val d = LocalDate.of(2026, 10, 1)
        val a = debit(500, "Veg", d)
        val b = debit(300, "Veg", d, owner = "Priya")
        val c = debit(1_000, "IRCTC", d, owner = "Priya")
        val r = credit(400, "IRCTC REFUND", d.plusDays(1), owner = "Priya")
        val labels = mapOf(a.id to listOf("Vegetables"), b.id to listOf("Vegetables"), c.id to listOf("Travel"), r.id to listOf("Travel"))
        val (people, rows) = FamilyLabels.table(listOf(a, b, c, r), labels, "Siva")
        assertEquals(listOf("Siva", "Priya"), people)
        val veg = rows.first { it.label == "Vegetables" }
        assertEquals(mapOf("Siva" to 500_00L, "Priya" to 300_00L), veg.byPerson)
        assertEquals(600_00, rows.first { it.label == "Travel" }.totalMinor)
    }

    @Test fun labelsTravelInExport() {
        val t = SharedTxn("u1", 100, TxnType.DEBIT, "Veg", "Groceries", null, null, 0, null, "Priya", "SMS", null, null, false, labels = listOf("Vegetables", "Daily"))
        val back = CsvCodec.decode(CsvCodec.encode(listOf(t))).first.single()
        assertEquals(listOf("Vegetables", "Daily"), back.labels)
        val none = CsvCodec.decode(CsvCodec.encode(listOf(t.copy(labels = emptyList())))).first.single()
        assertEquals(emptyList(), none.labels)
    }
}
