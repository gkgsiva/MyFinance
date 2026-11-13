package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth

/** What a month looks like before it is closed. */
data class MonthCloseSummary(
    val month: YearMonth,
    /** Spending only; money put into investments is [investedMinor]. */
    val spentMinor: Long,
    val receivedMinor: Long,
    val count: Int,
    val uncategorized: Int,
    val topCategories: List<Pair<String, Long>>,
    val investedMinor: Long = 0,
)

/** Closing a month: once reviewed it is locked, so later imports don't quietly change it. */
object MonthClose {
    fun key(month: YearMonth): String = month.toString()
    fun parse(key: String): YearMonth? = runCatching { YearMonth.parse(key) }.getOrNull()

    fun summary(month: YearMonth, txns: List<TxnLite>): MonthCloseSummary {
        val debits = txns.filter { it.isSpend }
        return MonthCloseSummary(
            month = month,
            spentMinor = debits.sumOf { it.amountMinor },
            receivedMinor = txns.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
            count = txns.size,
            uncategorized = txns.count { it.category == DefaultCategories.OTHER },
            investedMinor = txns.filter { it.isInvestment }.sumOf { it.amountMinor },
            topCategories = debits.groupBy { it.category }.map { (k, v) -> k to v.sumOf { it.amountMinor } }.sortedByDescending { it.second }.take(5),
        )
    }

    /** Finished months not closed yet, newest first, going back at most [back] months. */
    fun openPastMonths(today: LocalDate, closed: Set<YearMonth>, first: YearMonth?, back: Int = 6): List<YearMonth> {
        val current = YearMonth.from(today)
        return (1..back).map { current.minusMonths(it.toLong()) }
            .filter { it !in closed && (first == null || !it.isBefore(first)) }
    }
}
