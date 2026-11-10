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
    /** Why it is not a new row: null when new. */
    val duplicateOf: DuplicateReason? = null,
    /**
     * The stored row (an SMS or a typed entry) this line is the same transaction as. Statements are
     * the official record, so importing updates that row with the statement's details.
     */
    val matchedId: Long? = null,
    /** From a credit card statement (its "payment received" lines are bill payments, not income). */
    val cardStatement: Boolean = false,
    /** Money moved between two of the user's own accounts (both sides seen): not spending or income. */
    val ownTransfer: Boolean = false,
) {
    /** Paying a card bill or moving money between own accounts: left out of spending and income. */
    val notCounted: Boolean get() = ownTransfer || SelfTransfers.isCardBillPayment(row.type, merchant, row.description, cardStatement)

    /** True when importing changes a stored SMS / manual row to the statement's details. */
    val updatesExisting: Boolean get() = matchedId != null && duplicateOf != DuplicateReason.SAME_STATEMENT
}

enum class DuplicateReason(val label: String) {
    SAME_STATEMENT("already imported"),
    REFERENCE("same as an SMS (reference), statement details used"),
    MATCHED("same as an SMS, statement details used"),
}

/** What is already stored, in the shape the matcher needs. */
data class ExistingTxn(
    val amountMinor: Long,
    val type: TxnType,
    val timestamp: Long,
    val accountTail: String?,
    val reference: String?,
    val key: String?,
    /** Row id; 0 for rows not stored yet (other files in the same batch). */
    val id: Long = 0,
    /** Already a statement line, so another statement's matching line is simply skipped. */
    val fromStatement: Boolean = false,
    /** The statement narration (or SMS) stored with it, to recognise the same line imported again. */
    val text: String? = null,
)

object StatementImport {

    /** Statement dates have no time; noon keeps the day right in any Indian time zone. */
    private val NOON: LocalTime = LocalTime.NOON

    fun candidates(
        statement: ParsedStatement,
        bank: String? = statement.bank,
        accountTail: String? = statement.accountTail,
        zone: ZoneId = ZoneId.systemDefault(),
        /** The user's corrections of the cards / accounts a statement lists: printed last 4 → the right last 4. */
        renames: Map<String, String> = emptyMap(),
    ): List<ImportCandidate> {
        val tail = accountTail?.filter(Char::isDigit)?.takeLast(4)?.ifEmpty { null }
        val seen = HashMap<String, Int>()
        val several = accountsIn(statement).size > 1
        return statement.rows.map { r ->
            // A statement can list several cards (add-on card, RuPay card used for UPI) or accounts (savings and
            // current): each line keeps its own. With one, the account chosen for the file applies.
            val printed = r.cardTail?.let { renames[it] ?: it }
            val rowTail = if (several || statement.kind == StatementKind.CARD) printed ?: tail else tail ?: printed
            val base = listOf(
                rowTail ?: bank.orEmpty(), r.date.toString(), r.amountMinor.toString(), r.type.name,
                r.description.lowercase().replace(Regex("""[^a-z0-9]+"""), ""),
            ).joinToString("|")
            // Two identical lines on one day (two ₹20 tea payments) are two transactions.
            val n = seen.merge(base, 1, Int::plus)!!
            val hash = sha1("$base|$n")
            val ts = r.date.atTime(r.time ?: NOON).atZone(zone).toInstant().toEpochMilli()
            ImportCandidate(
                row = r, key = "STMT|$hash", uid = java.util.UUID.nameUUIDFromBytes("stmt|$hash".toByteArray()).toString(),
                timestamp = ts, merchant = Narration.merchant(r.description), bank = bank, accountTail = rowTail,
                cardStatement = statement.kind == StatementKind.CARD,
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
                if (hit != null) { used[hit] = true; return@map linked(c, existing[hit], DuplicateReason.REFERENCE) }
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
                // Another card number is another transaction, unless it is the same statement line imported
                // again (an older version of the app, or a different card number typed on import).
                if (c.accountTail != null && et != null && et != c.accountTail && !(e.fromStatement && sameLine(c.row.description, e.text))) continue
                val gap = abs(day(e.timestamp) - cd)
                if (gap <= windowDays) pairs += Triple(ci, i, gap * 10 + (if (et == c.accountTail && et != null) 0 else 1))
            }
        }
        val taken = BooleanArray(result.size)
        for ((ci, i, _) in pairs.sortedBy { it.third }) {
            if (taken[ci] || used[i]) continue
            taken[ci] = true
            used[i] = true
            result[ci] = linked(result[ci], existing[i], DuplicateReason.MATCHED)
        }
        return result
    }

    private fun words(s: String): String =
        s.removePrefix("Statement: ").substringBefore("\n\nSMS: ").lowercase()
            .split(Regex("""[^a-z]+""")).filter { it.length >= 2 }.joinToString(" ")

