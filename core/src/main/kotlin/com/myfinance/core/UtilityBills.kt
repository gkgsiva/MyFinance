package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId

/** A utility or service bill read from its SMS: who it is from, how much and when it is due. */
data class UtilityBill(val kind: ReminderKind, val provider: String, val amountMinor: Long, val dueDate: LocalDate) {
    /** Lower-case word that finds the payment and the reminder, e.g. "bescom". */
    val key: String get() = provider.lowercase()
}

/**
 * Electricity, water, gas, mobile postpaid, broadband and property tax bill SMS ("Your BESCOM bill of Rs 1,240 is
 * due on 15-11-2026"). Card and loan bills are read by [BillParser].
 */
object UtilityBills {
    private const val AMOUNT = """(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""

    private val providers: List<Triple<Regex, String, ReminderKind>> = listOf(
        "bescom" to "BESCOM", "tangedco|tneb" to "TANGEDCO", "msedcl|mahavitaran" to "MSEDCL", "tata\\s*power" to "Tata Power",
        "adani\\s*electricity" to "Adani Electricity", "bses" to "BSES", "kseb" to "KSEB", "cesc" to "CESC", "torrent\\s*power" to "Torrent Power",
        "uppcl" to "UPPCL", "apspdcl|apepdcl|apcpdcl" to "APSPDCL", "tsspdcl|tgspdcl" to "TSSPDCL", "hescom|gescom|mescom|cescom" to "ESCOM",
        "electricity" to "Electricity",
    ).map { Triple(Regex("\\b(?:${it.first})\\b", RegexOption.IGNORE_CASE), it.second, ReminderKind.ELECTRICITY) } +
        listOf("bwssb" to "BWSSB", "jal\\s*board" to "Jal Board", "metro\\s*water" to "Metrowater", "water" to "Water")
            .map { Triple(Regex("\\b(?:${it.first})\\b", RegexOption.IGNORE_CASE), it.second, ReminderKind.WATER) } +
        listOf("mahanagar\\s*gas" to "Mahanagar Gas", "\\bigl\\b|indraprastha\\s*gas" to "IGL", "gail\\s*gas" to "GAIL Gas", "piped\\s*gas|png" to "Piped gas")
            .map { Triple(Regex("(?:${it.first})", RegexOption.IGNORE_CASE), it.second, ReminderKind.GAS) } +
        listOf("airtel\\s*xstream|xstream\\s*fiber" to "Airtel Xstream", "jio\\s*fiber" to "JioFiber", "act\\s*fibernet" to "ACT Fibernet", "hathway" to "Hathway", "broadband" to "Broadband", "tata\\s*play|dish\\s*tv|sun\\s*direct" to "DTH")
            .map { Triple(Regex("\\b(?:${it.first})\\b", RegexOption.IGNORE_CASE), it.second, ReminderKind.INTERNET) } +
        listOf("airtel" to "Airtel", "jio" to "Jio", "vodafone|\\bvi\\b" to "Vi", "bsnl" to "BSNL")
            .map { Triple(Regex("\\b(?:${it.first})\\b", RegexOption.IGNORE_CASE), it.second, ReminderKind.MOBILE) } +
        listOf("property\\s*tax" to "Property tax")
            .map { Triple(Regex("\\b(?:${it.first})\\b", RegexOption.IGNORE_CASE), it.second, ReminderKind.PROPERTY_TAX) }

    private val billWords = Regex("""\bbill\b|amount\s+due|amt\s+due|\bpayable\b|due\s+date|\bdue\s+(?:on|by)\b|\bdemand\b""", RegexOption.IGNORE_CASE)
    private val notBill = Regex(
        """\botp\b|thank\s+you\s+for|payment\s+(?:of\s+(?:\S+\s+){1,3})?(?:has\s+been\s+|is\s+)?(?:received|successful)|received\s+(?:your\s+)?payment|has\s+been\s+paid|paid\s+successfully|recharge\s+(?:of\s+\S+\s+)?(?:is\s+)?successful|\boffer\b|cashback|\bspent\b|(?:has\s+been|is|was)\s+debited|\bcredited\b""",
        RegexOption.IGNORE_CASE,
    )
    private val amountAfterWord = Regex("""(?:bill|amount|amt|payable|due)[^0-9₹]{0,25}$AMOUNT""", RegexOption.IGNORE_CASE)
    private val anyAmount = Regex(AMOUNT, RegexOption.IGNORE_CASE)
    private val dueWord = Regex("""due\s+date|due\s+(?:on|by)|pay\s+(?:by|before)|payable\s+(?:on|by|before)|last\s+date""", RegexOption.IGNORE_CASE)

    fun parse(sender: String, body: String, receivedAt: Long, zone: ZoneId = ZoneId.systemDefault()): UtilityBill? {
        val text = body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || notBill.containsMatchIn(text) || !billWords.containsMatchIn(text)) return null
        if (BillParser.parse(sender, body, receivedAt, zone) != null) return null
        val hay = "$sender $text"
        val (_, provider, kind) = providers.firstOrNull { it.first.containsMatchIn(hay) } ?: return null
        val received = Dates.localDate(receivedAt, zone)
        val due = dueWord.findAll(text).firstNotNullOfOrNull { Dates.findDate(text.substring(it.range.last + 1).take(30), received) } ?: return null
        if (due.isBefore(received.minusDays(3)) || due.isAfter(received.plusDays(120))) return null
        val amount = (amountAfterWord.find(text) ?: anyAmount.find(text))?.groupValues?.get(1)?.let(::parseAmountToMinor)?.takeIf { it > 0 } ?: return null
        return UtilityBill(kind, provider, amount, due)
    }

    /** The reminder this bill belongs to: one whose words name the provider, else the only one of the same kind. */
    fun matchReminder(bill: UtilityBill, reminders: List<BillReminder>): BillReminder? =
        reminders.firstOrNull { r -> r.matchWords.any { it.equals(bill.key, true) } || r.name.contains(bill.provider, ignoreCase = true) }
            ?: reminders.filter { it.kind == bill.kind && !it.done }.singleOrNull()
}
