package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonthCycleTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)

    private fun salary(y: Int, m: Int, d: Int, amount: Long = 10_000_000) =
        TxnLite(amount, TxnType.CREDIT, DefaultCategories.SALARY, "ACME", null, at(y, m, d))

    private fun spend(y: Int, m: Int, d: Int, amount: Long = 100_000, category: String = "Food") =
        TxnLite(amount, TxnType.DEBIT, category, "Shop", null, at(y, m, d))

    @Test
    fun calendarIsDefault() {
        val c = MonthCycle.CALENDAR
        assertTrue(c.isCalendar)
        assertEquals(oct, c.periodOf(salary(2026, 10, 28), zone))
        assertEquals(LocalDate.of(2026, 11, 1), c.start(nov))
        assertEquals(LocalDate.of(2026, 11, 30), c.end(nov))
        assertNull(c.rangeLabel(nov))
    }

    @Test
    fun startDayMovesWholeMonth() {
        val c = MonthCycle(startDay = 25)
        assertEquals(LocalDate.of(2026, 10, 25), c.start(nov))
        assertEquals(LocalDate.of(2026, 11, 24), c.end(nov))
        assertEquals(nov, c.periodOf(salary(2026, 10, 27), zone))
        assertEquals(nov, c.periodOf(spend(2026, 10, 25), zone))
        assertEquals(oct, c.periodOf(spend(2026, 10, 24), zone))
        assertEquals(nov, c.current(LocalDate.of(2026, 11, 24)))
        assertEquals(YearMonth.of(2026, 12), c.current(LocalDate.of(2026, 11, 25)))
        assertEquals("25 Oct – 24 Nov", c.rangeLabel(nov))
        // December's late days roll into January of the next year.
        assertEquals(YearMonth.of(2027, 1), c.periodOfDate(LocalDate.of(2026, 12, 30)))
    }

    @Test
    fun salaryShiftOnlyMovesSalary() {
        val c = MonthCycle(salaryFromDay = 25)
        assertFalse(c.isCalendar)
        assertEquals(nov, c.periodOf(salary(2026, 10, 25), zone))
        assertEquals(nov, c.periodOf(salary(2026, 10, 31), zone))
        // Salary paid early (before the 25th, a Wednesday in November) stays in its month.
        assertEquals(nov, c.periodOf(salary(2026, 11, 24), zone))
        // 25 October is a Sunday: a salary paid on Friday the 23rd counts for November, the 22nd doesn't.
        assertEquals(nov, c.periodOf(salary(2026, 10, 23), zone))
        assertEquals(oct, c.periodOf(salary(2026, 10, 22), zone))
        // Spending and other credits stay in their calendar month.
        assertEquals(oct, c.periodOf(spend(2026, 10, 28), zone))
        val refund = TxnLite(5_000, TxnType.CREDIT, "Refund", "Shop", null, at(2026, 10, 28))
        assertEquals(oct, c.periodOf(refund, zone))
        // December's salary is January's income.
        assertEquals(YearMonth.of(2027, 1), c.periodOf(salary(2026, 12, 29), zone))
    }

    @Test
    fun salaryPaidOnFridayBeforeWeekendPayDayCountsForNextMonth() {
        // Siva's case: salary from the 25th, 25 July 2026 is a Saturday, the salary came on Friday the 24th.
        val c = MonthCycle(salaryFromDay = 25)
        val aug = YearMonth.of(2026, 8)
        assertEquals(aug, c.periodOf(salary(2026, 7, 24), zone))
        assertEquals("salary from 24 Jul counts here", c.note(aug))
        assertTrue(at(2026, 7, 24) in c.loadRange(aug, zone))
        // A weekday from-day is unchanged: 25 August 2026 is a Tuesday.
        assertEquals("salary from 25 Aug counts here", c.note(YearMonth.of(2026, 9)))
        assertEquals(aug, c.periodOf(salary(2026, 8, 24), zone))
    }

    @Test
    fun bothSettingsTogetherNeverSkipAMonth() {
        val c = MonthCycle(startDay = 25, salaryFromDay = 20)
        // 22 Oct is before the start day, but salary from the 20th counts for November.
        assertEquals(nov, c.periodOf(salary(2026, 10, 22), zone))
        // 27 Oct is already in November's cycle, and stays there (not December).
        assertEquals(nov, c.periodOf(salary(2026, 10, 27), zone))
    }

    @Test
    fun loadRangeCoversShiftedSalary() {
        val c = MonthCycle(salaryFromDay = 25)
        val r = c.loadRange(nov, zone)
        assertTrue(at(2026, 10, 28) in r)
        assertTrue(at(2026, 11, 30) in r)
        assertFalse(at(2026, 12, 1) in r)
        val plain = MonthCycle(startDay = 25).loadRange(nov, zone)
        assertTrue(at(2026, 10, 25) in plain)
        assertFalse(at(2026, 10, 24) in plain)
        assertFalse(at(2026, 11, 25) in plain)
    }

    @Test
    fun monthlyReportUsesCycle() {
        val txns = listOf(
            salary(2026, 9, 28),
            spend(2026, 10, 5, 2_000_000),
            salary(2026, 10, 29),
            spend(2026, 11, 3, 3_000_000),
        )
        val calendar = Reports.monthly(txns, nov, count = 2, zone = zone)
        assertEquals(10_000_000, calendar[0].incomeMinor) // October has October's salary
        assertEquals(0, calendar[1].incomeMinor) // November shows no income yet

        val shifted = Reports.monthly(txns, nov, count = 2, zone = zone, cycle = MonthCycle(salaryFromDay = 25))
        assertEquals(10_000_000, shifted[0].incomeMinor) // September's late salary pays for October
        assertEquals(2_000_000, shifted[0].expenseMinor)
        assertEquals(80, shifted[0].savingsRatePercent)
        assertEquals(10_000_000, shifted[1].incomeMinor)
        assertEquals(70, shifted[1].savingsRatePercent)
    }

    @Test
    fun rejectsBadDays() {
        assertFailsWith<IllegalArgumentException> { MonthCycle(startDay = 29) }
        assertFailsWith<IllegalArgumentException> { MonthCycle(salaryFromDay = 1) }
    }
}
