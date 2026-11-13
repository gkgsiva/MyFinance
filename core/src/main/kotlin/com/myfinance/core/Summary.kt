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
    /** Row id in the app database, so a report can point back at a transaction. */
    val id: Long = 0,
    val mode: PaymentMode = PaymentMode.OTHER,
)

data class GroupTotal(val key: String, val totalMinor: Long, val count: Int)

data class FinanceSummary(
    val incomeMinor: Long,
    val expenseMinor: Long,
    val byCategory: List<GroupTotal>,
    val byMerchant: List<GroupTotal>,
    val byAccount: List<GroupTotal>,
    val byPerson: List<GroupTotal>,
    /** Money put into investments (SIP, mutual funds, stocks, PPF, FD ...). Not part of [expenseMinor]. */
    val investedMinor: Long = 0,
    /** Investments by category, largest first. */
    val byInvestment: List<GroupTotal> = emptyList(),
) {
    /** Income minus spending. Investments count as saved, so they are not taken off here. */
    val netMinor: Long get() = incomeMinor - expenseMinor

    /** What is left after spending and investing. */
    val leftMinor: Long get() = incomeMinor - expenseMinor - investedMinor

    /** Share of income left after spending, 0..100. Null when there was no income. */
    val savingsRatePercent: Int?
        get() = if (incomeMinor <= 0) null else ((netMinor * 100) / incomeMinor).toInt()

    companion object {
        /** Groups are computed over spending only (investments are left out), which is what people want to see broken down. */
        fun of(txns: List<TxnLite>): FinanceSummary {
            val debits = txns.filter { it.isSpend }
            val invested = txns.filter { it.isInvestment }
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
                investedMinor = invested.sumOf { it.amountMinor },
                byInvestment = invested.groupBy { it.category }
                    .map { (k, v) -> GroupTotal(k, v.sumOf { it.amountMinor }, v.size) }
                    .sortedByDescending { it.totalMinor },
            )
        }
    }
}
