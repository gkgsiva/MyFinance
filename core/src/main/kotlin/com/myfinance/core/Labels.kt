package com.myfinance.core

/** Spent, received back and net for one label. */
data class LabelNet(val label: String, val spentMinor: Long, val receivedMinor: Long, val payments: Int, val credits: Int) {
    val netMinor: Long get() = spentMinor - receivedMinor
}

/** User labels such as "EMI", "SIP" or "Grocery". A transaction can carry several. */
object Labels {
    const val NONE = "No label"

    /** Offered when adding a label; the user can type any other. */
    val suggestions = listOf("EMI", "SIP", "Food", "Grocery", "Rent", "Fuel", "Medical", "Kids", "Travel", "Shopping", "Bills", "Gifts")

    /** Offered when labelling money received. */
    val incomeSuggestions = listOf("Salary", "Bonus", "Rent income", "Interest", "Dividend", "Freelance", "Refund", "Cashback", "Gift received")

    private val genericWords = setOf(
        "upi", "pay", "payment", "payments", "refund", "refunds", "reversal", "cashback", "cancel", "cancelled", "from", "to", "for",
        "the", "pvt", "ltd", "private", "limited", "india", "bank", "transfer", "imps", "neft", "rtgs", "credit", "debit", "online", "services",
    )

    /** The main word of a payee, e.g. "irctc" for both "IRCTC" and "IRCTC REFUND", or "amazon" for "Amazon Pay India". */
    fun sellerWord(merchant: String): String? =
        Categorizer.merchantKey(merchant).split(' ').firstOrNull { it.length >= 4 && it !in genericWords && !it.all(Char::isDigit) }

    /** True when two payee texts are the same seller, so a refund from "IRCTC REFUND" belongs with "IRCTC". */
    fun sameSeller(a: String, b: String): Boolean {
        if (Categorizer.merchantKey(a) == Categorizer.merchantKey(b)) return true
        val wa = sellerWord(a) ?: return false
        return wa == sellerWord(b)
    }

    /**
     * Per label: what went out, what came back (refunds, cashback, cancellations) and the net, e.g. IRCTC tickets
     * bought minus tickets refunded. Credits that are income labels (Salary, Interest…) show as received with no spend.
     */
    fun netByLabel(txns: List<TxnLite>, labelsById: Map<Long, List<String>>): List<LabelNet> {
        val acc = LinkedHashMap<String, LongArray>() // spent, received, payments, credits
        for (t in txns) for (l in labelsById[t.id].orEmpty().distinct()) {
            val a = acc.getOrPut(l) { LongArray(4) }
            if (t.type == TxnType.DEBIT) { a[0] += t.amountMinor; a[2]++ } else { a[1] += t.amountMinor; a[3]++ }
        }
        return acc.map { (k, a) -> LabelNet(k, a[0], a[1], a[2].toInt(), a[3].toInt()) }
            .sortedByDescending { maxOf(it.spentMinor, it.receivedMinor) }
    }

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

    /** Every label in use plus the suggestions, used ones first. [income] offers income labels (for money received). */
    fun choices(used: Collection<String>, income: Boolean = false): List<String> =
        (used.distinct().sortedBy { it.lowercase() } + (if (income) incomeSuggestions + suggestions else suggestions + incomeSuggestions))
            .distinctBy { it.lowercase() }
}
