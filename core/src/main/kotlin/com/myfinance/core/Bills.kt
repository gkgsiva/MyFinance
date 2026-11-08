package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId

enum class BillKind(val label: String) { CREDIT_CARD("Credit card bill"), EMI("EMI / loan") }

/** A bill found in an SMS: a credit card statement or an EMI / loan instalment that is coming due. */
data class BillDue(
    val kind: BillKind,
    /** Total amount due (for a card) or the instalment (for an EMI). */
    val amountMinor: Long,
    /** Minimum amount due, cards only. */
    val minDueMinor: Long?,
    val dueDate: LocalDate,
    val accountTail: String?,
    val bank: String?,
) {
    /** e.g. "HDFC Bank card ••1234" or "Bajaj Finance EMI". */
    val title: String
        get() {
            val tail = accountTail?.let { " ••$it" }.orEmpty()
            return when (kind) {
                BillKind.CREDIT_CARD -> (bank?.let { "$it card" } ?: "Credit card") + tail
                BillKind.EMI -> (bank?.let { "$it EMI" } ?: "Loan EMI") + tail
            }
        }
}

/**
 * Spots credit card statement and EMI-due SMS. These are the reminder messages that [SmsParser]
 * deliberately ignores, because no money has moved yet.
 *
 *   "Your HDFC Bank Credit Card XX1234 statement: Total Amt Due Rs.12,345.00, Min Amt Due Rs.620.00, due by 15-Nov-24"
 *   "EMI of Rs 5,250 for loan a/c XX7788 is due on 05-12-2024"
 */
object BillParser {

