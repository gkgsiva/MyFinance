package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** An account or card whose statements were imported before, and how up to date it is. */
data class StatementGap(val account: String, val lastCovered: LocalDate) {
    /** The month whose statement is the next one to import. */
    val nextMonth: YearMonth get() = YearMonth.from(lastCovered).let { if (lastCovered == it.atEndOfMonth()) it.plusMonths(1) else it }
}

/** The monthly nudge to import the latest bank and card statements. */
object StatementReminders {

    /**
     * Accounts that had a statement imported but none covering last month. [statementRows] are transactions that
     * came from statements. Accounts quiet for over a year are left out (probably closed).
     */
    fun gaps(statementRows: List<TxnLite>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<StatementGap> {
        val lastMonthEnd = YearMonth.from(today).minusMonths(1).atEndOfMonth()
        return statementRows.filter { it.account != null }
            .groupBy { it.account!! }
            .map { (acct, rows) -> StatementGap(acct, rows.maxOf { Dates.localDate(it.timestamp, zone) }) }
            // A statement ending a few days before month end still covers the month.
            .filter { it.lastCovered.isBefore(lastMonthEnd.minusDays(5)) && it.lastCovered.isAfter(today.minusYears(1)) }
            .sortedBy { it.account }
    }

    /** True when the reminder for [today]'s month is due: on or after [day], and not sent yet this month. */
    fun isDue(today: LocalDate, day: Int, lastSent: YearMonth?): Boolean =
        day in 1..28 && today.dayOfMonth >= day && lastSent != YearMonth.from(today)

    fun message(gaps: List<StatementGap>, today: LocalDate): Pair<String, String> {
        val month = YearMonth.from(today).minusMonths(1)
        val name = month.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val title = "Import your $name statements"
        val text = if (gaps.isEmpty()) "Download last month's bank and card statements and import them in MyFinance."
        else gaps.take(4).joinToString(", ") { it.account } + (if (gaps.size > 4) " and ${gaps.size - 4} more" else "") +
            ": download the latest statement and import it."
        return title to text
    }
}
