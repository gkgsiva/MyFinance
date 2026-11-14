package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class QuietAccount(val account: String, val lastSeen: LocalDate, val daysQuiet: Long, val usualPerMonth: Int)

data class HealthReport(
    /** Months with no entries at all between the first and the last month with data. */
    val missingMonths: List<YearMonth>,
    val quietAccounts: List<QuietAccount>,
    /** Copies of one payment, one group per payment. */
    val duplicates: List<DuplicateGroup>,
    val uncategorized: Int,
) {
    val problems: Int get() = missingMonths.size + quietAccounts.size + duplicates.size
}

/** Flags gaps and doubtful entries in the data: missing months, accounts gone quiet, likely duplicates. */
object DataHealth {
    fun check(rows: List<CheckRow>, today: LocalDate, dismissed: Set<String> = emptySet(), zone: ZoneId = ZoneId.systemDefault()): HealthReport {
        if (rows.isEmpty()) return HealthReport(emptyList(), emptyList(), emptyList(), 0)
        val months = rows.map { YearMonth.from(Dates.localDate(it.timestamp, zone)) }.toSet()
        val first = months.min()
        val lastFull = YearMonth.from(today).minusMonths(1)
        val missing = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(lastFull) }.filter { it !in months }.toList()

        val quiet = rows.filter { it.account != null && !it.manual }.groupBy { it.account!! }.mapNotNull { (acct, list) ->
            val dates = list.map { Dates.localDate(it.timestamp, zone) }
            val last = dates.max()
            val days = ChronoUnit.DAYS.between(last, today)
            // Used regularly before: at least 2 entries a month over the 3 months before it went quiet.
            val before = dates.count { !it.isBefore(last.minusMonths(3)) }
            if (days > 45 && before >= 6) QuietAccount(acct, last, days, before / 3) else null
        }.sortedByDescending { it.daysQuiet }

        val dups = Duplicates.find(rows, dismissed, zone)
        return HealthReport(missing, quiet, dups, rows.count { it.category == DefaultCategories.OTHER })
    }
}
