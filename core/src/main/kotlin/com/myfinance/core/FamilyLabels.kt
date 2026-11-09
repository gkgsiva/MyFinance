package com.myfinance.core

/** Spending on one label by each family member. */
data class FamilyLabelRow(val label: String, val byPerson: Map<String, Long>, val totalMinor: Long)

object FamilyLabels {
    /**
     * Label x person table of spending (refunds netted off), biggest labels first. [people] gives the column order;
     * people with no labelled spending are still listed so the table stays the same shape.
     */
    fun table(txns: List<TxnLite>, labelsById: Map<Long, List<String>>, me: String): Pair<List<String>, List<FamilyLabelRow>> {
        val acc = LinkedHashMap<String, HashMap<String, Long>>()
        val people = LinkedHashSet<String>()
        for (t in txns) {
            val who = t.owner ?: me
            people += who
            for (l in labelsById[t.id].orEmpty().distinct()) {
                val sign = if (t.type == TxnType.DEBIT) 1 else -1
                // Income labels (Salary, Interest…) are not spending.
                if (t.type == TxnType.CREDIT && l in Labels.incomeSuggestions && l != "Refund" && l != "Cashback") continue
                acc.getOrPut(l) { HashMap() }.merge(who, sign * t.amountMinor, Long::plus)
            }
        }
        val order = listOf(me) + people.filter { it != me }.sorted()
        val rows = acc.map { (l, m) -> FamilyLabelRow(l, m.mapValues { it.value.coerceAtLeast(0) }, m.values.sum().coerceAtLeast(0)) }
            .filter { it.totalMinor > 0 }
            .sortedByDescending { it.totalMinor }
        return order.filter { p -> p in people } to rows
    }
}
