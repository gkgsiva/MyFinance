package com.myfinance.core

import java.time.Month
import java.time.YearMonth
import java.time.ZoneId

/** How much of the past the Dashboard covers, ending at the month shown. */
enum class DashPeriod(val label: String) {
    MONTH("Month"), QUARTER("3 months"), YEAR("12 months"), FY("Financial year");

    /** The budget months covered, oldest first. */
    fun months(end: YearMonth): List<YearMonth> = when (this) {
        MONTH -> listOf(end)
        QUARTER -> Reports.months(end, 3)
        YEAR -> Reports.months(end, 12)
        FY -> {
            val start = if (end.month >= Month.APRIL) YearMonth.of(end.year, Month.APRIL) else YearMonth.of(end.year - 1, Month.APRIL)
            generateSequence(start) { it.plusMonths(1) }.takeWhile { it <= end }.toList()
        }
    }
}

/** Everything the Dashboard shows, for one period and (optionally) one account or card. */
data class DashboardData(
    val months: List<YearMonth> = emptyList(),
    val spentMinor: Long = 0,
    val receivedMinor: Long = 0,
    val count: Int = 0,
    val topCategory: GroupTotal? = null,
    /** Spending per month over the last 12 months to the month shown (the trend line). */
    val trend: List<Pair<YearMonth, Long>> = emptyList(),
    val byCategory: List<GroupTotal> = emptyList(),
    val topMerchants: List<MerchantStat> = emptyList(),
    /** Spending by bank account / card, largest first. */
    val byAccount: List<GroupTotal> = emptyList(),
    /** Accounts and cards to filter by, most used first. */
    val accounts: List<String> = emptyList(),
) {
    val averagePerMonthMinor: Long get() = if (months.isEmpty()) 0 else spentMinor / months.size
}

/**
 * The Dashboard: totals, top category, monthly spend trend, category mix, top merchants and spend by account
 * or card (layout inspired by Card-Statement-Analyser's analytics page, MIT).
 */
object Dashboard {
    const val UNKNOWN_ACCOUNT = "Other / cash"

    fun compute(
        txns: List<TxnLite>,
        end: YearMonth,
        period: DashPeriod,
        account: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        cycle: MonthCycle = MonthCycle.CALENDAR,
        /** Hidden payments count in the totals but are not named among the top merchants. */
        hiddenIds: Set<Long> = emptySet(),
    ): DashboardData {
        fun accountOf(t: TxnLite) = t.account?.takeIf { it.isNotBlank() } ?: UNKNOWN_ACCOUNT
        val accounts = txns.filter { it.type == TxnType.DEBIT }.groupingBy { accountOf(it) }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        val mine = if (account == null) txns else txns.filter { accountOf(it) == account }
        val byMonth = mine.groupBy { cycle.periodOf(it, zone) }

        val months = period.months(end)
        val inPeriod = months.flatMap { byMonth[it].orEmpty() }
        val debits = inPeriod.filter { it.type == TxnType.DEBIT }
        val summary = FinanceSummary.of(inPeriod)
        return DashboardData(
            months = months,
            spentMinor = summary.expenseMinor,
            receivedMinor = summary.incomeMinor,
            count = inPeriod.size,
            topCategory = summary.byCategory.firstOrNull(),
            trend = Reports.months(end, 12).map { m -> m to byMonth[m].orEmpty().filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor } },
            byCategory = summary.byCategory,
            topMerchants = Reports.topMerchants(debits.filterNot { it.id in hiddenIds }, 8).first,
            byAccount = debits.groupBy { accountOf(it) }
                .map { (k, v) -> GroupTotal(k, v.sumOf { it.amountMinor }, v.size) }
                .sortedByDescending { it.totalMinor },
            accounts = accounts,
        )
    }
}