    /** True when two narrations are the same statement line, ignoring numbers, reward points and spacing. */
    fun sameLine(a: String, b: String?): Boolean {
        if (b == null) return false
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.contains(y) || y.contains(x)) return true
        val xs = x.split(' ').toSet()
        val ys = y.split(' ').toSet()
        return (xs intersect ys).size * 10 >= minOf(xs.size, ys.size) * 7
    }

    /** A match with a stored SMS / entry is an update; with another statement's line or this batch, a skip. */
    private fun linked(c: ImportCandidate, e: ExistingTxn, reason: DuplicateReason) =
        if (e.id > 0 && !e.fromStatement) c.copy(duplicateOf = reason, matchedId = e.id)
        else c.copy(duplicateOf = DuplicateReason.SAME_STATEMENT)

    /**
     * The other direction: an SMS arriving (or found by a rescan) after its statement was imported.
     * Returns the index in [lines] (statement lines not yet linked to an SMS) of the line it is the
     * same transaction as, or null when it is new: same reference, or same amount and direction on
     * a compatible account within [windowDays] days, closest first.
     */
    fun lineForSms(sms: ExistingTxn, lines: List<ExistingTxn>, windowDays: Long = 2, zone: ZoneId = ZoneId.systemDefault()): Int? {
        fun tail(s: String?) = s?.filter(Char::isDigit)?.takeLast(4)?.ifEmpty { null }
        val st = tail(sms.accountTail)
        val ref = sms.reference?.takeIf { it.length >= 6 }
        val same = lines.indices.filter { lines[it].amountMinor == sms.amountMinor && lines[it].type == sms.type }
        if (ref != null) {
            same.firstOrNull { i -> lines[i].reference?.let { it == ref || (it.length >= 10 && ref.endsWith(it)) || (ref.length >= 10 && it.endsWith(ref)) } == true }
                ?.let { return it }
        }
        val day = Dates.localDate(sms.timestamp, zone).toEpochDay()
        return same.filter { i ->
            val lt = tail(lines[i].accountTail)
            !(st != null && lt != null && st != lt) && abs(Dates.localDate(lines[i].timestamp, zone).toEpochDay() - day) <= windowDays
        }.minWithOrNull(
            compareBy<Int> { abs(Dates.localDate(lines[it].timestamp, zone).toEpochDay() - day) }
                .thenBy { if (tail(lines[it].accountTail) == st && st != null) 0 else 1 },
        )
    }

    /** Totals for the preview. */
    /** The cards / accounts a statement's lines belong to (last 4 digits), with how many lines each, in order. */
    fun accountsIn(statement: ParsedStatement): List<Pair<String, Int>> =
        statement.rows.mapNotNull { it.cardTail }.groupingBy { it }.eachCount().toList()

    /**
     * Marks money moved between the user's own accounts when both sides are being imported (savings to current
     * and back): a debit on one account and a credit of the same amount on another of [ownTails] within
     * [windowDays] days, where both sides read like a transfer or one names the other account. Each line pairs once.
     */
    fun markOwnTransfers(
        list: List<ImportCandidate>,
        ownTails: Set<String>,
        windowDays: Long = 2,
    ): List<ImportCandidate> {
        val fresh = list.indices.filter { list[it].duplicateOf == null && list[it].accountTail in ownTails }
        val credits = fresh.filter { list[it].row.type == TxnType.CREDIT }.groupBy { list[it].row.amountMinor }
        val used = HashSet<Int>()
        val out = list.toMutableList()
        for (d in fresh.filter { list[it].row.type == TxnType.DEBIT }.sortedBy { list[it].timestamp }) {
            val dc = list[d]
            val c = credits[dc.row.amountMinor].orEmpty()
                .filter { it !in used && list[it].accountTail != dc.accountTail && abs(list[it].row.date.toEpochDay() - dc.row.date.toEpochDay()) <= windowDays }
                .filter { i -> val cc = list[i]; (SelfTransfers.looksLikeTransfer(dc.row.description) && SelfTransfers.looksLikeTransfer(cc.row.description)) || names(dc.row.description, cc.accountTail) || names(cc.row.description, dc.accountTail) }
                .minByOrNull { abs(list[it].timestamp - dc.timestamp) } ?: continue
            used += c
            out[d] = dc.copy(ownTransfer = true)
            out[c] = list[c].copy(ownTransfer = true)
        }
        return out
    }

    /** The narration names the account ending in [tail] ("TO A/C XXXX4144"). */
    private fun names(text: String, tail: String?) = tail != null && Regex("""(?:[Xx*•]|\d)$tail(?!\d)""").containsMatchIn(text)

    data class Summary(
        val newCount: Int,
        /** SMS / typed entries that will take the statement's details. */
        val updateCount: Int,
        /** Lines already imported from a statement. */
        val duplicateCount: Int,
        val spentMinor: Long,
        val receivedMinor: Long,
        val from: LocalDate?,
        val to: LocalDate?,
        /** Card bill payments and transfers between own accounts among the new lines: not counted. */
        val notCountedMinor: Long = 0,
        val notCountedCount: Int = 0,
    )

    fun summary(list: List<ImportCandidate>): Summary {
        val fresh = list.filter { it.duplicateOf == null }
        val updates = list.count { it.updatesExisting }
        return Summary(
            newCount = fresh.size,
            updateCount = updates,
            duplicateCount = list.size - fresh.size - updates,
            spentMinor = fresh.filter { it.row.type == TxnType.DEBIT && !it.notCounted }.sumOf { it.row.amountMinor },
            receivedMinor = fresh.filter { it.row.type == TxnType.CREDIT && !it.notCounted }.sumOf { it.row.amountMinor },
            notCountedMinor = fresh.filter { it.notCounted }.sumOf { it.row.amountMinor },
            notCountedCount = fresh.count { it.notCounted },
            from = list.minOfOrNull { it.row.date },
            to = list.maxOfOrNull { it.row.date },
        )
    }

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
