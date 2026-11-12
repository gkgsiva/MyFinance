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

        // The same line printed under two cards' tables in one file (a consolidated statement): one transaction.
        val firstTail = HashMap<List<Any?>, MutableList<String?>>()
        val repeatOnOtherCard = BooleanArray(candidates.size)
        candidates.forEachIndexed { i, c ->
            val k = listOf(c.row.date, c.row.amountMinor, c.row.type, words(c.row.description))
            val tails = firstTail.getOrPut(k) { ArrayList() }
            if (c.accountTail != null && tails.any { it != null && it != c.accountTail } && tails.count { it == c.accountTail } == 0) repeatOnOtherCard[i] = true
            else tails += c.accountTail
        }

        // Pass 1: exact keys and references, which are certain.
        val result = candidates.mapIndexed { i, c ->
            if (repeatOnOtherCard[i]) return@mapIndexed c.copy(duplicateOf = DuplicateReason.SAME_STATEMENT)
            if (c.key in keys) return@mapIndexed c.copy(duplicateOf = DuplicateReason.SAME_STATEMENT)
            val ref = c.row.reference?.takeIf { it.length >= 6 }
            if (ref != null) {
                val hit = byAmount[c.row.amountMinor to c.row.type]?.firstOrNull { i ->
                    !used[i] && existing[i].reference?.let { it == ref || (ref.length >= 10 && it.endsWith(ref)) || (it.length >= 10 && ref.endsWith(it)) } == true
                }
                if (hit != null) { used[hit] = true; return@mapIndexed linked(c, existing[hit], DuplicateReason.REFERENCE) }
            }
            c
        }.toMutableList()

        // Pass 2: same amount and direction, close in time, closest first. A foreign spend's SMS gives the amount
        // in dollars (converted at an approximate rate): the same foreign amount, or a rupee amount close to it.
        val foreignSms = existing.indices.filter { !existing[it].fromStatement && existing[it].key?.startsWith("STMT|") != true }
            .mapNotNull { i -> existing[i].text?.let { Fx.find(it)?.first }?.let { i to it } }
        val pairs = ArrayList<Triple<Int, Int, Long>>()
        result.forEachIndexed { ci, c ->
            if (c.duplicateOf != null) return@forEachIndexed
            val cd = c.row.date.toEpochDay()
            for (i in byAmount[c.row.amountMinor to c.row.type].orEmpty()) {
                if (used[i]) continue
                val e = existing[i]
                val et = tail(e.accountTail)
                // Another card number is another transaction, unless it is the same statement line imported
                // again (an older version of the app, or a different card number typed on import), or a debit
                // card's SMS for a card payment from this bank account.
                if (!sameAccount(c.accountTail, et) && !(e.fromStatement && sameLine(c.row.description, e.text)) &&
                    !(!e.fromStatement && !c.cardStatement && debitCardSms(e.text) && debitCardLine(c.row.description))
                ) continue
                val gap = abs(day(e.timestamp) - cd)
                if (gap <= windowDays) pairs += Triple(ci, i, gap * 10 + (if (et != null && c.accountTail != null && sameAccount(et, c.accountTail)) 0 else 1))
            }
            for ((i, fx) in foreignSms) {
                if (used[i]) continue
                val e = existing[i]
                if (e.type != c.row.type || e.amountMinor == c.row.amountMinor) continue
                val exact = c.row.foreign?.let { f -> Fx.find(f)?.first == fx } == true
                val close = c.row.foreign == null && abs(e.amountMinor - c.row.amountMinor) * 100 <= c.row.amountMinor * FX_TOLERANCE_PCT
                // Only on the same card: rupee amounts that are merely close need the card to agree.
                val et = tail(e.accountTail)
                if (!(exact || close) || c.accountTail == null || et == null || !sameAccount(c.accountTail, et)) continue
                val gap = abs(day(e.timestamp) - cd)
                if (gap <= windowDays) pairs += Triple(ci, i, gap * 10 + (if (exact) 2 else 5))
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

    /** How far (percent) a foreign spend's SMS amount, converted at an approximate rate, may be from the statement's. */
    private const val FX_TOLERANCE_PCT = 15

    /**
     * Two account numbers (last digits) can be the same account: either is unknown, they are equal, or one is
     * the end of the other. ICICI's SMS print 3 digits ("Acct XX144") where its statements print 4 (4144).
     */
    fun sameAccount(a: String?, b: String?): Boolean {
        val x = a?.filter(Char::isDigit)?.ifEmpty { null } ?: return true
        val y = b?.filter(Char::isDigit)?.ifEmpty { null } ?: return true
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= 3 && long.endsWith(short)
    }

    private val debitCardWords = Regex("""\bdebit\s*card\b""", RegexOption.IGNORE_CASE)
    private val creditCardWords = Regex("""\bcredit\s*card\b""", RegexOption.IGNORE_CASE)
    /** A bank statement line paid with the account's debit card (shop, online or ATM). */
    private val debitCardNarration = Regex("""^(DEBIT CARD\b|ICICI ATM\b|OTHER ATMS\b)|\b(VPS|IPS|MPS|PCD|POS|ATM|NFS|VIN|DCARD)/|\bPOS\s|\bATM\s?WDL|CASH WDL""", RegexOption.IGNORE_CASE)

    /** An SMS about a debit card ("Debit Card XX1234"), which names the card, not the account. */
    private fun debitCardSms(text: String?) = text != null && debitCardWords.containsMatchIn(text) && !creditCardWords.containsMatchIn(text)
    private fun debitCardLine(narration: String) = debitCardNarration.containsMatchIn(narration.trim())

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
    fun lineForSms(
        sms: ExistingTxn,
        lines: List<ExistingTxn>,
        windowDays: Long = 2,
        zone: ZoneId = ZoneId.systemDefault(),
        /** Without a shared reference, both must name an account (or the SMS be a debit card's). */
        needAccount: Boolean = false,
    ): Int? {
        fun tail(s: String?) = s?.filter(Char::isDigit)?.takeLast(4)?.ifEmpty { null }
        val st = tail(sms.accountTail)
        val ref = sms.reference?.takeIf { it.length >= 6 }
        val same = lines.indices.filter { lines[it].amountMinor == sms.amountMinor && lines[it].type == sms.type }
        if (ref != null) {
            same.firstOrNull { i -> lines[i].reference?.let { it == ref || (it.length >= 10 && ref.endsWith(it)) || (ref.length >= 10 && it.endsWith(ref)) } == true }
                ?.let { return it }
        }
        val day = Dates.localDate(sms.timestamp, zone).toEpochDay()
        fun gap(i: Int) = abs(Dates.localDate(lines[i].timestamp, zone).toEpochDay() - day)
        val debitCard = debitCardSms(sms.text)
        same.filter { i ->
            val lt = tail(lines[i].accountTail)
            val account = if (needAccount) st != null && lt != null && sameAccount(st, lt) else sameAccount(st, lt)
            (account || debitCard && debitCardLine(lines[i].text.orEmpty().removePrefix("Statement: "))) && gap(i) <= windowDays
        }.minWithOrNull(
            compareBy<Int> { gap(it) }
                .thenBy { if (st != null && tail(lines[it].accountTail) != null && sameAccount(tail(lines[it].accountTail), st)) 0 else 1 },
        )?.let { return it }
        // A foreign spend's SMS: its rupee amount is only an estimate. A close amount on the same card.
        if (sms.text?.let { Fx.find(it) } == null || st == null) return null
        return lines.indices.filter { i ->
            val l = lines[i]
            val lt = tail(l.accountTail)
            l.type == sms.type && lt != null && sameAccount(st, lt) && gap(i) <= windowDays &&
                abs(l.amountMinor - sms.amountMinor) * 100 <= l.amountMinor * FX_TOLERANCE_PCT
        }.minWithOrNull(compareBy<Int> { gap(it) }.thenBy { abs(lines[it].amountMinor - sms.amountMinor) })
    }

    /**
     * Stored SMS entries that are the same payment as a stored statement line, kept twice by earlier versions
     * (ICICI's SMS name the account by 3 digits, its statements by 4; a debit card's SMS names the card; a
     * foreign spend's SMS is in dollars). Returns (index in [sms], index in [lines]) pairs, one to one.
     * Stricter than importing: both sides must name an account (or be a debit card payment), so payments on
     * unknown accounts are never paired by amount alone.
     */
    fun storedSmsDoubles(
        sms: List<ExistingTxn>,
        lines: List<ExistingTxn>,
        windowDays: Long = 2,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<Int, Int>> {
        val order = lines.indices.sortedBy { lines[it].timestamp }
        val times = LongArray(order.size) { lines[order[it]].timestamp }
        val used = BooleanArray(lines.size)
        val span = (windowDays + 1) * 24 * 60 * 60 * 1000L
        val out = ArrayList<Pair<Int, Int>>()
        for (si in sms.indices.sortedBy { sms[it].timestamp }) {
            val s = sms[si]
            var lo = java.util.Arrays.binarySearch(times, s.timestamp - span).let { if (it < 0) -it - 1 else it }
            while (lo > 0 && times[lo - 1] >= s.timestamp - span) lo--
            val near = ArrayList<Int>()
            var k = lo
            while (k < order.size && times[k] <= s.timestamp + span) {
                val li = order[k]
                if (!used[li] && lines[li].type == s.type) near += li
                k++
            }
            if (near.isEmpty()) continue
            val pick = lineForSms(s, near.map { lines[it] }, windowDays, zone, needAccount = true) ?: continue
            used[near[pick]] = true
            out += si to near[pick]
        }
        return out
    }

    /** Totals for the preview. */
    /** A stored statement line, for finding the same line stored twice under two card numbers. */
    data class StoredLine(val id: Long, val day: Long, val amountMinor: Long, val type: TxnType, val text: String?, val tail: String?, val edited: Boolean)

    /**
     * Statement lines stored twice, once under each of two cards (an earlier import read the card heading
     * differently): the same day, amount, direction and narration on different cards. The latest import's card
     * is kept; the others' lines are returned to remove, never one the user edited.
     */
    fun storedRepeats(lines: List<StoredLine>): List<Long> {
        val out = ArrayList<Long>()
        lines.filter { it.tail != null && !it.text.isNullOrBlank() }
            .groupBy { listOf(it.day, it.amountMinor, it.type, words(it.text!!)) }
            .values.filter { g -> g.mapTo(HashSet()) { it.tail }.size > 1 }
            .forEach { g ->
                val byTail = g.groupBy { it.tail }
                // The card the user edited a line on, else the latest import's.
                val keep = byTail.maxWith(compareBy<Map.Entry<String?, List<StoredLine>>> { (_, l) -> l.any { it.edited } }.thenBy { (_, l) -> l.maxOf { it.id } }).key
                byTail.filterKeys { it != keep }.values.flatten().filterNot { it.edited }.forEach { out += it.id }
            }
        return out
    }

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
