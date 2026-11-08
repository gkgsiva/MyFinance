package com.myfinance.core

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId

/** Small date helpers shared by the bill parser and the reports. */
object Dates {

    private val months = mapOf(
        "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "apr" to Month.APRIL,
        "may" to Month.MAY, "jun" to Month.JUNE, "jul" to Month.JULY, "aug" to Month.AUGUST,
        "sep" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
    )

    private const val MON = """(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"""

    // 15-11-2024, 15/11/24, 15.11.2024
    private val dmyNumeric = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b""")
    // 2024-11-15
    private val ymd = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    // 15-Nov-24, 15 Nov 2024, 15NOV24, 15th Nov
    private val dMonY = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s\-/]*$MON\.?[\s\-/,]*(\d{2,4})?\b""", RegexOption.IGNORE_CASE)
    // Nov 15, 2024
    private val monDY = Regex("""\b$MON\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s*(\d{4})?\b""", RegexOption.IGNORE_CASE)

    /**
     * Finds the first date in [text]. A date without a year is taken as the next such date on or
     * after [reference] (minus a week of slack, since a reminder can arrive after the date).
     */
    fun findDate(text: String, reference: LocalDate): LocalDate? {
        data class Hit(val start: Int, val date: LocalDate?)
        val hits = ArrayList<Hit>()
        ymd.find(text)?.let { m ->
            hits += Hit(m.range.first, safe(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()))
        }
        dmyNumeric.find(text)?.let { m ->
            hits += Hit(m.range.first, safe(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt()))
        }
        dMonY.find(text)?.let { m ->
            val month = months[m.groupValues[2].lowercase().take(3)]!!
            val day = m.groupValues[1].toInt()
            hits += Hit(m.range.first, withYear(m.groupValues[3], month, day, reference))
        }
        monDY.find(text)?.let { m ->
            val month = months[m.groupValues[1].lowercase().take(3)]!!
            val day = m.groupValues[2].toInt()
            hits += Hit(m.range.first, withYear(m.groupValues[3], month, day, reference))
        }
        return hits.filter { it.date != null }.minByOrNull { it.start }?.date
    }

    private fun year(s: String): Int = s.toInt().let { if (s.length <= 2) 2000 + it else it }

    private fun withYear(y: String, month: Month, day: Int, reference: LocalDate): LocalDate? {
        if (y.isNotEmpty()) return safe(year(y), month.value, day)
        val thisYear = safe(reference.year, month.value, day) ?: return null
        return if (thisYear.isBefore(reference.minusDays(7))) thisYear.plusYears(1) else thisYear
    }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? =
        if (y !in 2000..2100) null else runCatching { LocalDate.of(y, m, d) }.getOrNull()

    fun localDate(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun yearMonth(epochMillis: Long, zone: ZoneId): YearMonth = YearMonth.from(localDate(epochMillis, zone))

    /** Indian financial year (April to March) a date falls in, given by its starting calendar year. */
    fun financialYearStart(date: LocalDate): Int = if (date.monthValue >= 4) date.year else date.year - 1

    /** "FY 2024-25". */
    fun financialYearLabel(startYear: Int): String =
        "FY $startYear-${((startYear + 1) % 100).toString().padStart(2, '0')}"
}
