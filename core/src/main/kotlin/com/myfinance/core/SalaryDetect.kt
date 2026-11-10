package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * A salary that usually arrives late in the month (the last week), so it should count for the next month.
 * [fromDay]: credits from this day on count for the next month. [payer] and [ids] are the credits found;
 * [alreadySalary] is false when they are not in the Salary category yet (a steady credit from one payer).
 */
data class SalaryGuess(val fromDay: Int, val payer: String?, val ids: List<Long>, val months: Int, val alreadySalary: Boolean, val typicalDay: Int)

object SalaryDetect {
    /** Late in the month: from the 20th. */
    private const val LATE = 20
    private const val MIN_AMOUNT = 10_000_00L

    fun guess(txns: List<TxnLite>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SalaryGuess? {
        val since = today.minusMonths(13)
        val credits = txns.filter { it.type == TxnType.CREDIT && Dates.localDate(it.timestamp, zone).isAfter(since) }
        fun months(l: List<TxnLite>) = l.mapTo(HashSet()) { YearMonth.from(Dates.localDate(it.timestamp, zone)) }.size

        val salary = credits.filter { it.category.equals(DefaultCategories.SALARY, ignoreCase = true) }
        val (rows, already) = if (months(salary) >= 2) salary to true else {
            // No Salary category yet: the same payer crediting a steady, large amount in at least 3 months.
            val group = credits.filter { it.amountMinor >= MIN_AMOUNT && !it.merchant.isNullOrBlank() && it.category != DefaultCategories.TRANSFERS }
                .groupBy { Categorizer.merchantKey(it.merchant!!) }
                .filterKeys { it.isNotBlank() }
                .values
                .filter { l -> months(l) >= 3 && l.maxOf { it.amountMinor } <= 2 * l.minOf { it.amountMinor } }
                .maxByOrNull { months(it) } ?: return null
            group to false
        }
        // The month's main payment: the biggest credit of each month.
        val main = rows.groupBy { YearMonth.from(Dates.localDate(it.timestamp, zone)) }.values.map { l -> l.maxBy { it.amountMinor } }
        val days = main.map { Dates.localDate(it.timestamp, zone).dayOfMonth }
        val late = days.filter { it >= LATE }
        // Mostly late in the month (paid early when the month end is a holiday is fine).
        if (late.isEmpty() || late.size * 2 < days.size) return null
        val fromDay = (late.min() - 2).coerceIn(15, 28)
        return SalaryGuess(
            fromDay, rows.mapNotNull { it.merchant }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            rows.map { it.id }, months(rows), already, late.sorted()[late.size / 2],
        )
    }
}
