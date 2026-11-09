package com.myfinance.core

/** One extra part of a split payment: [amountMinor] of the payment goes to [category]. The rest stays on the payment. */
data class SplitPart(val category: String, val amountMinor: Long, val note: String? = null)

/** Breaking one payment into parts, e.g. ₹2,000 at a supermarket into Groceries ₹1,500 and Household ₹500. */
object PaymentSplit {
    /** Null when the parts are fine, otherwise what is wrong. */
    fun problem(totalMinor: Long, parts: List<SplitPart>): String? = when {
        parts.any { it.amountMinor <= 0 } -> "Each part needs an amount"
        parts.any { it.category.isBlank() } -> "Each part needs a category"
        parts.sumOf { it.amountMinor } >= totalMinor -> "The parts must add up to less than ${formatInr(totalMinor)}; the rest stays with the payment"
        else -> null
    }

    /** What stays with the payment itself. */
    fun remaining(totalMinor: Long, parts: List<SplitPart>): Long = totalMinor - parts.sumOf { it.amountMinor }
}
