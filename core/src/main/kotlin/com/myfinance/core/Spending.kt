package com.myfinance.core

/**
 * Money put into investments (SIP, mutual funds, stocks, PPF, NPS, FD, RD, gold bonds) is not spending.
 * It still leaves the account, so it moves the ledger balance, but every spending total, budget and report
 * leaves it out and shows it on its own line as "Invested".
 *
 * A payment is an investment when it is money out in the Investments category, or in one of the user's
 * own categories whose name says so ("SIP", "Mutual funds", "PPF", "FD" ...). Money coming back from an
 * investment (a redemption) stays money in.
 */
object Spending {
    const val INVESTED_LABEL = "Invested"

    private val investWords = Regex(
        """(?<![a-z])(invest\w*|sips?|mutual funds?|mf|stocks?|shares?|equity|ppf|nps|fds?|rds?|fixed deposits?|recurring deposits?|term deposits?|sgb|gold bonds?|elss|demat|savings? plan)(?![a-z])""",
    )

    /** True for the Investments category and for user categories named like one. */
    fun isInvestmentCategory(category: String?): Boolean {
        if (category.isNullOrBlank()) return false
        if (category == DefaultCategories.INVESTMENTS) return true
        return investWords.containsMatchIn(category.lowercase())
    }

    fun isInvestment(type: TxnType, category: String?): Boolean = type == TxnType.DEBIT && isInvestmentCategory(category)

    /** Money out that counts as spending: a debit that is not an investment. */
    fun isSpend(type: TxnType, category: String?): Boolean = type == TxnType.DEBIT && !isInvestmentCategory(category)
}

/** Money out into an investment. Not spending, but it still leaves the account. */
val TxnLite.isInvestment: Boolean get() = Spending.isInvestment(type, category)

/** Money out that counts as spending (investments left out). */
val TxnLite.isSpend: Boolean get() = Spending.isSpend(type, category)
