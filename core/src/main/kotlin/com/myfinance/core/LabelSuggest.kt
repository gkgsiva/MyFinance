package com.myfinance.core

/** A label the app thinks fits a payment, because of payments the user already labelled. */
data class LabelSuggestion(
    val txnId: Long,
    val label: String,
    /** Why, in a few words, e.g. "like Ravi Vegetables" or "has \"veg\" in the name". */
    val reason: String,
    /** 3 = same seller, 2 = shares a name word, 1 = same category and similar amount. */
    val strength: Int,
)

/**
 * Suggests labels for payments without one, after the user has labelled a few: other payments to the same
 * seller, payees that share a name word with labelled ones ("SRI BALAJI VEGETABLES" and "Murugan Vegetables"),
 * payees whose name contains the label itself ("veg" for Vegetables), and, for labels used on one category,
 * payments in that category of a similar size.
 */
object LabelSuggest {

    /** A label needs this many labelled payments before it is suggested. */
    const val MIN_EXAMPLES = 2

    private val skipWords = setOf(
        "upi", "pay", "payment", "payments", "paytm", "phonepe", "gpay", "googlepay", "bhim", "okaxis", "okhdfcbank", "okicici",
        "oksbi", "ybl", "ibl", "axl", "apl", "from", "for", "the", "and", "pvt", "ltd", "private", "limited", "india", "bank",
        "transfer", "imps", "neft", "rtgs", "credit", "debit", "online", "services", "service", "store", "stores", "shop",
        "mart", "enterprises", "enterprise", "traders", "trading", "sri", "shri", "shree", "new", "retail", "company", "co",
        "refund", "cashback", "via", "txn", "ref", "card", "pos", "ecom", "www", "com", "net", "mr", "mrs", "ms",
    )

    /** Name words of a payee that can tell sellers apart: "SRI BALAJI VEGETABLES" -> [balaji, vegetables]. */
    fun words(merchant: String): Set<String> =
        merchant.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in skipWords && !it.any(Char::isDigit) }
            .toSet()

    /** True when a payee word looks like the label: "veg" or "vegetables" for Vegetables, "grocery" for Groceries. */
    fun nameMatchesLabel(word: String, label: String): Boolean {
        val l = label.lowercase().split(Regex("[^a-z]+")).firstOrNull { it.length >= 3 } ?: return false
        if (word == l) return true
        if (l.length <= 4) return word.startsWith(l)
        return word == l.take(3) || word.startsWith(l.take(4))
    }

    fun suggest(
        txns: List<TxnLite>,
        labelsById: Map<Long, List<String>>,
        dismissed: Set<Pair<Long, String>> = emptySet(),
        limit: Int = 300,
    ): List<LabelSuggestion> {
        val debits = txns.filter { it.type == TxnType.DEBIT }
        val labelled = debits.filter { labelsById[it.id].orEmpty().isNotEmpty() }
        if (labelled.isEmpty()) return emptyList()

        data class Profile(
            val label: String,
            val sellers: MutableSet<String> = HashSet(),
            val sellerNames: MutableMap<String, String> = HashMap(),
            val words: MutableMap<String, Int> = HashMap(),
            val categories: MutableMap<String, Int> = HashMap(),
            var count: Int = 0,
            var minAmount: Long = Long.MAX_VALUE,
            var maxAmount: Long = 0,
        )
        val profiles = LinkedHashMap<String, Profile>()
        for (t in labelled) for (l in labelsById[t.id].orEmpty().distinct()) {
            if (l.equals("Refund", true)) continue
            val p = profiles.getOrPut(l) { Profile(l) }
            p.count++
            p.categories.merge(t.category, 1, Int::plus)
            p.minAmount = minOf(p.minAmount, t.amountMinor)
            p.maxAmount = maxOf(p.maxAmount, t.amountMinor)
            t.merchant?.let { m ->
                val key = Labels.sellerWord(m) ?: Categorizer.merchantKey(m)
                p.sellers += key
                p.sellerNames.putIfAbsent(key, m)
                words(m).forEach { w -> p.words.merge(w, 1, Int::plus) }
            }
        }
        val usable = profiles.values.filter { it.count >= MIN_EXAMPLES }
        if (usable.isEmpty()) return emptyList()
        // A word used by payees of several labels ("kumar", "traders") says nothing.
        val wordLabels = HashMap<String, Int>()
        for (p in usable) p.words.keys.forEach { wordLabels.merge(it, 1, Int::plus) }

        val out = ArrayList<LabelSuggestion>()
        for (t in debits) {
            if (labelsById[t.id].orEmpty().isNotEmpty()) continue
            val m = t.merchant
            var best: LabelSuggestion? = null
            fun offer(s: LabelSuggestion) {
                if ((t.id to s.label) in dismissed) return
                if (best == null || s.strength > best!!.strength) best = s
            }
            for (p in usable) {
                if (m != null) {
                    val key = Labels.sellerWord(m) ?: Categorizer.merchantKey(m)
                    if (key in p.sellers) {
                        offer(LabelSuggestion(t.id, p.label, "like ${p.sellerNames[key] ?: m}", 3)); continue
                    }
                    val shared = words(m).firstOrNull { w -> p.words.containsKey(w) && wordLabels[w] == 1 }
                    if (shared != null) {
                        offer(LabelSuggestion(t.id, p.label, "\"$shared\" is in the name, like your other ${p.label} payments", 2)); continue
                    }
                    if (words(m).any { nameMatchesLabel(it, p.label) }) {
                        offer(LabelSuggestion(t.id, p.label, "\"${p.label.lowercase()}\" is in the name", 2)); continue
                    }
                }
                // Mostly one category, a few examples, and an amount in the usual range.
                val (topCat, n) = p.categories.maxByOrNull { it.value }?.toPair() ?: continue
                if (p.count >= 3 && n * 10 >= p.count * 8 && t.category == topCat && t.category != DefaultCategories.OTHER &&
                    t.amountMinor >= p.minAmount / 2 && t.amountMinor <= p.maxAmount * 2
                ) {
                    offer(LabelSuggestion(t.id, p.label, "same category and size as your ${p.label} payments", 1))
                }
            }
            best?.let { out += it }
        }
        return out.sortedWith(compareByDescending<LabelSuggestion> { it.strength }.thenBy { it.label }).take(limit)
    }
}
