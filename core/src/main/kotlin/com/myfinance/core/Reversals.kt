package com.myfinance.core

import kotlin.math.abs

/** How the money came back. */
enum class ReversalKind(val label: String) {
    /** The payment failed or was reversed by the bank or UPI, usually within minutes. */
    FAILED("Payment failed, money came back"),
    /** The seller refunded the payment. */
    REFUND("Refunded"),
    /** An autopay / UPI mandate held the amount and then released it. */
    HOLD("Autopay hold released"),
}

/**
 * A payment and the money that came back for it: neither is spending or income. [key] is what the app remembers
 * the pair by (and what "Not a reversal" dismisses).
 */
data class ReversalPair(val debit: CheckRow, val credit: CheckRow, val kind: ReversalKind) {
    val key: String get() = keyOf(debit.uid, credit.uid)
    val amountMinor: Long get() = debit.amountMinor
    val gapMs: Long get() = credit.timestamp - debit.timestamp

    companion object {
        fun keyOf(debitUid: String, creditUid: String) = "rev:$debitUid+$creditUid"

        /** (debit uid, credit uid) from a [key]; null when it isn't one. */
        fun uidsOf(key: String): Pair<String, String>? {
            if (!key.startsWith("rev:")) return null
            val body = key.removePrefix("rev:")
            val i = body.indexOf('+')
            if (i <= 0 || i == body.length - 1) return null
            return body.substring(0, i) to body.substring(i + 1)
        }
    }
}

/**
 * Failed payments, reversals, refunds and autopay holds: a debit and a credit of exactly the same amount on the
 * same account, the credit soon after the debit, with something tying them together. These are netted out
 * (neither counted as spending nor as money received) and listed as linked pairs the user can undo.
 *
 * What ties a pair together, strongest first:
 * - the credit says so ("reversal", "refund", "failed", "RVSL", "credited back"): up to 10 days later;
 * - the same seller on both sides ("JIO" paid, "JIO" credited): up to 7 days;
 * - an autopay / mandate message on either side: up to 3 days;
 * - the money came back within the hour, or within a day for an amount with paise (₹2,576.71 back from no one
 *   in particular is not a coincidence).
 *
 * Salary, transfers between own accounts, card bill payments and the user's own entries are never paired.
 */
