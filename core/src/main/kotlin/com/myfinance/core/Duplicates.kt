package com.myfinance.core

import java.time.ZoneId
import kotlin.math.abs

/**
 * Entries that look like one payment stored more than once. [keep] is the one to keep (the richest), [extras]
 * the ones to remove. [sameId] is true when every entry carries the same transaction ID (UPI reference, RRN,
 * card statement reference number), so they are certainly one payment.
 */
data class DuplicateGroup(val rows: List<CheckRow>, val keep: CheckRow, val sameId: Boolean) {
    /** For two entries, the same key the older pair cards used, so earlier "Both are real" answers still hold. */
    val key: String get() = "dup:" + rows.map { it.uid }.sorted().joinToString("+")
    val extras: List<CheckRow> get() = rows.filter { it.uid != keep.uid }
    val amountMinor: Long get() = keep.amountMinor
    val timestamp: Long get() = rows.maxOf { it.timestamp }
    val title: String get() = keep.merchant ?: rows.firstNotNullOfOrNull { it.merchant } ?: "Same amount twice"
}

/**
 * Finds entries stored more than once, using the bank's transaction ID first:
 * - The same ID on entries of the same amount and direction within 3 days: one payment (removed automatically).
 * - Different IDs: two real payments, never a duplicate, however alike they look (two ₹599 Amazon orders on a day).
 * - No ID on one side: a likely duplicate when the rest agrees (account, owner, a few minutes apart, or the same
 *   statement line), left for the user to confirm.
 * Copies of one payment are put in one group (five copies are one card, not ten pairs).
 */
object Duplicates {
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
    private const val ID_WINDOW = 3 * DAY

    /** Card and bank charges printed once per transaction they belong to. */
    private val feeLine = Regex("""\b(IGST|CGST|SGST|GST|DCC\s+Fee|Markup|Mark-up|Fuel\s+Surcharge|Interest\s+Amount|Principal\s+Amount|Late\s+Pay|Finance\s+Charge|Service\s+Tax|Cess)\b""", RegexOption.IGNORE_CASE)

    /**
     * A transaction ID worth trusting: at least 8 characters with at least 6 digits ("Ref no 1" or a word picked up
     * by mistake is not an ID). Upper-cased, without spaces and a "UPI:" style prefix.
     */
    fun id(reference: String?): String? {
        val r = reference?.trim()?.uppercase()?.replace(Regex("""\s+"""), "") ?: return null
        if (r.length < 8 || r.count(Char::isDigit) < 6) return null
        if (r.all { it == r[0] }) return null
        return r
    }

    /** Two IDs are the same: equal, or one 10+ characters long ends the other (a bank printing a longer RRN). */
    fun sameId(a: String?, b: String?): Boolean {
        val x = id(a) ?: return false
        val y = id(b) ?: return false
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= 10 && long.endsWith(short)
    }

    /**
     * Two IDs of the same kind (same length: two UPI references, two card statement reference numbers) that differ:
     * two payments. IDs of different kinds (an SMS's UPI reference and a card statement's 23-digit reference number)
     * tell nothing either way.
     */
    private fun idsDiffer(a: CheckRow, b: CheckRow): Boolean {
        val x = id(a.reference) ?: return false
        val y = id(b.reference) ?: return false
        return x.length == y.length && !sameId(x, y)
    }

    /** True when [a] and [b] look like the same payment. Both must be the same amount and direction. */
    fun same(a: CheckRow, b: CheckRow, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (a.type != b.type || a.amountMinor != b.amountMinor || a.uid == b.uid) return false
        val gap = abs(a.timestamp - b.timestamp)
        if (sameId(a.reference, b.reference)) return gap <= ID_WINDOW
        if (idsDiffer(a, b)) return false
        if (gap > DAY) return false
        if (!sameAccount(a.account, b.account)) return false
        if (a.owner != b.owner && !(a.manual || b.manual)) return false
        // Two statement lines without IDs: only the same line printed or imported twice. Two different shops
        // charging the same amount on one day are two payments.
        // Fees and taxes repeat for real (a DCC fee and its GST for each foreign spend on a day).
        if (a.statement && b.statement) return Dates.localDate(a.timestamp, zone) == Dates.localDate(b.timestamp, zone) &&
            StatementImport.sameLine(a.text.orEmpty(), b.text) && !feeLine.containsMatchIn(a.text.orEmpty())
        // Alerts minutes apart, or a typed entry on the same day as the SMS / statement line.
        return gap <= 10 * MINUTE || ((a.manual || b.manual) && Dates.localDate(a.timestamp, zone) == Dates.localDate(b.timestamp, zone))
    }

