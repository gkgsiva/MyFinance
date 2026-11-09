package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class QuietAccount(val account: String, val lastSeen: LocalDate, val daysQuiet: Long, val usualPerMonth: Int)

/** Two entries that look like the same payment. [a] is the older one. */
data class DuplicatePair(val a: CheckRow, val b: CheckRow) {
    val key: String get() = "dup:" + listOf(a.uid, b.uid).sorted().joinToString("+")
}

data class HealthReport(
    /** Months with no entries at all between the first and the last month with data. */
    val missingMonths: List<YearMonth>,
    val quietAccounts: List<QuietAccount>,
    val duplicates: List<DuplicatePair>,
    val uncategorized: Int,
) {
    val problems: Int get() = missingMonths.size + quietAccounts.size + duplicates.size
}

/** Flags gaps and doubtful entries in the data: missing months, accounts gone quiet, likely duplicates. */
object DataHealth {
    private const val MINUTE = 60_000L

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

        val dups = ArrayList<DuplicatePair>()
        val sorted = rows.sortedBy { it.timestamp }
        for (i in sorted.indices) {
            val a = sorted[i]
            var j = i + 1
            while (j < sorted.size && sorted[j].timestamp - a.timestamp <= 24 * 60 * MINUTE) {
                val b = sorted[j]
                j++
                if (a.type != b.type || a.amountMinor != b.amountMinor) continue
                if (a.account != null && b.account != null && a.account != b.account) continue
                if (a.reference != null && b.reference != null && a.reference != b.reference) continue
                if (a.owner != b.owner && !(a.manual || b.manual)) continue
                // Same-day typed entry, or alerts minutes apart with the same reference or no reference.
                val close = abs(b.timestamp - a.timestamp) <= 10 * MINUTE ||
                    (a.reference != null && a.reference == b.reference) ||
                    ((a.manual || b.manual) && Dates.localDate(a.timestamp, zone) == Dates.localDate(b.timestamp, zone))
                if (!close) continue
                val p = DuplicatePair(a, b)
                if (p.key !in dismissed) dups += p
            }
        }
        return HealthReport(missing, quiet, dups.distinctBy { it.key }, rows.count { it.category == DefaultCategories.OTHER })
    }
}
