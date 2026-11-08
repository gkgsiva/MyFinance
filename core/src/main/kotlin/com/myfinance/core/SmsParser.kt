package com.myfinance.core

/**
 * Heuristic parser for Indian bank, card and UPI transaction SMS.
 *
 * Works on the common formats used by HDFC, SBI, ICICI, Axis, Kotak and others:
 *   "Rs.500.00 debited from a/c XX1234 on 05-11-24 to VPA swiggy@icici (UPI Ref 431234567890)"
 *   "Spent Rs 1,299 on HDFC Bank Card x4321 at AMAZON on 2024-11-05"
 *   "Your A/c XX5678 is credited with INR 50,000.00 on 01-11-24 by NEFT from ACME CORP"
 *
 * Returns null for anything that is not a completed transaction (OTPs, promos, reminders, failures).
 */
object SmsParser {

    private const val AMOUNT = """(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    private val amountRegex = Regex(AMOUNT, RegexOption.IGNORE_CASE)

    // Some banks (notably SBI UPI) omit the currency: "debited by 1,499.00".
    private val bareAmountRegex = Regex(
        """\b(?:debited|credited)\s+(?:by|for|with)\s+([0-9][0-9,]*(?:\.[0-9]{1,2})?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val balanceHint = Regex(
        """(?:bal(?:ance)?|lmt|limit)\.?\s*(?:is|:|-|of|=)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val ignoreRegex = Regex(
        listOf(
            """\botp\b""", """one[\s-]time\s+password""", """verification\s+code""",
            """\bdeclined\b""", """\bfailed\b""", """\bunsuccessful\b""",
            """will\s+be\s+(?:debited|credited|deducted)""", """\bis\s+due\b""", """\bdue\s+(?:on|by)\b""",
            """payment\s+request""", """\brequested\b""", """\bpre-?approved\b""", """\boffer\b""",
            """\bminimum\s+amount\s+due\b""", """\bauto-?pay\b.*\bscheduled\b""",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    private val debitWords = Regex(
        """\b(?:debited|debit|spent|paid|withdrawn|withdrawal|sent|purchase|purchased|deducted|txn\s+of|transferred\s+to|dr)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val creditWords = Regex(
        """\b(?:credited|credit(?!\s+card)|received|deposited|refund(?:ed)?|reversed|cashback|cr)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val accountRegex = Regex(
        """(?:a/c|acct|account|card|ac)\s*(?:no\.?|number)?\s*(?:ending\s*(?:with|in)?\s*)?[:\-]?\s*[xX*.]*\s?([0-9]{3,6})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val maskedRegex = Regex("""[xX*]{2,}\s?([0-9]{3,6})\b""")

    private val refRegex = Regex(
        """(?:upi\s*ref(?:\.|erence)?\s*(?:no\.?)?|ref(?:\.|erence)?\s*(?:no\.?|number|#)?|rrn|txn\s*id|utr)\s*[:\-]?\s*([A-Za-z0-9]{6,})""",
        RegexOption.IGNORE_CASE,
    )

    // Stops a merchant capture at common separators.
    private const val STOP =
        """(?=\s+on\s|\s+ref|\s+upi\b|\s+via\b|\s+using\b|\s+avl\b|\s+avail|\s+bal\b|\s+dated\b|\s+at\s|\s+txn\b|\s*[.,;(]|\s*$|\s+for\s|\s+if\s|\s+not\s|\s+from\s|\s+by\s)"""
    private const val NAME = """([A-Za-z0-9][A-Za-z0-9 &'_./@\-]{1,40}?)"""

    private val vpaRegex = Regex("""(?:vpa|upi\s*id)\s*[:\-]?\s*([A-Za-z0-9._\-]+@[A-Za-z]+)""", RegexOption.IGNORE_CASE)
    private val atRegex = Regex("""\bat\s+$NAME$STOP""", RegexOption.IGNORE_CASE)
    private val toRegex = Regex("""\b(?:to|towards|trf\s+to|paid\s+to)\s+$NAME$STOP""", RegexOption.IGNORE_CASE)
    private val fromRegex = Regex("""\b(?:from|by)\s+$NAME$STOP""", RegexOption.IGNORE_CASE)
    private val infoRegex = Regex("""\binfo\s*[:\-]\s*$NAME$STOP""", RegexOption.IGNORE_CASE)

    private val bankCodes = linkedMapOf(
        "HDFC" to "HDFC Bank", "SBI" to "SBI", "ICICI" to "ICICI Bank", "AXIS" to "Axis Bank",
        "KOTAK" to "Kotak Bank", "KMB" to "Kotak Bank", "YES" to "Yes Bank", "IDFC" to "IDFC First",
        "INDUS" to "IndusInd Bank", "PNB" to "PNB", "BOB" to "Bank of Baroda", "BARODA" to "Bank of Baroda",
        "CANARA" to "Canara Bank", "CANBNK" to "Canara Bank", "UNION" to "Union Bank", "UBOI" to "Union Bank",
        "FEDERAL" to "Federal Bank", "FEDBNK" to "Federal Bank", "AUBANK" to "AU Bank", "RBL" to "RBL Bank",
        "PAYTM" to "Paytm", "AMEX" to "Amex", "CITI" to "Citi", "SCB" to "Standard Chartered",
        "HSBC" to "HSBC", "IOB" to "Indian Overseas Bank", "INDBNK" to "Indian Bank", "BOI" to "Bank of India",
    )

    /** Words that mean the "merchant" we captured is really just account boilerplate. */
    private val junkMerchant = Regex(
        """^(?:your|you|a/c|ac|acct|account|card|the|bank|upi|imps|neft|rtgs|mobile|beneficiary|vpa|self|xx\w*|\d+)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(sender: String, body: String, timestamp: Long): ParsedTransaction? {
        val text = body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        if (ignoreRegex.containsMatchIn(text)) return null

        val amountMatch = findTransactionAmount(text) ?: return null
        val amount = parseAmountToMinor(amountMatch.groupValues[1]) ?: return null
        if (amount <= 0) return null

        val type = detectType(text) ?: return null

        val accountTail = (maskedRegex.find(text)?.groupValues?.get(1)
            ?: accountRegex.find(text)?.groupValues?.get(1))

        return ParsedTransaction(
            amountMinor = amount,
            type = type,
            merchant = extractMerchant(text, type),
            accountTail = accountTail,
            bank = bankFromSender(sender) ?: bankFromBody(text),
            reference = refRegex.find(text)?.groupValues?.get(1),
            balanceMinor = findBalance(text),
            timestamp = timestamp,
        )
    }

    private fun findTransactionAmount(text: String): MatchResult? =
        amountRegex.findAll(text).firstOrNull { !isBalance(text, it) } ?: bareAmountRegex.find(text)

    private fun findBalance(text: String): Long? =
        amountRegex.findAll(text).firstOrNull { isBalance(text, it) }
            ?.let { parseAmountToMinor(it.groupValues[1]) }

    private fun isBalance(text: String, m: MatchResult): Boolean {
        val before = text.substring(maxOf(0, m.range.first - 25), m.range.first)
        return balanceHint.containsMatchIn(before)
    }

    /** Whichever direction keyword appears first in the message wins. */
    private fun detectType(text: String): TxnType? {
        val d = debitWords.find(text)?.range?.first
        val c = creditWords.find(text)?.range?.first
        return when {
            d == null && c == null -> null
            c == null -> TxnType.DEBIT
            d == null -> TxnType.CREDIT
            d <= c -> TxnType.DEBIT
            else -> TxnType.CREDIT
        }
    }

    private fun extractMerchant(text: String, type: TxnType): String? {
        vpaRegex.find(text)?.let { return cleanVpa(it.groupValues[1]) }

        val ordered = when (type) {
            TxnType.DEBIT -> listOf(atRegex, toRegex, infoRegex)
            TxnType.CREDIT -> listOf(fromRegex, infoRegex, atRegex)
        }
        for (regex in ordered) {
            for (m in regex.findAll(text)) {
                val cleaned = cleanName(m.groupValues[1]) ?: continue
                return cleaned
            }
        }
        return null
    }

    private fun cleanVpa(vpa: String): String {
        val handle = vpa.substringBefore('@')
        // Phone-number handles (9876543210@ybl) are kept whole so the user can still recognise them.
        return if (handle.all { it.isDigit() }) vpa.lowercase() else handle.lowercase()
    }

    private fun cleanName(raw: String): String? {
        var name = raw.trim().trimEnd('.', '-', '/', ' ')
        if (name.contains('@')) return cleanVpa(name)
        name = name.replace(Regex("""\s+"""), " ")
        if (name.length < 2) return null
        if (junkMerchant.matches(name)) return null
        if (name.split(' ').first().let { junkMerchant.matches(it) }) return null
        if (amountRegex.containsMatchIn(name)) return null
        return name
    }

    fun bankFromSender(sender: String): String? {
        // DLT sender ids look like "VM-HDFCBK", "AD-SBIINB-S", "JD-ICICIT".
        val core = sender.uppercase().split('-').maxByOrNull { it.length } ?: return null
        if (core.all { it.isDigit() || it == '+' }) return null
        return bankCodes.entries.firstOrNull { core.contains(it.key) }?.value
    }

    private fun bankFromBody(text: String): String? {
        val upper = text.uppercase()
        return bankCodes.entries.firstOrNull { Regex("""\b${it.key}\b""").containsMatchIn(upper) }?.value
    }

    /** True when the sender looks like a bank/financial DLT header rather than a person. */
    fun looksLikeFinancialSender(sender: String): Boolean {
        val s = sender.uppercase()
        if (s.replace("+", "").all { it.isDigit() }) return false
        return true
    }
}
