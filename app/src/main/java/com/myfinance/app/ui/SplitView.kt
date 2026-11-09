package com.myfinance.app.ui

import com.myfinance.app.data.SplitPartEntity
import com.myfinance.app.data.TransactionEntity

/**
 * Split payments are stored once, with their extra parts alongside. Lists and reports see them as separate
 * entries: the payment with what is left, and one entry per part. A part has a negative id that points back
 * to the payment, so opening it opens the payment. Imports and family merges keep seeing the one payment.
 */
object SplitView {
    private const val PARTS = 100L

    fun partId(originalId: Long, index: Int): Long = -(originalId * PARTS + index + 1)

    fun originalId(id: Long): Long = if (id < 0) (-id - 1) / PARTS else id

    fun isPart(t: TransactionEntity): Boolean = t.id < 0

    fun expand(list: List<TransactionEntity>, parts: List<SplitPartEntity>): List<TransactionEntity> {
        if (parts.isEmpty()) return list
        val byUid = parts.groupBy { it.txnUid }
        val out = ArrayList<TransactionEntity>(list.size + parts.size)
        for (t in list) {
            val mine = byUid[t.uid]
            if (mine.isNullOrEmpty()) { out += t; continue }
            val used = mine.sumOf { it.amountMinor }
            if (used >= t.amountMinor) { out += t; continue }
            out += t.copy(amountMinor = t.amountMinor - used)
            mine.sortedBy { it.id }.take((PARTS - 1).toInt()).forEachIndexed { i, p ->
                out += t.copy(
                    id = partId(t.id, i), uid = "${t.uid}#$i", amountMinor = p.amountMinor, category = p.category,
                    note = p.note ?: t.note, smsKey = null, userEdited = true,
                )
            }
        }
        return out
    }
}
