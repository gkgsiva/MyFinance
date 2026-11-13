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
            """\bdeclined\b""", """\bfailed\b""", """\bunsuccessful\b""",
            """will\s+be\s+(?:auto[\s-]?)?(?:debited|credited|deducted|charged)""", """\b(?:will|shall)\s+(?:auto[\s-]?)?debit\b""",
            """\bis\s+due\b""", """\bdue\s+(?:on|by)\b""",
            """payment\s+request""", """\brequested\b""", """\bpre-?approved\b""", """\boffer\b""",
            """\bminimum\s+amount\s+due\b""", """\bauto-?pay\b.*\bscheduled\b""",
            // Ideas from omoi-sms-parser (MIT) and kharcha (Apache-2.0): marketing and money that did not move.
            """\b(?:coupon|voucher|lucky\s+draw|scratch\s+card|spin\s+(?:the\s+wheel|to\s+win)|refer\s*(?:and|&)\s*earn)\b""",
            """\b(?:returned\s+unpaid|dishono(?:u)?red|bounced)\b""",
            """\bmatures\s+on\b|\bmaturity\s+date\b""",
            """(?:rs\.?|inr|₹)\s?[\d,]+(?:\.\d{1,2})?\s*off\b""",
            """\b(?:get|earn|win|avail|assured|flat|upto|up\s+to)\s+(?:(?:rs\.?|inr|₹)\s?[\d,]+(?:\.\d{1,2})?\s+)?cashback\b|\bcashback\s+(?:offer|waiting)\b""",
            """\b(?:flat|upto|up\s+to|at\s+just|starting\s+(?:at|from))\s+(?:rs\.?|inr|₹)""",
            """\b(?:shop\s+now|buy\s+now|claim\s+now|apply\s+now|t\s*&\s*c\s+apply|tnc\s+apply)\b""",
            """\b(?:congratulations|you\s+have\s+won|you've\s+won)\b""",
            """\bspent\b.{0,80}\bthis\s+(?:month|week|year)\b""", """\bin\s+the\s+(?:last|past)\s+\d+\s+days\b""",
            """\brefund\s+(?:has\s+been\s+|is\s+)?initiated\b""", """\bwill\s+(?:be\s+)?reflect""",
            """\b(?:credit\s+)?limit\s+(?:has\s+been\s+)?(?:increased|enhanced|upgraded)\b""",
            """\b(?:collect|mandate|autopay)\s+request\b""",
            // Autopay / UPI mandate notices: setting one up, the pre-debit notice and an amount held for it move no money.
            """\b(?:mandate|auto[\s-]?pay)\b.{0,80}\b(?:created|registered|set\s*up|modified|revoked|paused|cancell?ed|approved|activated)\b""",
            """\bpre-?debit\s+(?:notification|intimation|alert|notice)\b""",
            """\b(?:blocked|unblocked|on\s+hold)\b.{0,60}\b(?:mandate|auto[\s-]?pay)\b|\b(?:mandate|auto[\s-]?pay)\b.{0,60}\b(?:blocked|unblocked|on\s+hold)\b""",
            """\b(?:is|was|remains)\s+pending\b|\bin\s+progress\b|\bprocessing\s+(?:your\s+)?(?:payment|request|transaction)\b""",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    /**
     * An OTP message binds the word to a code ("123456 is your OTP", "OTP for txn of Rs 500 is 123456").
     * The word alone is not enough: real debit alerts end with "Never share your OTP / CVV" (from omoi-sms-parser).
     */
    private val otpRegex = Regex(
        listOf(
            """\b\d{3,8}\s+is\s+(?:the\s+|your\s+|an?\s+)?(?:otp|one[\s-]time\s+password|verification\s+code|secret\s+code)\b""",
            """\b(?:otp|one[\s-]time\s+password|verification\s+code)\b.{0,80}?(?:\bis\b|:)\s*\d{3,8}\b""",
            """\b(?:use|enter)\s+(?:otp|one[\s-]time\s+password)\s*:?\s*\d{3,8}\b""",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    /** Card bill / statement SMS: the spends in it were already counted (read by the bill reminders instead). */
    private val cardBillRegex = Regex(
        """\btotal\s+(?:amount\s+)?due\b|\bmin(?:imum)?\.?\s+(?:amt|amount)\s+(?:due|of)\b|\bbill\s+(?:has\s+been\s+|is\s+)?generated\b|\bstatement\s+(?:has\s+been\s+|is\s+)?generated\b""",
        RegexOption.IGNORE_CASE,
    )
    private val completedMoney = Regex("""\b(?:received|credited|thank\s+you|debited|spent)\b""", RegexOption.IGNORE_CASE)

    private val debitWords = Regex(
        """\b(?:debited|debit|spent|paid|withdrawn|withdrawal|sent|purchase|purchased|deducted|txn\s+of|transferred\s+to|dr|charged|used\s+(?:for|at)|(?:a\s+)?transaction\s+of)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val creditWords = Regex(
        """\b(?:credited|credit(?!\s+card)|received|deposited|refund(?:ed)?|reversed|cashback|cr)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val accountRegex = Regex(
        """(?:a/c|acct|account|card|ac)\s*(?:no\.?|number)?\s*(?:ending\s*(?:with|in)?\s*)?[:\-]?\s*[xX*.•●]*\s?([0-9]{3,6})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val maskedRegex = Regex("""[xX*•●]{2,}\s?([0-9]{3,6})\b""")

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
        // More DLT header families (from omoi-sms-parser's sender list and transaction-sms-parser's cards).
        "PAYZAP" to "HDFC Bank", "ICBANK" to "ICICI Bank", "IDBI" to "IDBI Bank", "BANDHN" to "Bandhan Bank",
        "UCOBNK" to "UCO Bank", "KVBANK" to "Karur Vysya Bank", "SIBSMS" to "South Indian Bank", "DBSBNK" to "DBS Bank",
        "CSBBNK" to "CSB Bank", "EQUTAS" to "Equitas Bank", "ONECRD" to "OneCard", "ONECARD" to "OneCard",
        "SLCEIT" to "slice", "SLICE" to "slice", "SCAPIA" to "Scapia", "JUPITR" to "Jupiter",
        "AXSFIN" to "Axis Bank", "KTKREM" to "Kotak Bank", "CAANBK" to "Canara Bank", "FEDBK" to "Federal Bank",
        "AMZPAY" to "Amazon Pay", "PHONPE" to "PhonePe", "MOBIKW" to "MobiKwik", "FRCHRG" to "Freecharge",
    )

    /** Words that mean the "merchant" we captured is really just account boilerplate. */
    private val junkMerchant = Regex(
        """^(?:your|you|a/c|ac|acct|account|card|the|bank|upi|imps|neft|rtgs|mobile|beneficiary|vpa|self|xx\w*|\d+)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [rates] are rupees per unit of other currencies: a card spend in USD with no rupee amount is converted
     * with them, and the foreign amount is returned in [ParsedTransaction.foreign].
     */
    fun parse(sender: String, body: String, timestamp: Long, rates: Map<String, Double> = Fx.defaultRates): ParsedTransaction? {
        val text = body.replace('\n', ' ').replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        if (otpRegex.containsMatchIn(text)) return null
        if (ignoreRegex.containsMatchIn(text)) return null
        if (cardBillRegex.containsMatchIn(text) && !completedMoney.containsMatchIn(text)) return null
        // A biller's own receipt ("Payment of Rs 2576.71 for your JioHome connection has been received"): the
        // bank's debit is the transaction; this is not money coming in.
        if (isBillerReceipt(sender, text)) return null

        val amountMatch = findTransactionAmount(text)
        // A foreign amount before any rupee amount ("Spent USD 12.99 on card ..."): convert it.
        val foreign = Fx.find(text)?.takeIf { (_, at) -> amountMatch == null || at < amountMatch.range.first }?.first
        val rate = foreign?.let { Fx.rate(it.code, rates) }
        val amount = if (foreign != null && rate != null) Fx.toInrMinor(foreign, rate)
            else parseAmountToMinor((amountMatch ?: return null).groupValues[1]) ?: return null
        if (amount <= 0) return null

        val type = detectType(text) ?: return null

        val accountTail = fastagTag.find(text)?.takeIf { fastag.containsMatchIn(text) }?.groupValues?.get(1)?.uppercase() ?: accountTail(text)

        return ParsedTransaction(
            amountMinor = amount,
            type = type,
            // A code for a payee ("yblec123879a52a") gives way to the brand the message names ("Jio broadband").
            merchant = PayeeNames.better(extractMerchant(text, type), text),
            accountTail = accountTail,
            bank = instrument(text) ?: bankFromSender(sender) ?: bankFromBody(text),
            reference = refRegex.find(text)?.groupValues?.get(1) ?: channelRef.find(text)?.groupValues?.get(1),
            balanceMinor = findBalance(text),
            timestamp = timestamp,
            foreign = foreign.takeIf { rate != null },
            fxRate = rate,
            counterTail = (if (type == TxnType.DEBIT) toOwnAccount else fromOwnAccount).find(text)?.groupValues?.get(1),
        )
    }

    private val receiptWords = Regex(
        """\bpayment\s+(?:of\s+)?(?:rs\.?|inr|₹)\s?[\d,]+(?:\.\d{1,2})?.{0,140}?\b(?:has\s+been\s+|is\s+|was\s+)?received\b|\bthank\s+you\s+for\s+(?:your\s+|the\s+)?payment\b|\bwe\s+have\s+received\s+(?:your\s+|the\s+)?payment\b|\breceived\s+your\s+payment\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Words that put the money into one of the user's own accounts or cards. */
    private val ownAccountWords = Regex(
        """\b(?:a/c|acct|account|card|wallet)\b|\bcredited\s+(?:to|in)\b|\bin\s+your\s+(?:bank|a/c|account)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A biller's receipt for a payment the user made (JioHome, BESCOM, an insurer): sent by the biller, not a bank,
     * and naming no account or card of the user's. The bank's debit SMS is the transaction; this is not income.
     */
    fun isBillerReceipt(sender: String, body: String): Boolean {
        val text = body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (!receiptWords.containsMatchIn(text)) return false
        if (bankFromSender(sender) != null || ownAccountWords.containsMatchIn(text) || accountTail(text) != null) return false
        return true
    }

    /** The amount and brand of a biller's receipt ("Rs. 2576.71", "Jio broadband"), or null when it isn't one. */
    fun billerReceipt(sender: String, body: String): Pair<Long, String?>? {
        if (!isBillerReceipt(sender, body)) return null
        val text = body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        val amount = findTransactionAmount(text)?.groupValues?.get(1)?.let(::parseAmountToMinor)?.takeIf { it > 0 } ?: return null
        return amount to PayeeNames.brandIn("$sender $text")
    }

    // Meal cards and FASTag are their own instruments, whichever bank issues them.
    private val mealCard = Regex("""\b(?:pluxee|sodexo)\b|\bmeal\s+card\b|\bzeta\b.*\bmeal\b""", RegexOption.IGNORE_CASE)
    private val fastag = Regex("""\bfas\s*tag\b""", RegexOption.IGNORE_CASE)
    private val fastagTag = Regex("""\btag\s*id\s*[:\-]?\s*([A-Za-z0-9]{4,})\b""", RegexOption.IGNORE_CASE)

    private fun instrument(text: String): String? = when {
        fastag.containsMatchIn(text) -> "FASTag"
        mealCard.containsMatchIn(text) -> if (Regex("""\bpluxee\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) "Pluxee meal card" else "Meal card"
        else -> null
    }

    /** "UPI:117869245541", "IMPS:630721708826", "UPI-841795829162-HungerBo": the 12-digit reference. */
    private val channelRef = Regex("""\b(?:upi|imps|neft)\s*[:\-/]\s*(\d{12})\b""", RegexOption.IGNORE_CASE)

    /** ICICI: "Acct XX144 debited with Rs 22,000.00 & Acct XX848 credited": the other account. */
    private val toOwnAccount = Regex("""(?:&|\band\b)\s*(?:your\s+)?(?:a/c|acct|account)\s*(?:no\.?)?\s*[xX*•]*\s?(\d{3,6})\s+(?:is\s+)?credited""", RegexOption.IGNORE_CASE)
    private val fromOwnAccount = Regex("""\bfrom\s+(?:your\s+)?(?:a/c|acct|account)\s*(?:no\.?)?\s*[xX*•]+\s?(\d{3,6})\b""", RegexOption.IGNORE_CASE)


    /** Last digits of the account or card the message is about, e.g. "1234" from "A/c XX1234". */
    fun accountTail(text: String): String? =
        maskedRegex.find(text)?.groupValues?.get(1) ?: accountRegex.find(text)?.groupValues?.get(1)

    /** Bank named by the sender id, or failing that by the message. */
    fun bank(sender: String, text: String): String? = bankFromSender(sender) ?: bankFromBody(text)

    /**
     * The transaction amount is the leftmost amount once balance / limit / outstanding clauses are blanked
     * out (idea from omoi-sms-parser, MIT). Blanking keeps every index in place, so "debited by 35.0 ... Avl Bal
     * Rs 800.50" can only ever read 35.0.
     */
    private fun findTransactionAmount(text: String): MatchResult? {
        val masked = maskBalances(text)
        return listOfNotNull(
            amountRegex.findAll(masked).firstOrNull { !isBalance(masked, it) },
            bareAmountRegex.find(masked),
            transactionOfRegex.find(masked),
        ).minByOrNull { it.range.first }
    }

    private val balanceClauses = listOf(
        Regex("""\b(?:avl|avbl|avail|available|total|updated|remaining|current|curr|clr|ledger|closing)(?:\.?\s*avail)?\.?\s*(?:credit\s+)?(?:bal(?:ance)?|lmt|limit)\b[^\d\n]{0,30}(?:rs\.?|inr|₹)?\s*[\d,]+(?:\.\d{1,2})?""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:balance|credit\s+limit)\s*(?:is|:)[^\d\n]{0,20}(?:rs\.?|inr|₹)?\s*[\d,]+(?:\.\d{1,2})?""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:total\s+)?outstanding\b[^\d\n]{0,20}(?:rs\.?|inr|₹)?\s*[\d,]+(?:\.\d{1,2})?""", RegexOption.IGNORE_CASE),
    )

    /** Blanks balance and limit clauses with spaces, keeping the text's length. */
    fun maskBalances(text: String): String =
        balanceClauses.fold(text) { t, re -> re.replace(t) { m -> " ".repeat(m.value.length) } }

    // HDFC reversals omit the currency: "Your UPI transaction of 50.00 has been reversed".
    private val transactionOfRegex = Regex("""\btransaction\s+of\s+([0-9][0-9,]*(?:\.[0-9]{1,2})?)\b""", RegexOption.IGNORE_CASE)

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
        // "UPI/P2M/431234567890/SWIGGY" style narrations quoted in the SMS (from kharcha, Apache-2.0).
        upiNarration.find(text)?.let { m -> cleanName(m.groupValues[1])?.let { return it } }
        // ICICI card: "for UPI-841795829162-HungerBo."
        upiDash.find(text)?.let { m -> cleanName(m.groupValues[1])?.let { return it } }
        if (type == TxnType.DEBIT) toOwnAccount.find(text)?.let { return "A/c ••" + it.groupValues[1] }
        // ICICI: "Acct XX123 debited for Rs 500.00 on 05-Nov-24; SWIGGY credited. UPI:431234567890."
        if (type == TxnType.DEBIT) iciciPayee.find(text)?.let { m -> cleanName(m.groupValues[1])?.let { return it } }
        for (regex in ordered) {
            for (m in regex.findAll(text)) {
                val cleaned = cleanName(m.groupValues[1]) ?: continue
                // A whole narration was captured ("Info: UPI/DR/1234/RAVI KUMAR/YESB"): keep only the name in it.
                if (cleaned.count { it == '/' } >= 2) return Narration.merchant(cleaned) ?: cleaned
                return cleaned
            }
        }
        // Last resort: any UPI id in the message (handles from transaction-sms-parser, MIT).
        anyVpa.find(text)?.let { return cleanVpa(it.groupValues[1]) }
        return null
    }

    private val upiDash = Regex("""\bUPI-\d{6,}-([A-Za-z][A-Za-z0-9 &'._@\-]{1,40}?)(?=\s*\.\s|\s*\.$|\s*/|\s+(?:to|on|ref|avl)\b|$)""", RegexOption.IGNORE_CASE)

    private val iciciPayee = Regex(""";\s*([A-Za-z0-9][A-Za-z0-9 &'._@\-]{1,40}?)\s+credited\b""", RegexOption.IGNORE_CASE)

    private val upiNarration = Regex(
        """\bUPI/(?:(?:DR|CR|P2A|P2M|P2P|REV)/)?\d{6,}/([A-Za-z][A-Za-z0-9 &.'\-]{1,40}?)(?=\s*/|\s*\.\s|\s*\.$|\s+(?:avl|available|bal|ref|upi|on|not|if|call|sms)\b|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val anyVpa = Regex(
        """(?<![\w.@-])([A-Za-z0-9][A-Za-z0-9._\-]{1,40}@(?:ybl|ibl|axl|paytm|ptyes|ptaxis|pthdfc|ptsbi|okaxis|okhdfcbank|okicici|oksbi|upi|apl|yapl|icici|sbi|hdfcbank|axisbank|kotak|federal|indus|rbl|idfcbank|aubank|barodampay|uboi|unionbank|yesbank|jupiteraxis|fbl|airtel|freecharge|ikwik|naviaxis|slc|waaxis|wahdfcbank|wasbi|waicici))\b""",
        RegexOption.IGNORE_CASE,
    )

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
        val s = sender.uppercase().trim()
        if (s.replace("+", "").replace(" ", "").all { it.isDigit() }) return false
        // TRAI DLT headers end "-P" for promotional traffic ("VM-HDFCBK-P"): never a transaction (omoi-sms-parser).
        if (Regex("""^[A-Z]{2}-[A-Z0-9]{3,9}-P$""").matches(s)) return false
        return true
    }
}
