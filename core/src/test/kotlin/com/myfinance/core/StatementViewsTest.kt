package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class StatementViewsTest {
    private val zone = ZoneOffset.UTC
    private var n = 0L
    private fun at(m: Int, d: Int, h: Int = 10) = LocalDate.of(2026, m, d).atTime(h, 0).toInstant(zone).toEpochMilli()
    private fun row(type: TxnType, amount: Long, ts: Long, tail: String?, stmt: Boolean = false, excluded: Boolean = false) =
        LedgerRow(++n, type, amount, ts, tail, "x$n", stmt, excluded)

    @Test fun cardCycleRunsFromTheDayAfterOneBillToTheNext() {
        assertEquals(LocalDate.of(2026, 10, 15) to LocalDate.of(2026, 11, 14), StatementViews.cardCycle(14, LocalDate.of(2026, 11, 13)))
        assertEquals(LocalDate.of(2026, 10, 15) to LocalDate.of(2026, 11, 14), StatementViews.cardCycle(14, LocalDate.of(2026, 11, 14)))
        assertEquals(LocalDate.of(2026, 11, 15) to LocalDate.of(2026, 12, 14), StatementViews.cardCycle(14, LocalDate.of(2026, 11, 15)))
        // Bill on the 31st in a 30-day month.
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 30), StatementViews.cardCycle(31, LocalDate.of(2026, 11, 10)))
    }

    @Test fun oneCardsEntriesInThePeriodWithARunningTotal() {
        val rows = listOf(
            row(TxnType.DEBIT, 2_576_71, at(11, 4, 14), "XX7002"),
            row(TxnType.DEBIT, 998_00, at(11, 4, 19), "7002", stmt = true),
            row(TxnType.CREDIT, 40_000_00, at(11, 1), "7002", stmt = true, excluded = true), // bill payment: still listed
            row(TxnType.DEBIT, 500_00, at(11, 5), "5010"), // another card
            row(TxnType.DEBIT, 100_00, at(10, 14), "7002"), // last cycle
            row(TxnType.DEBIT, 50_00, at(11, 6), null),
        )
        val s = StatementViews.build(rows, "7002", LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 14), zone)
        assertEquals(3, s.lines.size)
        assertEquals(3_574_71, s.debitsMinor)
        assertEquals(40_000_00, s.creditsMinor)
        assertEquals(listOf(40_000_00L, 37_423_29L, 36_425_29L), s.lines.map { it.runningMinor })
        assertEquals(2, s.fromStatementCount)
        assertEquals(1, s.fromSmsCount)
    }

    @Test fun bankAccountMatchesItsThreeDigitSmsTail() {
        val rows = listOf(row(TxnType.CREDIT, 1_00_000_00, at(11, 3), "XX144"), row(TxnType.DEBIT, 5_000_00, at(11, 3), "4144", stmt = true))
        val s = StatementViews.build(rows, "4144", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), zone)
        assertEquals(2, s.lines.size)
        assertEquals(95_000_00, s.netMinor)
    }
}
