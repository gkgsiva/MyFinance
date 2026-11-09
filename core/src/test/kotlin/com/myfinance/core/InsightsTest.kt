package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InsightsTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun salary(y: Int, m: Int, amt: Long) = TxnLite(amt, TxnType.CREDIT, DefaultCategories.SALARY, "ACME", null, at(y, m, 1))

    @Test
    fun salaryRaiseYearOnYear() {
        val txns = (1..12).map { salary(2024, it, 100_000) } + (1..12).map { salary(2025, it, 110_000) } +
            TxnLite(50_000, TxnType.CREDIT, DefaultCategories.INCOME, "Interest", null, at(2025, 6, 3)) +
            TxnLite(9_999, TxnType.DEBIT, "Food", "X", null, at(2025, 6, 3))
        val years = Insights.incomeByYear(txns, financialYear = false, zone = zone)
        assertEquals(listOf("2024", "2025"), years.map { it.label })
        assertNull(years[0].salaryChangePct)
        assertEquals(10.0, years[1].salaryChangePct!!, 0.001)
        assertEquals(110_000L * 12 + 50_000, years[1].incomeMinor)
        assertEquals(12, years[1].salaryMonths)
    }

    @Test
    fun financialYearsSplitInApril() {
        val years = Insights.incomeByYear(listOf(salary(2025, 3, 1), salary(2025, 4, 2)), financialYear = true, zone = zone)
        assertEquals(listOf("FY 2024-25", "FY 2025-26"), years.map { it.label })
    }

    @Test
    fun trendIncludesEmptyMonths() {
        val t = listOf(TxnLite(100, TxnType.DEBIT, "Food", "Ravi", null, at(2025, 1, 5)), TxnLite(50, TxnType.DEBIT, "Food", "Ravi", null, at(2025, 3, 5)))
        val m = Insights.trend(t, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31), Granularity.MONTH, zone)
        assertEquals(listOf(100L, 0L, 50L), m.map { it.totalMinor })
        val y = Insights.trend(t, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 3, 31), Granularity.YEAR, zone)
        assertEquals(listOf(0L, 150L), y.map { it.totalMinor })
        val w = Insights.trend(t, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 20), Granularity.WEEK, zone)
        assertEquals("2024-12-30", w.first().label) // the Monday of that week
        assertEquals(100L, w.first().totalMinor)
    }

    @Test
    fun factsFindRecordMonthForLabel() {
        fun veg(m: Int, amt: Long) = TxnLite(amt, TxnType.DEBIT, "Groceries", "Ravi", null, at(2025, m, 10), id = m.toLong())
        val txns = listOf(veg(1, 100_000), veg(2, 120_000), veg(3, 90_000), veg(4, 300_000))
        val labels = txns.associate { it.id to listOf("Vegetables") }
        val facts = Insights.facts(txns, labels, java.time.YearMonth.of(2025, 4), zone)
        val titles = facts.map { it.title }
        kotlin.test.assertTrue("Vegetables: highest month ever" in titles, titles.toString())
        kotlin.test.assertTrue(titles.any { it.startsWith("Vegetables up") }, titles.toString())
    }
}
