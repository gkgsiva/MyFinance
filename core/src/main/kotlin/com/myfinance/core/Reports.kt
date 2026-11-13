package com.myfinance.core

import java.time.YearMonth
import java.time.ZoneId

data class MonthTotals(
    val month: YearMonth,
    val incomeMinor: Long,
    /** Spending only: investments are in [investedMinor]. */
    val expenseMinor: Long,
    val investedMinor: Long = 0,
) {
    /** Income minus spending; money invested counts as saved. */
    val netMinor: Long get() = incomeMinor - expenseMinor

    /** Income not spent (investments count as saved), as a percentage. Null when there was no income. */
    val savingsRatePercent: Int? get() = if (incomeMinor <= 0) null else ((netMinor * 100) / incomeMinor).toInt()
}

/** One line of a chart: a value per month, oldest first. */
data class Series(val key: String, val values: List<Long>) {
    val total: Long get() = values.sum()
    val last: Long get() = values.lastOrNull() ?: 0

    /** Change of the latest month against the average of the earlier ones, in percent. */
    val trendPercent: Int?
        get() {
            val earlier = values.dropLast(1).filter { it > 0 }
            if (earlier.isEmpty()) return null
            val avg = earlier.sum() / earlier.size
            if (avg <= 0) return null
            return (((last - avg) * 100) / avg).toInt()
        }
}

data class MerchantStat(val merchant: String, val totalMinor: Long, val count: Int)

data class ModeShare(val mode: PaymentMode, val totalMinor: Long, val count: Int)

data class FySummary(
    val startYear: Int,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val byCategory: List<GroupTotal>,
    val investedMinor: Long = 0,
) {
    val label: String get() = Dates.financialYearLabel(startYear)
    val netMinor: Long get() = incomeMinor - expenseMinor
}

data class UnusualSpend(val txn: TxnLite, val typicalMinor: Long) {
    val times: Double get() = if (typicalMinor <= 0) 0.0 else txn.amountMinor.toDouble() / typicalMinor
}

/**
 * The summary reports. Each takes the transactions already filtered for "leave out of totals",
 * so the numbers match the Overview tab.
 */
object Reports {

    /** The [count] months ending with [end], oldest first. */
    fun months(end: YearMonth, count: Int): List<YearMonth> = (count - 1 downTo 0).map { end.minusMonths(it.toLong()) }

    /** 1 and 5: income, spending and savings rate per month. */
    fun monthly(
        txns: List<TxnLite>,
        end: YearMonth,
        count: Int = 12,
        zone: ZoneId = ZoneId.systemDefault(),
        cycle: MonthCycle = MonthCycle.CALENDAR,
    ): List<MonthTotals> {
        val byMonth = txns.groupBy { cycle.periodOf(it, zone) }
        return months(end, count).map { m ->
            val list = byMonth[m].orEmpty()
            MonthTotals(
                month = m,
                incomeMinor = list.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
                expenseMinor = list.filter { it.isSpend }.sumOf { it.amountMinor },
                investedMinor = list.filter { it.isInvestment }.sumOf { it.amountMinor },
            )
        }
    }

    /** Spending this month against last month, in percent. Null without last month's figure. */
    fun changePercent(current: Long, previous: Long): Int? =
        if (previous <= 0) null else (((current - previous) * 100) / previous).toInt()

    /** 2 and 4: spending per month for each key (category, person...), biggest first. */
    fun seriesBy(
        txns: List<TxnLite>,
        end: YearMonth,
        count: Int = 6,
        zone: ZoneId = ZoneId.systemDefault(),
        cycle: MonthCycle = MonthCycle.CALENDAR,
        keyOf: (TxnLite) -> String?,
    ): List<Series> {
        val window = months(end, count)
        val index = window.withIndex().associate { (i, m) -> m to i }
        val sums = HashMap<String, LongArray>()
        for (t in txns) {
            if (!t.isSpend) continue
            val i = index[cycle.periodOf(t, zone)] ?: continue
            val key = keyOf(t)?.takeIf { it.isNotBlank() } ?: "Unknown"
            sums.getOrPut(key) { LongArray(count) }[i] += t.amountMinor
        }
        return sums.map { (k, v) -> Series(k, v.toList()) }.sortedByDescending { it.total }
    }

    fun categoryTrends(
        txns: List<TxnLite>,
        end: YearMonth,
        count: Int = 6,
        zone: ZoneId = ZoneId.systemDefault(),
        cycle: MonthCycle = MonthCycle.CALENDAR,
    ) = seriesBy(txns, end, count, zone, cycle) { it.category }

    fun perPerson(
        txns: List<TxnLite>,
        end: YearMonth,
        count: Int = 6,
        zone: ZoneId = ZoneId.systemDefault(),
        cycle: MonthCycle = MonthCycle.CALENDAR,
    ) = seriesBy(txns, end, count, zone, cycle) { it.owner }

    /** 3: where the money goes, by amount and by number of payments. */
    fun topMerchants(txns: List<TxnLite>, limit: Int = 10): Pair<List<MerchantStat>, List<MerchantStat>> {
        val stats = txns.filter { it.isSpend && !it.merchant.isNullOrBlank() }
            .groupBy { Categorizer.merchantKey(it.merchant!!) }
            .map { (_, list) ->
                // Show the most common spelling of the merchant.
                val name = list.groupingBy { it.merchant!! }.eachCount().maxBy { it.value }.key
                MerchantStat(name, list.sumOf { it.amountMinor }, list.size)
            }
        val byAmount = stats.sortedByDescending { it.totalMinor }.take(limit)
        val byCount = stats.sortedWith(compareByDescending<MerchantStat> { it.count }.thenByDescending { it.totalMinor }).take(limit)
        return byAmount to byCount
    }

    /** 6: spending split by UPI, card, cash and net banking. */
    fun paymentModes(txns: List<TxnLite>): List<ModeShare> =
        txns.filter { it.isSpend }
            .groupBy { it.mode }
            .map { (mode, list) -> ModeShare(mode, list.sumOf { it.amountMinor }, list.size) }
            .sortedByDescending { it.totalMinor }

    /** 7: April-to-March totals, newest year first. */
    fun financialYears(txns: List<TxnLite>, zone: ZoneId = ZoneId.systemDefault()): List<FySummary> =
        txns.groupBy { Dates.financialYearStart(Dates.localDate(it.timestamp, zone)) }
            .map { (year, list) ->
                val s = FinanceSummary.of(list)
                FySummary(year, s.incomeMinor, s.expenseMinor, s.byCategory, s.investedMinor)
            }
            .sortedByDescending { it.startYear }

    /**
     * 8: payments much bigger than usual for their category: at least [factor] times the median of
     * that category's earlier payments, with at least [minHistory] of them to compare against, and
     * over [minAmountMinor] so small bills are not flagged. Newest first.
     */
    fun unusual(
        txns: List<TxnLite>,
        factor: Double = 3.0,
        minHistory: Int = 5,
        minAmountMinor: Long = 100_000,
    ): List<UnusualSpend> {
        val out = ArrayList<UnusualSpend>()
        val debits = txns.filter { it.isSpend }.sortedBy { it.timestamp }
        for ((_, list) in debits.groupBy { it.category }) {
            val seen = ArrayList<Long>()
            for (t in list) {
                if (seen.size >= minHistory && t.amountMinor >= minAmountMinor) {
                    val median = seen.sorted()[seen.size / 2]
                    if (median > 0 && t.amountMinor >= median * factor) out += UnusualSpend(t, median)
                }
                seen += t.amountMinor
            }
        }
        return out.sortedByDescending { it.txn.timestamp }
    }
}
