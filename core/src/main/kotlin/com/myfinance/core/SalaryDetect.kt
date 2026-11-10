package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * A salary that usually arrives late in the month (the last week), so it should count for the next month.
 * [fromDay]: credits from this day on count for the next month. [payer] is who pays it; [alreadySalary] is false
 * when it was found as a steady credit from one payer that isn't in the Salary category yet (ask first).
 * [toCategorise]: credits that are the salary but not in the Salary category (same payer, similar amount).
 */
data class SalaryGuess(
    val fromDay: Int,
    val payer: String?,
    val ids: List<Long>,
    val months: Int,
    val alreadySalary: Boolean,
    val typicalDay: Int,
    val toCategorise: List<Long> = emptyList(),
    /** The latest salary credit, to show which one counts for this month. */
    val latest: TxnLite? = null,
)

object SalaryDetect {
    /** Late in the month: from the 20th. */
    private const val LATE = 20
    private const val MIN_AMOUNT = 10_000_00L

    fun guess(txns: List<TxnLite>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SalaryGuess? {
        val since = today.minusMonths(13)
        val credits = txns.filter { it.type == TxnType.CREDIT && Dates.localDate(it.timestamp, zone).isAfter(since) }
        fun months(l: List<TxnLite>) = l.mapTo(HashSet()) { YearMonth.from(Dates.localDate(it.timestamp, zone)) }.size
        fun key(t: TxnLite) = t.merchant?.let { Categorizer.merchantKey(it) }?.ifBlank { null }
        fun isSalary(t: TxnLite) = t.category.equals(DefaultCategories.SALARY, ignoreCase = true)

        val salary = credits.filter(::isSalary)
        val rows: List<TxnLite>
        val already: Boolean
        if (months(salary) >= 2) {
            // The same payer's other credits of about the same amount are the salary too (a statement line
            // or SMS that didn't say "salary"): they need the Salary category to count for the next month.
            val payers = salary.mapNotNullTo(HashSet()) { key(it) }
            val usual = salary.map { it.amountMinor }.sorted()[salary.size / 2]
            val same = credits.filter { !isSalary(it) && key(it) in payers && it.amountMinor * 10 >= usual * 6 && it.amountMinor * 10 <= usual * 16 }
            rows = salary + same
            already = true
        } else {
            // No Salary category yet: the same payer crediting a steady, large amount in at least 3 months.
            rows = credits.filter { it.amountMinor >= MIN_AMOUNT && key(it) != null && it.category != DefaultCategories.TRANSFERS }
                .groupBy { key(it)!! }
                .values
                .filter { l -> months(l) >= 3 && l.maxOf { it.amountMinor } <= 2 * l.minOf { it.amountMinor } }
                .maxByOrNull { months(it) } ?: return null
            already = false
        }
        // The month's main payment: the biggest credit of each month.
        val main = rows.groupBy { YearMonth.from(Dates.localDate(it.timestamp, zone)) }.values.map { l -> l.maxBy { it.amountMinor } }
        val days = main.map { Dates.localDate(it.timestamp, zone).dayOfMonth }
        val late = days.filter { it >= LATE }
        // Mostly late in the month (paid early when the month end is a holiday is fine).
        if (late.isEmpty() || late.size * 2 < days.size) return null
        // A few days early is common (a weekend or holiday at month end), so start well before the usual day.
        val fromDay = (late.min() - 3).coerceIn(18, 25)
        return SalaryGuess(
            fromDay, rows.mapNotNull { it.merchant }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            rows.map { it.id }, months(rows), already, late.sorted()[late.size / 2],
            toCategorise = rows.filterNot(::isSalary).map { it.id },
            latest = rows.maxByOrNull { it.timestamp },
        )
    }
}