    /** "ICICI ••144" and "ICICI ••4144" are one account; either unknown matches anything. */
    private fun sameAccount(a: String?, b: String?): Boolean {
        if (a == null || b == null || a == b) return true
        val da = a.filter(Char::isDigit)
        val db = b.filter(Char::isDigit)
        if (da.isEmpty() || db.isEmpty()) return false
        val bankA = a.substringBefore("••").trim()
        val bankB = b.substringBefore("••").trim()
        if (bankA.isNotEmpty() && bankB.isNotEmpty() && !bankA.equals(bankB, ignoreCase = true)) return false
        return StatementImport.sameAccount(da, db)
    }

    /**
     * The entry to keep: one the user edited, then the statement line (the official record), then one with an
     * ID, then one with a category, then the longest text, then the oldest.
     */
    fun keeper(rows: List<CheckRow>): CheckRow = rows.minWith(
        compareByDescending<CheckRow> { it.edited }
            .thenByDescending { it.statement }
            .thenByDescending { !it.manual }
            .thenByDescending { id(it.reference) != null }
            .thenByDescending { it.category != DefaultCategories.OTHER }
            .thenByDescending { it.text?.length ?: 0 }
            .thenBy { it.id },
    )

    /**
     * Groups of entries that are one payment. Pairs the user said are both real ([dismissed] holds their "dup:" key)
     * are never joined, and a group never mixes two different IDs.
     */
    fun find(rows: List<CheckRow>, dismissed: Set<String> = emptySet(), zone: ZoneId = ZoneId.systemDefault()): List<DuplicateGroup> {
        val sorted = rows.sortedBy { it.timestamp }
        val parent = IntArray(sorted.size) { it }
        fun root(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        // Each group's ID (when it has one), so two payments with different IDs never end up together.
        val groupId = arrayOfNulls<String>(sorted.size)
        val members = HashMap<Int, MutableList<Int>>()
        fun membersOf(r: Int) = members.getOrPut(r) { mutableListOf(r) }
        for (i in sorted.indices) groupId[i] = id(sorted[i].reference)

        val byAmount = sorted.indices.groupBy { sorted[it].amountMinor to sorted[it].type }
        for (idx in byAmount.values) {
            if (idx.size < 2) continue
            for (x in idx.indices) {
                val a = sorted[idx[x]]
                var y = x + 1
                while (y < idx.size && sorted[idx[y]].timestamp - a.timestamp <= ID_WINDOW) {
                    val b = sorted[idx[y]]
                    y++
                    if (!same(a, b, zone)) continue
                    if (pairKey(a, b) in dismissed) continue
                    val ra = root(idx[x])
                    val rb = root(idx[y - 1])
                    if (ra == rb) continue
                    val ia = groupId[ra]
                    val ib = groupId[rb]
                    if (ia != null && ib != null && ia.length == ib.length && !sameId(ia, ib)) continue
                    val ma = membersOf(ra)
                    val mb = membersOf(rb)
                    // Nobody in one group may be a pair the user said is real with anybody in the other.
                    if (ma.any { p -> mb.any { q -> pairKey(sorted[p], sorted[q]) in dismissed } }) continue
                    // The bigger group absorbs the smaller.
                    val (big, small) = if (ma.size >= mb.size) ra to rb else rb to ra
                    parent[small] = big
                    membersOf(big).addAll(membersOf(small))
                    members.remove(small)
                    groupId[big] = groupId[big] ?: groupId[small]
                }
            }
        }
        return members.values.filter { it.size > 1 }.map { m ->
            val list = m.map { sorted[it] }.sortedBy { it.timestamp }
            val ids = list.map { id(it.reference) }
            val certain = ids.all { it != null } && ids.all { sameId(it, ids[0]) }
            DuplicateGroup(list, keeper(list), certain)
        }.filter { it.key !in dismissed }.sortedByDescending { it.timestamp }
    }

    fun pairKey(a: CheckRow, b: CheckRow) = "dup:" + listOf(a.uid, b.uid).sorted().joinToString("+")

    /** How many entries removing all [groups] takes out (one is kept in each). */
    fun extraCount(groups: List<DuplicateGroup>): Int = groups.sumOf { it.rows.size - 1 }
}
