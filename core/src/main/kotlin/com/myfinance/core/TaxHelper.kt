package com.myfinance.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Old-regime Indian income tax deduction sections the app tracks. Limits are per person per financial year. */
enum class TaxSection(val label: String, val limitMinor: Long?) {
    S80C("80C: PPF, ELSS, LIC, EPF, tuition, home loan principal", 1_50_000_00),
    S80CCD1B("80CCD(1B): extra NPS", 50_000_00),
    S80D("80D: health insurance (self & family)", 25_000_00),
    S80D_PARENTS("80D: health insurance (parents)", 25_000_00),
    S80E("80E: education loan interest", null),
    S80G("80G: donations", null),
    S24B("24(b): home loan interest", 2_00_000_00),
    S80TTA("80TTA: savings interest", 10_000_00),
}

data class TaxItem(val section: TaxSection, val amountMinor: Long, val timestamp: Long, val label: String?)

data class SectionTotal(val section: TaxSection, val claimedMinor: Long, val countedMinor: Long, val remainingMinor: Long?)

object TaxHelper {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** Financial year start (April 1) for the given date, e.g. 2026-02-10 -> 2025. */
    fun fyStartYear(date: LocalDate): Int = if (date.monthValue >= 4) date.year else date.year - 1

    fun fyLabel(startYear: Int) = "FY $startYear-${(startYear + 1) % 100}"

    fun fyRange(startYear: Int): LongRange {
        val from = LocalDate.of(startYear, 4, 1).atStartOfDay(IST).toInstant().toEpochMilli()
        val to = LocalDate.of(startYear + 1, 4, 1).atStartOfDay(IST).toInstant().toEpochMilli() - 1
        return from..to
    }

    /** Guesses a section for a debit from its merchant, category and note. Null when it doesn't look tax-saving. */
    fun suggest(merchant: String?, category: String, note: String? = null, smsBody: String? = null): TaxSection? {
        val h = listOfNotNull(merchant, category, note, smsBody).joinToString(" ").lowercase()
        fun has(vararg w: String) = w.any { Regex("""(?<![a-z])${Regex.escape(it)}(?![a-z])""").containsMatchIn(h) }
        return when {
            has("nps", "pran") && has("tier", "80ccd", "voluntary") -> TaxSection.S80CCD1B
            has("parents", "mother", "father") && has("health insurance", "mediclaim", "star health", "care health") -> TaxSection.S80D_PARENTS
            has("health insurance", "mediclaim", "star health", "care health", "niva bupa", "hdfc ergo", "icici lombard", "preventive health") -> TaxSection.S80D
            has("ppf", "elss", "tax saver", "taxsaver", "lic", "life insurance", "epf", "vpf", "sukanya", "nsc", "tuition", "school fees", "term plan", "ulip") -> TaxSection.S80C
            has("nps", "pran") -> TaxSection.S80CCD1B
            has("education loan") -> TaxSection.S80E
            has("donation", "charity", "pm cares", "trust", "foundation", "ngo") -> TaxSection.S80G
            has("home loan interest") -> TaxSection.S24B
            else -> null
        }
    }

    /** Totals for one financial year, with each section capped at its limit. */
    fun yearTotals(items: List<TaxItem>, fyStart: Int): List<SectionTotal> {
        val range = fyRange(fyStart)
        val inYear = items.filter { it.timestamp in range }
        return TaxSection.entries.mapNotNull { s ->
            val claimed = inYear.filter { it.section == s }.sumOf { it.amountMinor }
            if (claimed == 0L) return@mapNotNull null
            val counted = s.limitMinor?.let { minOf(it, claimed) } ?: claimed
            SectionTotal(s, claimed, counted, s.limitMinor?.let { (it - claimed).coerceAtLeast(0) })
        }
    }

    fun totalDeduction(totals: List<SectionTotal>): Long = totals.sumOf { it.countedMinor }

    fun dateOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(IST).toLocalDate()
}
