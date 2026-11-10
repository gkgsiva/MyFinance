package com.myfinance.core

import kotlin.math.abs

/** A transaction as the transfer and data health checks see it. */
data class CheckRow(
    val id: Long,
    val uid: String,
    val type: TxnType,
    val amountMinor: Long,
    val account: String?,
    val timestamp: Long,
    val merchant: String?,
    /** SMS or statement text. */
    val text: String?,
    val category: String = DefaultCategories.OTHER,
    val owner: String? = null,
    val manual: Boolean = false,
    val statement: Boolean = false,
    val reference: String? = null,
)

enum class TransferKind(val label: String) {
    OWN_ACCOUNTS("Between your accounts"),
    CARD_BILL("Credit card bill payment"),
}

/**
 * Money that only moved between the family's own accounts, or paid a credit card bill whose spends are
 * already counted. [out] is the debit and [into] the matching credit, when found.
 */
data class TransferMatch(val kind: TransferKind, val out: CheckRow?, val into: CheckRow?) {
    val key: String get() = "xfer:" + listOfNotNull(out?.uid, into?.uid).joinToString("+")
    val amountMinor: Long get() = out?.amountMinor ?: into?.amountMinor ?: 0
    val timestamp: Long get() = out?.timestamp ?: into?.timestamp ?: 0
    val rows: List<CheckRow> get() = listOfNotNull(out, into)
}

/** Finds transfers between own accounts and card bill payments, so they are not counted as spending and income. */
object SelfTransfers {
    private const val DAY = 24 * 60 * 60 * 1000L

    private val cardPayment = Regex(
        """credit\s*card|card\s*payment|cc\s*payment|\bcc\s*bill|card\s*bill|\bcred\b|cred\s*club|billdesk.*card|payment\s+(?:of\s+.{0,20})?received.{0,40}card|card.{0,40}payment\s+received""",
        RegexOption.IGNORE_CASE,
    )
    private val selfWords = Regex("""\bself\b|own\s+a/?c|\bneft\b|\bimps\b|\brtgs\b|\bupi\b|transfer|trf|\bft\b|sweep""", RegexOption.IGNORE_CASE)

    /** A purchase made with the card (shop, online, UPI on a RuPay credit card), not a payment of its bill. */
    private val cardSpend = Regex(
        """\b(spent|used|purchase|txn|transaction|debited|upi)\b.{0,80}\bcredit\s*card|credit\s*card.{0,80}\b(spent|used|purchase|txn|transaction|debited|upi)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Words that only a bill payment uses, even when the SMS also says "debited". */
    private val billWords = Regex(
        """payment\s+(?:of\s+.{0,20})?received|paid\s+towards|\btowards\b|bill\s*(?:payment|paid|pay)|card\s*bill|cc\s*(?:bill|payment)|\bcred\b|billdesk|payment\s+(?:to|for)\s+.{0,30}credit\s*card|card\s*payment|credit\s*card\s*(?:a/?c\s*)?(?:no\.?\s*)?[x*•]*\d{3,4}\s+(?:is\s+|has\s+been\s+)?credited""",
        RegexOption.IGNORE_CASE,
    )

    fun isCardPayment(r: CheckRow): Boolean = isCardPayment(r.merchant, r.text)

    /** A payment received on a card statement ("PAYMENT RECEIVED - THANK YOU", "BBPS PAYMENT", autopay). */
    private val cardStatementPayment = Regex(
        """payment|thank\s*you|autopay|auto\s*debit|\bbbps\b|\bneft\b|\bimps\b|\brtgs\b|\bupi\b|\bcred\b|billdesk""",
        RegexOption.IGNORE_CASE,
    )
    private val notPayment = Regex("""refund|reversal|reversed|cashback|cash\s*back|reward|surcharge\s*waiver|waiver|\bemi\b""", RegexOption.IGNORE_CASE)

    /**
     * True when this is paying a credit card bill, on either side: the debit from the bank account, or the
     * payment received on the card. Neither is spending or income (the card's own purchases are the spending),
     * so these are left out of totals when they are stored.
     */
    fun isCardBillPayment(type: TxnType, merchant: String?, text: String?, cardStatement: Boolean = false): Boolean {
        if (isCardPayment(merchant, text)) return true
        if (!cardStatement || type != TxnType.CREDIT) return false
        val texts = listOfNotNull(merchant, text)
        return texts.any { cardStatementPayment.containsMatchIn(it) } && texts.none { notPayment.containsMatchIn(it) }
    }

    fun isCardPayment(merchant: String?, text: String?): Boolean {
        val texts = listOfNotNull(merchant, text)
        if (texts.none { cardPayment.containsMatchIn(it) }) return false
        // "INR 324 spent on Credit Card XX7002 via UPI": spending on the card, not paying its bill.
        val spend = texts.any { cardSpend.containsMatchIn(it) }
        return !spend || texts.any { billWords.containsMatchIn(it) }
    }

    /**
     * Pairs each debit with a credit of the same amount into a different account within 3 days (closest first),
     * then lists card bill payments that were not paired. [dismissed] keys ("not a transfer") are left out.
     */
    fun find(rows: List<CheckRow>, dismissed: Set<String> = emptySet()): List<TransferMatch> {
        val credits = rows.filter { it.type == TxnType.CREDIT && it.account != null }.sortedBy { it.timestamp }
        val usedCredits = HashSet<String>()
        val usedDebits = HashSet<String>()
        val out = ArrayList<TransferMatch>()
        for (d in rows.filter { it.type == TxnType.DEBIT && it.account != null }.sortedBy { it.timestamp }) {
            val c = credits
                .filter {
                    it.uid !in usedCredits && it.amountMinor == d.amountMinor && it.account != d.account &&
                        abs(it.timestamp - d.timestamp) <= 3 * DAY
                }
                .minByOrNull { abs(it.timestamp - d.timestamp) } ?: continue
            // Same amount into another account: a transfer if either side reads like one or a card payment.
            val looksLike = listOf(d, c).any { r -> listOfNotNull(r.merchant, r.text).any { selfWords.containsMatchIn(it) } || isCardPayment(r) } ||
                d.category == DefaultCategories.TRANSFERS || c.category == DefaultCategories.TRANSFERS
            if (!looksLike) continue
            usedCredits += c.uid
            usedDebits += d.uid
            out += TransferMatch(if (isCardPayment(d) || isCardPayment(c)) TransferKind.CARD_BILL else TransferKind.OWN_ACCOUNTS, d, c)
        }
        for (r in rows) {
            if (r.uid in usedDebits || r.uid in usedCredits || !isCardPayment(r)) continue
            out += if (r.type == TxnType.DEBIT) TransferMatch(TransferKind.CARD_BILL, r, null) else TransferMatch(TransferKind.CARD_BILL, null, r)
        }
        return out.filter { it.key !in dismissed }.sortedByDescending { it.timestamp }
    }
}
