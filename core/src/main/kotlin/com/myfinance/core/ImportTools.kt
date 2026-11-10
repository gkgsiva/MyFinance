package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * Help around importing years of statements: which months are missing per card and account, a summary
 * per financial year of what was imported, the uncategorised entries grouped for bulk categories, and
 * when to remind about a backup.
 */

// ---- Missing statements ----

/** One card or bank account that has statement lines, and the months between its first and last with none. */
data class AccountCoverage(
    val account: String,
    val first: YearMonth,
    val last: YearMonth,
    /** Months from the first to last month (or to last calendar month, while the account is in use) with no statement line. */
    val missing: List<YearMonth>,
    val monthsCovered: Int,
) {
    /** Missing months by year, for a compact list: 2019 → [Mar, Apr]. */
    val missingByYear: Map<Int, List<YearMonth>> get() = missing.groupBy { it.year }
}

object MissingStatements {
    /**
     * [statementRows] are entries that came from statements. A month counts as covered when any line of that
     * account is dated in it. Accounts used in the last year are checked up to last month.
     */
    fun find(statementRows: List<TxnLite>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AccountCoverage> {
        val lastMonth = YearMonth.from(today).minusMonths(1)
        return statementRows.filter { !it.account.isNullOrBlank() }
            .groupBy { it.account!! }
            .map { (acct, rows) ->
                val months = rows.mapTo(HashSet()) { YearMonth.from(Dates.localDate(it.timestamp, zone)) }
                val first = months.min()
                val seenLast = months.max()
                val activeRecently = !seenLast.isBefore(lastMonth.minusMonths(12))
                val end = if (activeRecently && seenLast < lastMonth) lastMonth else seenLast
                val missing = generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= end }.filter { it !in months }.toList()
                AccountCoverage(acct, first, end, missing, months.size)
            }
            .sortedWith(compareByDescending<AccountCoverage> { it.missing.size }.thenBy { it.account })
    }
}

// ---- Summary per year of an import ----

data class YearTotals(val fyStartYear: Int, val spentMinor: Long, val receivedMinor: Long, val count: Int, val accounts: List<String>) {
    val label: String get() = Dates.financialYearLabel(fyStartYear)
}

object ImportYears {
    /** Spending and money received per financial year (April to March), oldest first. */
    fun of(rows: List<TxnLite>, zone: ZoneId = ZoneId.systemDefault()): List<YearTotals> =
        rows.groupBy { Dates.financialYearStart(Dates.localDate(it.timestamp, zone)) }
            .map { (fy, l) ->
                YearTotals(
                    fy, l.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor }, l.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
                    l.size, l.mapNotNull { it.account }.distinct().sorted(),
                )
            }
            .sortedBy { it.fyStartYear }
}

// ---- Uncategorised cleanup ----

/** Uncategorised entries from one payee, to categorise in one go. */
data class UncategorisedGroup(val key: String, val name: String, val count: Int, val totalMinor: Long, val ids: List<Long>, val type: TxnType)

object Uncategorised {
    /** Uncategorised entries grouped by payee, the most entries (then the most money) first. */
    fun groups(txns: List<TxnLite>): List<UncategorisedGroup> =
        txns.filter { it.category == DefaultCategories.OTHER }
            .groupBy { (it.merchant?.let { m -> Categorizer.merchantKey(m) }?.ifBlank { null } ?: "") to it.type }
            .map { (k, l) ->
                val name = l.mapNotNull { it.merchant?.takeIf { m -> m.isNotBlank() } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "No payee name"
                UncategorisedGroup(k.first, name, l.size, l.sumOf { it.amountMinor }, l.map { it.id }, k.second)
            }
            .sortedWith(compareByDescending<UncategorisedGroup> { it.count }.thenByDescending { it.totalMinor })
}

// ---- Backup reminder ----

object BackupNudge {
    private const val DAY = 24L * 60 * 60 * 1000

    /** Days since the last backup or export, counted from [since] (first use) when there has been none. */
    fun daysSince(lastBackup: Long?, since: Long, now: Long): Long = (now - (lastBackup ?: since)) / DAY

    /** Remind when there is data, no backup in [days] days, and no reminder in the last [days] days either. */
    fun isDue(lastBackup: Long?, since: Long, lastNudge: Long?, hasData: Boolean, now: Long, days: Int = 30): Boolean =
        hasData && daysSince(lastBackup, since, now) >= days && (lastNudge == null || now - lastNudge >= days * DAY)
}
