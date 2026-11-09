package com.myfinance.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/** A statement row ready to store, with a stable key so importing the same statement twice adds nothing. */
data class ImportCandidate(
    val row: StatementRow,
    /** "STMT|…": stored in the SMS-key column so the database refuses repeats. */
    val key: String,
    /** Same on every phone for the same statement line, so family merges don't duplicate it either. */
    val uid: String,
    val timestamp: Long,
    val merchant: String?,
    val bank: String?,
    val accountTail: String?,
    /** Why it is skipped: null when new. */
    val duplicateOf: DuplicateReason? = null,
)

enum class DuplicateReason(val label: String) {
    SAME_STATEMENT("already imported"),
    REFERENCE("same reference as an SMS"),
    MATCHED("matches an SMS or entry"),
}

/** What is already stored, in the shape the matcher needs. */
data class ExistingTxn(
    val amountMinor: Long,
    val type: TxnType,
    val timestamp: Long,
    val accountTail: String?,
    val reference: String?,
    val key: String?,
)

object StatementImport {

    /** Statement dates have no time; noon keeps the day right in any Indian time zone. */
    private val NOON: LocalTime = LocalTime.NOON

    fun candidates(
        statement: ParsedStatement,
        bank: String? = statement.bank,
        accountTail: String? = statement.accountTail,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ImportCandidate> {
        val tail = accountTail?.filter(Char::isDigit)?.takeLast(4)?.ifEmpty { null }
        val seen = HashMap<String, Int>()
        return statement.rows.map { r ->
            val base = listOf(
                tail ?: bank.orEmpty(), r.date.toString(), r.amountMinor.toString(), r.type.name,
                r.description.lowercase().replace(Regex("""[^a-z0-9]+"""), ""),
            ).joinToString("|")
            // Two identical lines on one day (two ₹20 tea payments) are two transactions.
            val n = seen.merge(base, 1, Int::plus)!!
            val hash = sha1("$base|$n")
            val ts = r.date.atTime(r.time ?: NOON).atZone(zone).toInstant().toEpochMilli()
            ImportCandidate(
                row = r, key = "STMT|$hash", uid = java.util.UUID.nameUUIDFromBytes("stmt|$hash".toByteArray()).toString(),
                timestamp = ts, merchant = Narration.merchant(r.description), bank = bank, accountTail = tail,
            )
        }
    }

    /**
     * Marks the candidates already present: imported before (same key), the same UPI / IMPS
     * reference as a stored SMS, or the same amount and direction on the same account within
     * [windowDays] days. Each stored transaction is matched at most once, so two ₹500 payments on
     * one day in the statement match two SMS, not one.
     */
    fun markDuplicates(
        candidates: List<ImportCandidate>,
        existing: List<ExistingTxn>,
        windowDays: Long = 2,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ImportCandidate> {
        val keys = existing.mapNotNullTo(HashSet()) { it.key }
        val used = BooleanArray(existing.size)
        val byAmount = existing.indices.groupBy { existing[it].amountMinor to existing[it].type }
        fun day(ts: Long) = Dates.localDate(ts, zone).toEpochDay()
        fun tail(s: String?) = s?.filter(Char::isDigit)?.takeLast(4)?.ifEmpty { null }

        // Pass 1: exact keys and references, which are certain.
        val result = candidates.map { c ->
            if (c.key in keys) return@map c.copy(duplicateOf = DuplicateReason.SAME_STATEMENT)
            val ref = c.row.reference?.takeIf { it.length >= 6 }
            if (ref != null) {
                val hit = byAmount[c.row.amountMinor to c.row.type]?.firstOrNull { i ->
                    !used[i] && existing[i].reference?.let { it == ref || (ref.length >= 10 && it.endsWith(ref)) || (it.length >= 10 && ref.endsWith(it)) } == true
                }
                if (hit != null) { used[hit] = true; return@map c.copy(duplicateOf = DuplicateReason.REFERENCE) }
            }
            c
        }.toMutableList()

        // Pass 2: same amount and direction, close in time, closest first.
        val pairs = ArrayList<Triple<Int, Int, Long>>()
        result.forEachIndexed { ci, c ->
            if (c.duplicateOf != null) return@forEachIndexed
            val cd = c.row.date.toEpochDay()
            for (i in byAmount[c.row.amountMinor to c.row.type].orEmpty()) {
                if (used[i]) continue
                val e = existing[i]
                val et = tail(e.accountTail)
                if (c.accountTail != null && et != null && et != c.accountTail) continue
                val gap = abs(day(e.timestamp) - cd)
                if (gap <= windowDays) pairs += Triple(ci, i, gap * 10 + (if (et == c.accountTail && et != null) 0 else 1))
            }
        }
        val taken = BooleanArray(result.size)
        for ((ci, i, _) in pairs.sortedBy { it.third }) {
            if (taken[ci] || used[i]) continue
            taken[ci] = true
            used[i] = true
            result[ci] = result[ci].copy(duplicateOf = DuplicateReason.MATCHED)
        }
        return result
    }

    /** Totals for the preview. */
    data class Summary(val newCount: Int, val duplicateCount: Int, val spentMinor: Long, val receivedMinor: Long, val from: LocalDate?, val to: LocalDate?)

    fun summary(list: List<ImportCandidate>): Summary {
        val fresh = list.filter { it.duplicateOf == null }
        return Summary(
            newCount = fresh.size,
            duplicateCount = list.size - fresh.size,
            spentMinor = fresh.filter { it.row.type == TxnType.DEBIT }.sumOf { it.row.amountMinor },
            receivedMinor = fresh.filter { it.row.type == TxnType.CREDIT }.sumOf { it.row.amountMinor },
            from = list.minOfOrNull { it.row.date },
            to = list.maxOfOrNull { it.row.date },
        )
    }

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
