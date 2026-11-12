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
}
