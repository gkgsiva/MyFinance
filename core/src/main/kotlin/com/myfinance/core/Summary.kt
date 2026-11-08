package com.myfinance.core

/** The minimal view of a transaction that the summaries need. */
data class TxnLite(
    val amountMinor: Long,
    val type: TxnType,
    val category: String,
    val merchant: String?,
    val account: String?,
    val timestamp: Long,
    val owner: String? = null,
)

data class GroupTotal(val key: String, val totalMinor: Long, val count: Int)

data class FinanceSummary(
    val incomeMinor: Long,
    val expenseMinor: Long,
    val byCategory: List<GroupTotal>,
    val byMerchant: List<GroupTotal>,
    val byAccount: List<GroupTotal>,
    val byPerson: List<GroupTotal>,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor

    /** Share of income left after spending, 0..100. Null when there was no income. */
    val savingsRatePercent: Int?
        get() = if (incomeMinor <= 0) null else ((netMinor * 100) / incomeMinor).toInt()

    companion object {
        /** Groups are computed over expenses (debits) only, which is what people want to see broken down. */
        fun of(txns: List<TxnLite>): FinanceSummary {
            val debits = txns.filter { it.type == TxnType.DEBIT }
            fun group(selector: (TxnLite) -> String?) = debits
                .groupBy { selector(it)?.takeIf { s -> s.isNotBlank() } ?: "Unknown" }
                .map { (k, v) -> GroupTotal(k, v.sumOf { it.amountMinor }, v.size) }
                .sortedByDescending { it.totalMinor }

            return FinanceSummary(
                incomeMinor = txns.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
                expenseMinor = debits.sumOf { it.amountMinor },
                byCategory = group { it.category },
                byMerchant = group { it.merchant },
                byAccount = group { it.account },
                byPerson = group { it.owner },
            )
        }
    }
}
