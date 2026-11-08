package com.myfinance.core

/** One movement in the cash wallet: an ATM withdrawal adds cash, a cash spend takes it away. */
data class CashEntry(val amountMinor: Long, val isWithdrawal: Boolean, val category: String, val note: String?, val timestamp: Long)

data class CashWalletSummary(
    val withdrawnMinor: Long,
    val spentMinor: Long,
    /** Cash that should still be in hand. Never below zero in the display, but [unaccountedMinor] shows the gap. */
    val inHandMinor: Long,
    val byCategory: List<GroupTotal>,
) {
    /** Cash spending you have logged beyond what was withdrawn (e.g. cash received as a gift), shown as a hint. */
    val overspentMinor: Long get() = (spentMinor - withdrawnMinor).coerceAtLeast(0)
}

object CashWallet {
    private val atm = Regex("""\b(atm|cash withdrawal|cash wdl|withdrawn at|cash w/d|nfs)\b""", RegexOption.IGNORE_CASE)

    /** True when a bank SMS debit is an ATM / cash withdrawal, which should top up the cash wallet. */
    fun isAtmWithdrawal(category: String, smsBody: String?): Boolean =
        category == DefaultCategories.CASH || (smsBody != null && atm.containsMatchIn(smsBody))

    fun summary(entries: List<CashEntry>, openingMinor: Long = 0): CashWalletSummary {
        val withdrawn = entries.filter { it.isWithdrawal }.sumOf { it.amountMinor } + openingMinor
        val spends = entries.filter { !it.isWithdrawal }
        val spent = spends.sumOf { it.amountMinor }
        return CashWalletSummary(
            withdrawnMinor = withdrawn,
            spentMinor = spent,
            inHandMinor = (withdrawn - spent).coerceAtLeast(0),
            byCategory = spends.groupBy { it.category }
                .map { (k, v) -> GroupTotal(k, v.sumOf { it.amountMinor }, v.size) }
                .sortedByDescending { it.totalMinor },
        )
    }
}
