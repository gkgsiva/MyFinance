package com.myfinance.core

/** A shared payment: [paidBy] paid [totalMinor], and each person in [shares] owes their share of it. */
data class SplitExpense(
    val id: String,
    val description: String,
    val paidBy: String,
    val totalMinor: Long,
    /** Person -> share in paise. Should add up to [totalMinor]; the payer can have a share too. */
    val shares: Map<String, Long>,
    val timestamp: Long,
)

/** Money handed back to settle up: [from] paid [to]. */
data class Settlement(val from: String, val to: String, val amountMinor: Long, val timestamp: Long)

data class Transfer(val from: String, val to: String, val amountMinor: Long)

object Splits {
    /** Splits [totalMinor] equally between [people], giving the leftover paise to the first people listed. */
    fun equalShares(totalMinor: Long, people: List<String>): Map<String, Long> {
        val distinct = people.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val base = totalMinor / distinct.size
        val extra = totalMinor % distinct.size
        return distinct.mapIndexed { i, p -> p to base + if (i < extra) 1 else 0 }.toMap()
    }

    /** Net position of each person: positive = others owe them, negative = they owe. */
    fun balances(expenses: List<SplitExpense>, settlements: List<Settlement>): Map<String, Long> {
        val bal = LinkedHashMap<String, Long>()
        fun add(p: String, v: Long) { bal[p] = (bal[p] ?: 0) + v }
        for (e in expenses) {
            add(e.paidBy, e.totalMinor)
            e.shares.forEach { (p, s) -> add(p, -s) }
            // If shares don't add up to the total, the payer covers the difference.
            val diff = e.totalMinor - e.shares.values.sum()
            if (diff != 0L) add(e.paidBy, -diff)
        }
        for (s in settlements) { add(s.from, s.amountMinor); add(s.to, -s.amountMinor) }
        return bal.filterValues { it != 0L }
    }

    /** The fewest simple payments that settle everyone up (greedy: largest debtor pays largest creditor). */
    fun settleUp(balances: Map<String, Long>): List<Transfer> {
        val creditors = balances.filter { it.value > 0 }.map { it.key to it.value }.sortedByDescending { it.second }.toMutableList()
        val debtors = balances.filter { it.value < 0 }.map { it.key to -it.value }.sortedByDescending { it.second }.toMutableList()
        val out = ArrayList<Transfer>()
        var i = 0; var j = 0
        while (i < debtors.size && j < creditors.size) {
            val (d, owe) = debtors[i]; val (c, due) = creditors[j]
            val pay = minOf(owe, due)
            out += Transfer(d, c, pay)
            debtors[i] = d to owe - pay; creditors[j] = c to due - pay
            if (debtors[i].second == 0L) i++
            if (creditors[j].second == 0L) j++
        }
        return out
    }
}