object Reversals {
    private const val MIN = 60 * 1000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    private val reversalWords = Regex(
        """\brevers(?:al|ed|e)\b|\brvsl\b|\brev\b|\bfailed\b|\bunsuccessful\b|\bdeclined\b|\breturned\b|\brefund(?:ed)?\b|\bchargeback\b|credited\s+back|money\s+back|\bcancel(?:led|lation)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val failedWords = Regex("""\brevers(?:al|ed|e)\b|\brvsl\b|\brev\b|\bfailed\b|\bunsuccessful\b|\bdeclined\b|\breturned\b|credited\s+back""", RegexOption.IGNORE_CASE)
    private val mandateWords = Regex(
        """\bmandate\b|\bauto[\s-]?pay\b|\bautopay\b|\bama\b|\bumrn\b|\bblock(?:ed)?\b|\bhold\b|\bunblock(?:ed)?\b|\breleased?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val salaryWords = Regex("""\bsalary\b|\bsal\b|\bpayroll\b""", RegexOption.IGNORE_CASE)
    private val neverPaired = setOf(DefaultCategories.SALARY, DefaultCategories.TRANSFERS, DefaultCategories.INVESTMENTS)

    private fun texts(r: CheckRow) = listOfNotNull(r.merchant, r.text)

    private fun tail(a: String?) = a?.filter(Char::isDigit)?.takeIf { it.length >= 3 }

    /** Same account, allowing "XX144" against "4144"; unknown on either side is allowed. */
    fun sameAccount(a: String?, b: String?): Boolean {
        val x = tail(a) ?: return true
        val y = tail(b) ?: return true
        return x.endsWith(y.takeLast(minOf(x.length, y.length))) && y.endsWith(x.takeLast(minOf(x.length, y.length)))
    }

    private fun eligible(r: CheckRow) = !r.manual && r.amountMinor > 0 && r.category !in neverPaired &&
        texts(r).none { SelfTransfers.isCardPayment(r.merchant, r.text) || salaryWords.containsMatchIn(it) }

    /** Why [credit] is the money back for [debit], or null when it isn't. */
    fun kindOf(debit: CheckRow, credit: CheckRow): ReversalKind? {
        if (debit.type != TxnType.DEBIT || credit.type != TxnType.CREDIT) return null
        if (debit.amountMinor != credit.amountMinor) return null
        if (!eligible(debit) || !eligible(credit)) return null
        if (!sameAccount(debit.account, credit.account)) return null
        // A family member's money coming back to someone else isn't a reversal.
        if (debit.owner != credit.owner) return null
        val gap = credit.timestamp - debit.timestamp
        // The credit's SMS can arrive a few minutes before the debit's.
        if (gap < -10 * MIN) return null
        val creditSays = texts(credit).any { reversalWords.containsMatchIn(it) }
        if (creditSays && gap <= 10 * DAY) {
            return if (texts(credit).any { failedWords.containsMatchIn(it) }) ReversalKind.FAILED else ReversalKind.REFUND
        }
        val sameSeller = debit.merchant != null && credit.merchant != null &&
            !PayeeNames.isCode(debit.merchant) && Labels.sameSeller(debit.merchant, credit.merchant)
        if (sameSeller && gap <= 7 * DAY) return if (gap <= HOUR) ReversalKind.FAILED else ReversalKind.REFUND
        // Back from someone else by name: a friend paying back, not a reversal.
        val otherPayee = credit.merchant != null && debit.merchant != null && !sameSeller && !PayeeNames.isCode(credit.merchant)
        if (otherPayee) return null
        val mandate = (texts(debit) + texts(credit)).any { mandateWords.containsMatchIn(it) }
        if (mandate && gap <= 3 * DAY) return ReversalKind.HOLD
        if (gap <= HOUR) return ReversalKind.FAILED
        if (gap <= DAY && debit.amountMinor % 100 != 0L) return ReversalKind.FAILED
        return null
    }

    /**
     * Pairs each credit with the closest debit it can be the money back for (each entry used once). Rows already
     * left out of totals are skipped; [dismissed] keys (the user said "Not a reversal") are never paired again.
     */
    fun find(rows: List<CheckRow>, dismissed: Set<String> = emptySet()): List<ReversalPair> {
        val debitsByAmount = rows.filter { it.type == TxnType.DEBIT }.groupBy { it.amountMinor }
        val candidates = ArrayList<ReversalPair>()
        for (c in rows) {
            if (c.type != TxnType.CREDIT) continue
            for (d in debitsByAmount[c.amountMinor].orEmpty()) {
                val kind = kindOf(d, c) ?: continue
                val p = ReversalPair(d, c, kind)
                if (p.key !in dismissed) candidates += p
            }
        }
        val used = HashSet<String>()
        val out = ArrayList<ReversalPair>()
        // Closest first; a debit before the credit beats one just after it.
        for (p in candidates.sortedWith(compareBy<ReversalPair>({ abs(it.gapMs) + if (it.gapMs < 0) 30 * MIN else 0 }, { it.debit.timestamp }))) {
            if (p.debit.uid in used || p.credit.uid in used) continue
            used += p.debit.uid; used += p.credit.uid
            out += p
        }
        return out.sortedByDescending { it.credit.timestamp }
    }
}

/** Payee names that are codes rather than names, and the brand to show instead. */
object PayeeNames {
    /**
     * A UPI handle or reference that is a code, e.g. "yblec123879a52a" or "paytmqr2810050501": no spaces, letters
     * and at least three digits mixed together. Phone numbers and plain names are not codes.
     */
    fun isCode(name: String?): Boolean {
        val n = name?.trim()?.substringBefore('@') ?: return false
        if (n.length < 10 || n.any { it.isWhitespace() }) return false
        val digits = n.count(Char::isDigit)
        val letters = n.count(Char::isLetter)
        if (digits < 3 || letters < 3) return false
        var switches = 0
        for (i in 1 until n.length) if (n[i].isDigit() != n[i - 1].isDigit()) switches++
        return switches >= 3 || (switches >= 2 && digits >= 6)
    }

    private val brands: List<Pair<Regex, String>> = listOf(
        """\bjio\s*(?:fiber|fibre|air\s*fiber|airfiber|broadband|home)\b|\bjio\b.{0,40}\bbroadband\b|\bbroadband\b.{0,40}\bjio\b""" to "Jio broadband",
        """\bairtel\s*(?:xstream|fiber|fibre|broadband|black)\b|\bairtel\b.{0,40}\bbroadband\b""" to "Airtel broadband",
        """\breliance\s*jio\b|\bjio\b|\bjiomobility\b|\brjil\b""" to "Jio",
        """\bairtel\b|\bbharti\s*airtel\b""" to "Airtel",
        """\bvodafone\b|\bvi\s+(?:postpaid|prepaid)\b""" to "Vi",
        """\bbsnl\b""" to "BSNL",
        """\bact\s*fibernet\b""" to "ACT Fibernet",
        """\bhathway\b""" to "Hathway",
        """\btata\s*play\b""" to "Tata Play",
        """\bbescom\b""" to "BESCOM",
        """\btangedco\b|\btneb\b""" to "TANGEDCO",
    ).map { Regex(it.first, RegexOption.IGNORE_CASE) to it.second }

    /** The brand a message names, e.g. "Jio broadband" for a JioFiber autopay. */
    fun brandIn(text: String?): String? = text?.let { t -> brands.firstOrNull { it.first.containsMatchIn(t) }?.second }

    /** [merchant] when it is a real name; else the brand the message names; else [merchant] unchanged. */
    fun better(merchant: String?, text: String?): String? {
        if (merchant != null && !isCode(merchant)) return merchant
        return brandIn(text) ?: merchant
    }
}