    private const val AMOUNT = """(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""

    private val totalDue = Regex(
        """(?:total\s+(?:amt|amount)\s+due|total\s+due|total\s+outstanding|statement\s+(?:amt|amount|balance))\s*(?:is|of|:|-|=)?\s*$AMOUNT""",
        RegexOption.IGNORE_CASE,
    )
    private val minDue = Regex(
        """(?:min(?:imum)?\.?\s+(?:amt|amount)\s+due|min\.?\s+due)\s*(?:is|of|:|-|=)?\s*$AMOUNT""",
        RegexOption.IGNORE_CASE,
    )
    private val emiAmount = Regex("""\b(?:emi|instal+ment)\s+(?:amount\s+)?(?:of|for|:)?\s*$AMOUNT""", RegexOption.IGNORE_CASE)
    private val anyAmount = Regex(AMOUNT, RegexOption.IGNORE_CASE)

    private val dueMarker = Regex(
        """(?:payment\s+due\s+date|due\s+date|due\s+on|due\s+by|is\s+due|payable\s+(?:on|by)|pay\s+by|will\s+be\s+(?:debited|deducted|presented)\s+on|by|on)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val cardWords = Regex("""\bcredit\s*card\b|\bcard\b.*\bstatement\b|\bstatement\b.*\bcard\b""", RegexOption.IGNORE_CASE)
    private val cardBillWords = Regex(
        """\bstatement\b|total\s+(?:amt|amount)\s+due|min(?:imum)?\.?\s+(?:amt|amount)\s+due|payment\s+due|is\s+due|due\s+(?:date|on|by)|payable""",
        RegexOption.IGNORE_CASE,
    )
    private val emiWords = Regex("""\bemi\b|\binstal+ment\b|\bloan\b""", RegexOption.IGNORE_CASE)
    private val emiDueWords = Regex(
        """\bdue\b|will\s+be\s+(?:debited|deducted|presented)|payable|scheduled""",
        RegexOption.IGNORE_CASE,
    )
    /** Confirmations, offers and OTPs: not a bill that is still to be paid. */
    private val notABill = Regex(
        """\botp\b|thank\s+you\s+for\s+(?:the\s+)?payment|payment\s+(?:of\s+(?:\S+\s+){1,3})?(?:has\s+been\s+|is\s+)?received|received\s+(?:a\s+)?payment|has\s+been\s+paid|\bpaid\s+successfully|\boffer\b|pre-?approved|\beligible\b|\bapply\b|\boverdue\b.*\bpenalty\b|\bspent\b|(?:has\s+been|is|was)\s+debited""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(sender: String, body: String, receivedAt: Long, zone: ZoneId = ZoneId.systemDefault()): BillDue? {
        val text = body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || notABill.containsMatchIn(text)) return null
        val received = Dates.localDate(receivedAt, zone)

        val kind = when {
            cardWords.containsMatchIn(text) && cardBillWords.containsMatchIn(text) -> BillKind.CREDIT_CARD
            emiWords.containsMatchIn(text) && emiDueWords.containsMatchIn(text) -> BillKind.EMI
            else -> return null
        }

        val dueDate = findDueDate(text, received) ?: return null
        // A reminder about a date long past is noise (e.g. a statement summary of an old bill).
        if (dueDate.isBefore(received.minusDays(3))) return null

        val total = totalDue.find(text)?.groupValues?.get(1)
            ?: (if (kind == BillKind.EMI) emiAmount.find(text)?.groupValues?.get(1) else null)
            ?: anyAmount.findAll(text).firstOrNull { !isNearMinDue(text, it) }?.groupValues?.get(1)
            ?: return null
        val amount = parseAmountToMinor(total)?.takeIf { it > 0 } ?: return null
        val min = if (kind == BillKind.CREDIT_CARD) minDue.find(text)?.groupValues?.get(1)?.let(::parseAmountToMinor) else null

        return BillDue(
            kind = kind,
            amountMinor = amount,
            minDueMinor = min,
            dueDate = dueDate,
            accountTail = SmsParser.accountTail(text),
            bank = SmsParser.bank(sender, text) ?: lenderFromText(text),
        )
    }

    private fun isNearMinDue(text: String, m: MatchResult): Boolean {
        val before = text.substring(maxOf(0, m.range.first - 20), m.range.first)
        return Regex("""min""", RegexOption.IGNORE_CASE).containsMatchIn(before)
    }

    /** Prefers a date right after a "due" phrase; falls back to the first date in the message. */
    private fun findDueDate(text: String, received: LocalDate): LocalDate? {
        val strong = dueMarker.findAll(text).filter { !it.value.equals("by", true) && !it.value.equals("on", true) }
        for (m in strong) {
            val after = text.substring(m.range.last + 1).take(30)
            Dates.findDate(after, received)?.let { return it }
        }
        return Dates.findDate(text, received)
    }

    private val lenders = listOf(
        "Bajaj Finance" to "bajaj", "HDB Financial" to "hdb", "Tata Capital" to "tata capital",
        "Home Credit" to "home credit", "LIC Housing" to "lic housing", "Muthoot" to "muthoot",
    )

    private fun lenderFromText(text: String): String? {
        val lower = text.lowercase()
        return lenders.firstOrNull { lower.contains(it.second) }?.first
    }

    /**
     * True when [txns] show the bill being paid: for a card, money credited to that card (or any
     * payment mentioning the card) since a few days before the statement; for an EMI, a debit of
     * about the instalment amount around the due date.
     */
    fun isPaid(bill: BillDue, txns: List<TxnLite>, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val from = bill.dueDate.minusDays(if (bill.kind == BillKind.CREDIT_CARD) 25 else 7)
        val to = bill.dueDate.plusDays(5)
        return txns.any { t ->
            val d = Dates.localDate(t.timestamp, zone)
            if (d.isBefore(from) || d.isAfter(to)) return@any false
            val sameAccount = bill.accountTail != null && t.account?.endsWith(bill.accountTail) == true
            when (bill.kind) {
                BillKind.CREDIT_CARD -> sameAccount && t.type == TxnType.CREDIT &&
                    t.amountMinor >= (bill.minDueMinor ?: bill.amountMinor)
                BillKind.EMI -> t.type == TxnType.DEBIT && kotlin.math.abs(t.amountMinor - bill.amountMinor) <= bill.amountMinor / 50 &&
                    (sameAccount || t.category == DefaultCategories.EMI)
            }
        }
    }
}
