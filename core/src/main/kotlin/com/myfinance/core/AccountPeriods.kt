package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How the Accounts & cards screen buckets time. */
enum class Period(val label: String) { MONTH("Month"), QUARTER("Quarter"), YEAR("Year"), FINANCIAL_YEAR("Financial year") }

data class AccountTotal(
    /** "HDFC Bank ••1234", or [AccountPeriods.NO_ACCOUNT] for cash and manual entries. */
    val account: String,
    val spentMinor: Long,
    val receivedMinor: Long,
    val count: Int,
    /** Row ids, newest first, for listing the individual transactions. */
    val ids: List<Long>,
)

data class PeriodGroup(
    val start: LocalDate,
    val label: String,
    val accounts: List<AccountTotal>,
) {
    val spentMinor get() = accounts.sumOf { it.spentMinor }
    val receivedMinor get() = accounts.sumOf { it.receivedMinor }
    val count get() = accounts.sumOf { it.count }
}

/** Totals per bank account / credit card for each month, quarter or year, newest period first. */
object AccountPeriods {
    const val NO_ACCOUNT = "Cash & other"

    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

    fun periodStart(date: LocalDate, period: Period): LocalDate = when (period) {
        Period.MONTH -> date.withDayOfMonth(1)
        Period.QUARTER -> LocalDate.of(date.year, (date.monthValue - 1) / 3 * 3 + 1, 1)
        Period.YEAR -> LocalDate.of(date.year, 1, 1)
        Period.FINANCIAL_YEAR -> LocalDate.of(Dates.financialYearStart(date), 4, 1)
    }

    fun label(start: LocalDate, period: Period): String = when (period) {
        Period.MONTH -> start.format(monthFmt)
        Period.QUARTER -> "Q${(start.monthValue - 1) / 3 + 1} ${start.year} (${start.format(DateTimeFormatter.ofPattern("MMM"))}–${start.plusMonths(2).format(DateTimeFormatter.ofPattern("MMM"))})"
        Period.YEAR -> start.year.toString()
        Period.FINANCIAL_YEAR -> Dates.financialYearLabel(start.year)
    }

    /**
     * Groups [txns] (already without excluded rows) by period, then by account. Accounts with the
     * most spending come first within each period.
     */
    fun group(txns: List<TxnLite>, period: Period, zone: ZoneId = ZoneId.systemDefault()): List<PeriodGroup> =
        txns.groupBy { periodStart(Dates.localDate(it.timestamp, zone), period) }
            .map { (start, list) ->
                val accounts = list.groupBy { it.account ?: NO_ACCOUNT }.map { (acct, rows) ->
                    AccountTotal(
                        account = acct,
                        spentMinor = rows.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor },
                        receivedMinor = rows.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
                        count = rows.size,
                        ids = rows.sortedByDescending { it.timestamp }.map { it.id },
                    )
                }.sortedWith(compareByDescending<AccountTotal> { it.spentMinor }.thenBy { it.account })
                PeriodGroup(start, label(start, period), accounts)
            }
            .sortedByDescending { it.start }

    /** Every account seen, busiest first, for the filter chips. */
    fun accounts(txns: List<TxnLite>): List<String> =
        txns.groupingBy { it.account ?: NO_ACCOUNT }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
}
