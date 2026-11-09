package com.myfinance.core

enum class HeavySort(val label: String) { AMOUNT("Biggest first"), NEWEST("Newest first"), OLDEST("Oldest first") }

data class HeavySpendResult(val rows: List<TxnLite>, val totalMinor: Long, val shareOfSpendPercent: Int?)

/** Payments at or above a threshold in a period: "everything over ₹10,000 this year". */
object HeavySpends {
    fun find(
        txns: List<TxnLite>,
        thresholdMinor: Long,
        fromMillis: Long,
        toMillis: Long,
        sort: HeavySort = HeavySort.AMOUNT,
        type: TxnType = TxnType.DEBIT,
    ): HeavySpendResult {
        val inPeriod = txns.filter { it.type == type && it.timestamp in fromMillis..toMillis }
        val heavy = inPeriod.filter { it.amountMinor >= thresholdMinor }
        val sorted = when (sort) {
            HeavySort.AMOUNT -> heavy.sortedByDescending { it.amountMinor }
            HeavySort.NEWEST -> heavy.sortedByDescending { it.timestamp }
            HeavySort.OLDEST -> heavy.sortedBy { it.timestamp }
        }
        val all = inPeriod.sumOf { it.amountMinor }
        val total = heavy.sumOf { it.amountMinor }
        return HeavySpendResult(sorted, total, if (all > 0) ((total * 100) / all).toInt() else null)
    }
}
