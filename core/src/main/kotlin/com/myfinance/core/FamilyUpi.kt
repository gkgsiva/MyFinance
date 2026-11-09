package com.myfinance.core

import java.time.YearMonth
import java.time.ZoneId

/** A payment a family member made through the user's family UPI / UPI Circle. */
data class FamilyPayment(val uid: String, val id: Long, val person: String, val amountMinor: Long, val timestamp: Long, val merchant: String?)

data class FamilyPersonMonth(
    val person: String,
    val spentMinor: Long,
    val count: Int,
    val limitMinor: Long?,
    /** Top payees this month with their totals. */
    val top: List<Pair<String, Long>>,
) {
    val percent: Int? get() = limitMinor?.takeIf { it > 0 }?.let { ((spentMinor * 100) / it).toInt() }
    val over: Boolean get() = limitMinor != null && spentMinor > limitMinor
}

object FamilyUpi {
    const val DEFAULT_PERSON = "Family"

    private val byName = Regex(
        """(?:upi\s*circle|secondary\s+user|delegate[d]?|family)\b[^.]{0,60}?\b(?:by|member|user)\s*[:\-]?\s*([A-Za-z][A-Za-z]{1,20}(?:\s[A-Za-z]{2,20})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val nameBefore = Regex("""\bby\s+([A-Za-z][A-Za-z]{1,20}(?:\s[A-Za-z]{2,20})?)\s+(?:via|using|through)\s+upi\s*circle""", RegexOption.IGNORE_CASE)
    private val stop = setOf("upi", "circle", "you", "your", "the", "a", "an", "mandate", "autopay", "bank", "account", "ac", "on", "for", "rs", "inr")

    /** The family member's name from the bank SMS, when the bank gives it. */
    fun personFromSms(sms: String?): String? {
        if (sms == null) return null
        val m = nameBefore.find(sms) ?: byName.find(sms) ?: return null
        val name = m.groupValues[1].trim().split(' ').filter { it.lowercase() !in stop }.joinToString(" ")
        return name.takeIf { it.length >= 2 }?.lowercase()?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
    }

    /** The person from a label such as "Family UPI - Priya" or "Family: Priya". */
    fun personFromLabels(labels: List<String>): String? = labels.firstNotNullOfOrNull { l ->
        Regex("""^family(?:\s*upi)?\s*[-:–/]\s*(.+)$""", RegexOption.IGNORE_CASE).find(l.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Spending per person in [month], with each person's monthly limit. People with a limit and no spending are listed too. */
    fun month(payments: List<FamilyPayment>, limits: Map<String, Long>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): List<FamilyPersonMonth> {
        val inMonth = payments.filter { YearMonth.from(Dates.localDate(it.timestamp, zone)) == month }
        val people = (inMonth.map { it.person } + limits.keys).distinct()
        return people.map { p ->
            val mine = inMonth.filter { it.person == p }
            FamilyPersonMonth(
                person = p,
                spentMinor = mine.sumOf { it.amountMinor },
                count = mine.size,
                limitMinor = limits[p],
                top = mine.groupBy { it.merchant ?: "Unknown" }.map { (k, v) -> k to v.sumOf { it.amountMinor } }.sortedByDescending { it.second }.take(3),
            )
        }.sortedByDescending { it.spentMinor }
    }

    /** Totals per person for each of the last [months] months, oldest first. */
    fun history(payments: List<FamilyPayment>, last: YearMonth, months: Int, zone: ZoneId = ZoneId.systemDefault()): List<Pair<YearMonth, Map<String, Long>>> =
        (months - 1 downTo 0).map { back ->
            val m = last.minusMonths(back.toLong())
            m to payments.filter { YearMonth.from(Dates.localDate(it.timestamp, zone)) == m }
                .groupBy { it.person }.mapValues { (_, v) -> v.sumOf { it.amountMinor } }
        }
}
