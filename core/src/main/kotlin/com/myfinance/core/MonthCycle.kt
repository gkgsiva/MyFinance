package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Which month a transaction counts towards in the Overview, budgets and reports.
 *
 * Two independent settings, for a salary that arrives near the end of the month:
 *  - [startDay]: the budget month starts on this day. With 25, "November" runs from 25 October
 *    to 24 November. 1 is the normal calendar month.
 *  - [salaryFromDay]: a credit in the Salary category on or after this day of the month counts for
 *    the next month (salary on 28 October is November's income). Spending stays in its own month.
 *    Null turns it off.
 */
data class MonthCycle(val startDay: Int = 1, val salaryFromDay: Int? = null) {

    init {
        require(startDay in 1..MAX_START_DAY) { "startDay must be 1..$MAX_START_DAY" }
        require(salaryFromDay == null || salaryFromDay in 2..31) { "salaryFromDay must be 2..31" }
    }

    val isCalendar: Boolean get() = startDay == 1 && salaryFromDay == null

    /** The budget month a plain date (not a shifted salary) falls in. */
    fun periodOfDate(date: LocalDate): YearMonth {
        val ym = YearMonth.from(date)
        return if (startDay > 1 && date.dayOfMonth >= startDay) ym.plusMonths(1) else ym
    }

    /** The budget month a transaction counts towards. */
    fun periodOf(epochMillis: Long, type: TxnType, category: String, zone: ZoneId): YearMonth {
        var date = Dates.localDate(epochMillis, zone)
        if (shiftsSalary(date, type, category)) date = date.withDayOfMonth(1).plusMonths(1)
        return periodOfDate(date)
    }

    fun periodOf(t: TxnLite, zone: ZoneId): YearMonth = periodOf(t.timestamp, t.type, t.category, zone)

    /** True when this salary credit is moved to the next month. */
    fun shiftsSalary(date: LocalDate, type: TxnType, category: String): Boolean =
        salaryFromDay != null && type == TxnType.CREDIT &&
            category.equals(DefaultCategories.SALARY, ignoreCase = true) && date.dayOfMonth >= salaryFromDay

    /** First day of the budget month [period]. */
    fun start(period: YearMonth): LocalDate = if (startDay == 1) period.atDay(1) else period.minusMonths(1).atDay(startDay)

    /** Last day of the budget month [period]. */
    fun end(period: YearMonth): LocalDate = start(period.plusMonths(1)).minusDays(1)

    /** The budget month that [today] is in. */
    fun current(today: LocalDate = LocalDate.now()): YearMonth = periodOfDate(today)

    /**
     * Timestamps to load from the database for [period] (start inclusive, end exclusive). It reaches
     * back a month when salaries shift, so filter the rows with [periodOf] afterwards.
     */
    fun loadRange(period: YearMonth, zone: ZoneId): LongRange {
        val first = if (salaryFromDay == null) start(period) else minOf(start(period), period.minusMonths(1).atDay(1))
        val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = start(period.plusMonths(1)).atStartOfDay(zone).toInstant().toEpochMilli()
        return from until to
    }

    /** "25 Oct – 24 Nov", or null for a calendar month. */
    fun rangeLabel(period: YearMonth): String? =
        if (startDay == 1) null else "${start(period).format(dayMonth)} – ${end(period).format(dayMonth)}"

    companion object {
        /** Up to the 28th, so every month (February too) has the start day. */
        const val MAX_START_DAY = 28
        val CALENDAR = MonthCycle()
        private val dayMonth = DateTimeFormatter.ofPattern("d MMM")
    }
}
