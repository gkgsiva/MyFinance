package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class MonthLedgerTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 10) =
        LocalDate.of(y, m, d).atTime(h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun t(id: Long, ts: Long, rupees: Long, type: TxnType, cat: String = "Food") =
        TxnLite(rupees * 100, type, cat, "M$id", null, ts, id = id)

    @Test
    fun runningBalanceOldestToNewestAndNewestDayFirst() {
        val txns = listOf(
            t(1, at(2026, 11, 1), 50_000, TxnType.CREDIT, "Income"),
            t(2, at(2026, 11, 1, 12), 1_000, TxnType.DEBIT),
            t(3, at(2026, 11, 3), 2_000, TxnType.DEBIT),
            t(4, at(2026, 10, 30), 999, TxnType.DEBIT), // last month
        )
        val l = MonthLedger.build(txns, YearMonth.of(2026, 11), MonthCycle.CALENDAR, zone)
        assertEquals(50_000_00, l.inMinor)
        assertEquals(3_000_00, l.outMinor)
        assertEquals(47_000_00, l.netMinor)
        assertEquals(listOf(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 1)), l.days.map { it.date })
        assertEquals(47_000_00, l.days[0].closingMinor)
        assertEquals(listOf(2L, 1L), l.days[1].rows.map { it.txn.id }) // newest first within a day
        assertEquals(listOf(49_000_00L, 50_000_00L), l.days[1].rows.map { it.balanceMinor })
    }

    @Test
    fun lateSalaryCountsForNextMonthAndHiddenStillCounts() {
        val cycle = MonthCycle(startDay = 1, salaryFromDay = 24)
        val txns = listOf(
            t(1, at(2026, 10, 27), 80_000, TxnType.CREDIT, DefaultCategories.SALARY),
            t(2, at(2026, 11, 5), 500, TxnType.DEBIT),
            t(3, at(2026, 11, 6), 200, TxnType.DEBIT),
            t(4, at(2026, 11, 27), 81_000, TxnType.CREDIT, DefaultCategories.SALARY), // December's
        )
        val l = MonthLedger.build(txns, YearMonth.of(2026, 11), cycle, zone, hiddenIds = setOf(2L))
        assertEquals(80_000_00, l.inMinor)
        assertEquals(700_00, l.outMinor)
        assertEquals(1, l.hiddenCount)
        assertEquals(2, l.rowCount)
        assertEquals(LocalDate.of(2026, 10, 27), l.days.last().date)
        assertEquals(79_300_00, l.days.first().rows.single().balanceMinor)
        assertEquals(null, l.days.firstOrNull { it.date == LocalDate.of(2026, 11, 5) }) // only a hidden row that day
    }

    private fun on(id: Long, ts: Long, rupees: Long, type: TxnType, account: String, cat: String = "Food") =
        TxnLite(rupees * 100, type, cat, "M$id", account, ts, id = id)

    @Test
    fun cardSpendsCountAsSpendingButLeaveTheBankBalanceUntilTheBillIsPaid() {
        val cards = setOf("5010", "7002")
        val txns = listOf(
            on(1, at(2026, 11, 1), 90_000, TxnType.CREDIT, "ICICI ••144", "Income"),
            on(2, at(2026, 11, 2), 2_000, TxnType.DEBIT, "ICICI ••5010"),      // on the card
            on(3, at(2026, 11, 3), 500, TxnType.DEBIT, "ICICI ••144"),         // from the bank
            on(4, at(2026, 11, 4), 300, TxnType.DEBIT, "••7002"),              // RuPay card via UPI
            on(5, at(2026, 11, 5), 200, TxnType.CREDIT, "ICICI ••5010"),       // refund on the card
        )
        val bill = on(6, at(2026, 11, 6), 40_000, TxnType.DEBIT, "ICICI ••144", "Card payment")
        val cardSide = on(7, at(2026, 11, 6, 11), 40_000, TxnType.CREDIT, "ICICI ••5010", "Card payment")
        val l = MonthLedger.build(txns, YearMonth.of(2026, 11), MonthCycle.CALENDAR, zone, cardTails = cards, cardBillPayments = listOf(bill, cardSide))
        assertEquals(2_800_00, l.outMinor) // all spending, cards included
        assertEquals(2_300_00, l.cardSpentMinor)
        assertEquals(2_100_00, l.cardDueMinor)
        assertEquals(40_000_00, l.cardBillPaidMinor)
        // Bank: 90,000 in, 500 spent, 40,000 card bill paid. Card spends and the card's refund don't move it.
        assertEquals(49_500_00, l.netMinor)
        assertEquals(90_200_00 - 2_800_00, l.leftMinor)
        val byId = l.days.flatMap { it.rows }.associateBy { it.txn.id }
        assertEquals(89_500_00, byId[4]!!.balanceMinor)
        assertEquals(MonthLedger.Kind.CARD, byId[2]!!.kind)
        assertEquals(MonthLedger.Kind.CARD_BILL, byId[6]!!.kind)
        assertEquals(null, byId[7]) // the card's side of the payment is not listed
        val day2 = l.days.single { it.date == LocalDate.of(2026, 11, 2) }
        assertEquals(2_000_00, day2.cardOutMinor)
        assertEquals(90_000_00, day2.closingMinor)
        assertEquals(40_000_00, l.days.single { it.date == LocalDate.of(2026, 11, 6) }.cardBillMinor)
    }

    @Test
    fun withoutKnownCardsEverythingMovesTheBalanceAsBefore() {
        val txns = listOf(
            on(1, at(2026, 11, 1), 1_000, TxnType.CREDIT, "ICICI ••144", "Income"),
            on(2, at(2026, 11, 2), 300, TxnType.DEBIT, "ICICI ••5010"),
        )
        val l = MonthLedger.build(txns, YearMonth.of(2026, 11), MonthCycle.CALENDAR, zone)
        assertEquals(700_00, l.netMinor)
        assertEquals(0, l.cardDueMinor)
    }
}
