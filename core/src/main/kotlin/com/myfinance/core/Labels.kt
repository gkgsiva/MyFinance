package com.myfinance.core

/** User labels such as "EMI", "SIP" or "Grocery". A transaction can carry several. */
object Labels {
    const val NONE = "No label"

    /** Offered when adding a label; the user can type any other. */
    val suggestions = listOf("EMI", "SIP", "Food", "Grocery", "Rent", "Fuel", "Medical", "Kids", "Travel", "Shopping", "Bills", "Gifts")

    /**
     * Spending per label. A payment with two labels counts under both, so the rows can add up to more than
     * the total spent; payments without a label are grouped under [NONE].
     */
    fun spendByLabel(txns: List<TxnLite>, labelsById: Map<Long, List<String>>, includeUnlabelled: Boolean = true): List<GroupTotal> {
        val totals = LinkedHashMap<String, Pair<Long, Int>>()
        for (t in txns) {
            if (t.type != TxnType.DEBIT) continue
            val labels = labelsById[t.id].orEmpty().ifEmpty { if (includeUnlabelled) listOf(NONE) else emptyList() }
            for (l in labels.distinct()) {
                val (sum, n) = totals[l] ?: (0L to 0)
                totals[l] = (sum + t.amountMinor) to (n + 1)
            }
        }
        return totals.map { (k, v) -> GroupTotal(k, v.first, v.second) }
            .sortedWith(compareBy<GroupTotal> { it.key == NONE }.thenByDescending { it.totalMinor })
    }

    /** Every label in use plus the suggestions, used ones first. */
    fun choices(used: Collection<String>): List<String> =
        (used.distinct().sortedBy { it.lowercase() } + suggestions).distinctBy { it.lowercase() }
}
