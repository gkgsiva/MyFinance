package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * A salary that usually arrives late in the month (the last week), so it should count for the next month.
 * [fromDay]: credits from this day on count for the next month. [payer] is who pays it; [alreadySalary] is false
 * when it was found as a steady credit from one payer that isn't in the Salary category yet (ask first).
 * [toCategorise]: credits that are the salary but not in the Salary category (same payer, similar amount).
 */
data class SalaryGuess(
    val fromDay: Int,
    val payer: String?,
    val ids: List<Long>,
    val months: Int,
    val alreadySalary: Boolean,
    val typicalDay: Int,
    val toCategorise: List<Long> = emptyList(),
    /** The latest salary credit, to show which one counts for this month. */
    val latest: TxnLite? = null,
)

object SalaryDetect {
    /** Late in the month: from the 20th. */
    private const val LATE = 20
    private const val MIN_AMOUNT = 10_000_00L

    fun guess(txns: List<TxnLite>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SalaryGuess? {
        val since = today.minusMonths(13)
        val allCredits = txns.filter { it.type == TxnType.CREDIT }
        // Finding the salary and its day looks at the last year; putting its credits in Salary looks at every
        // year, so old statements (imported years back) count their salary in the right month too.
        val credits = allCredits.filter { Dates.localDate(it.timestamp, zone).isAfter(since) }
        fun ym(t: TxnLite) = YearMonth.from(Dates.localDate(t.timestamp, zone))
        fun months(l: List<TxnLite>) = l.mapTo(HashSet()) { ym(it) }.size
        fun key(t: TxnLite) = payer(t.merchant)

        val salary = credits.filter(::isSalary)
        val rows: List<TxnLite>
        val already: Boolean
        if (months(salary) >= 2) {
            rows = salary + sameSalary(allCredits, zone)
            already = true
        } else {
            // No Salary category yet: the same payer crediting a steady, large amount in at least 3 months.
            rows = credits.filter { it.amountMinor >= MIN_AMOUNT && key(it) != null && it.category != DefaultCategories.TRANSFERS }
                .groupBy { key(it)!! }
                .values
                .filter { l -> months(l) >= 3 && l.maxOf { it.amountMinor } <= 2 * l.minOf { it.amountMinor } }
                .maxByOrNull { months(it) } ?: return null
            already = false
        }
        // The month's main payment: the biggest credit of each month.
        val main = rows.filter { Dates.localDate(it.timestamp, zone).isAfter(since) }
            .groupBy { YearMonth.from(Dates.localDate(it.timestamp, zone)) }.values.map { l -> l.maxBy { it.amountMinor } }
        val days = main.map { Dates.localDate(it.timestamp, zone).dayOfMonth }
        val late = days.filter { it >= LATE }
        // Mostly late in the month (paid early when the month end is a holiday is fine).
        if (late.isEmpty() || late.size * 2 < days.size) return null
        // A few days early is common (a weekend or holiday at month end), so start well before the usual day.
        val fromDay = (late.min() - 3).coerceIn(18, 25)
        return SalaryGuess(
            fromDay, rows.mapNotNull { it.merchant }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            rows.map { it.id }, months(rows.filter { Dates.localDate(it.timestamp, zone).isAfter(since) }), already, late.sorted()[late.size / 2],
            toCategorise = rows.filterNot(::isSalary).map { it.id },
            latest = rows.maxByOrNull { it.timestamp },
        )
    }

    /** Words that start a narration but say nothing about who paid. */
    private val genericWords = setOf("neft", "imps", "rtgs", "upi", "salary", "sal", "transfer", "payment", "credit", "by", "from", "the", "mr", "ms")

    /** Short names a payer is printed by, and the name they stand for. */
    private val aliases = mapOf("rntbci" to "renault", "rnaipl" to "renault", "rntbc" to "renault")

    /**
     * Who paid, as one word, so the same employer printed differently matches: "Renault", "RENAULT NISSAN TECH"
     * and "RNTBCI" are all "renault". Null when the name is only generic words.
     */
    fun payer(merchant: String?): String? {
        val words = merchant?.let { Categorizer.merchantKey(it) }?.split(' ')?.filter { it.isNotBlank() } ?: return null
        val w = words.firstOrNull { it !in genericWords && it.count(Char::isLetter) >= 3 } ?: return null
        return aliases[w] ?: aliases.entries.firstOrNull { w.startsWith(it.key) }?.value ?: w
    }

    private fun isSalary(t: TxnLite) = t.category.equals(DefaultCategories.SALARY, ignoreCase = true)

    /** Lines that are money between the user's own accounts or a card's bill, never a salary. */
    private val notSalary = Regex("""own account|credit card|card bill|card payment|refund|reversal|cashback|interest|dividend""", RegexOption.IGNORE_CASE)

    /**
     * Credits, in any year, that are the salary but not in the Salary category: they must be, or they stay in the
     * month they arrived while the other months' salary moves to the next month (one month gets two, the next none).
     *
     * - The salary payer's other credits of about the same amount (up to three times it: a bonus or arrears month).
     * - A month whose salary isn't in Salary at all, because the bank statement names the payer differently from
     *   the SMS ("NEFT-CITIN…-RENAULT NISSAN TECH" against "RNTBCI", or "SAL/Salary - Oct"): its credit into the
     *   salary account, of about the salary of the months around it, near the usual salary day.
     */
    fun sameSalary(credits: List<TxnLite>, zone: ZoneId = ZoneId.systemDefault()): List<TxnLite> {
        val salary = credits.filter(::isSalary)
        if (salary.size < 2) return emptyList()
        fun key(t: TxnLite) = payer(t.merchant)
        fun date(t: TxnLite) = Dates.localDate(t.timestamp, zone)
        fun tail(a: String?) = a?.filter(Char::isDigit)?.takeLast(3)?.takeIf { it.length == 3 }
        val payers = salary.mapNotNullTo(HashSet()) { key(it) }
        val accounts = salary.mapNotNullTo(HashSet()) { tail(it.account) }
        val usual = salary.map { it.amountMinor }.sorted()[salary.size / 2]
        val days = salary.filter { it.amountMinor * 2 >= usual }.map { date(it).dayOfMonth }.sorted()
        val usualDay = days[days.size / 2]
        val others = credits.filter { !isSalary(it) && it.merchant?.let { m -> notSalary.containsMatchIn(m) } != true }
        val out = LinkedHashSet<TxnLite>()
        others.filter { key(it) in payers && it.amountMinor * 10 >= usual * 6 && it.amountMinor <= usual * 3 }.forEach { out += it }

        // The salary the month should have: the nearest salary's amount, within a quarter either way.
        val sorted = salary.sortedBy { it.timestamp }
        fun nearest(t: TxnLite) = sorted.minBy { kotlin.math.abs(it.timestamp - t.timestamp) }
        // Which pay a credit is: one around the usual day; late in the month counts as the next month's pay.
        fun payMonth(t: TxnLite): YearMonth {
            val d = date(t)
            return if (usualDay >= 15 && d.dayOfMonth < usualDay - 15) YearMonth.from(d).minusMonths(1) else YearMonth.from(d)
        }
        fun nearDay(t: TxnLite): Boolean {
            val d = date(t)
            val target = payMonth(t).atDay(minOf(usualDay, payMonth(t).lengthOfMonth()))
            return kotlin.math.abs(d.toEpochDay() - target.toEpochDay()) <= 7
        }
        val paid = (salary + out).filter { it.amountMinor * 2 >= usual }.mapTo(HashSet()) { payMonth(it) }
        val first = sorted.first().timestamp - 40L * 24 * 60 * 60 * 1000
        others.asSequence()
            .filter { it !in out && it.timestamp >= first && it.amountMinor >= MIN_AMOUNT }
            .filter { accounts.isEmpty() || tail(it.account) == null || tail(it.account) in accounts }
            .filter { c -> val n = nearest(c).amountMinor; c.amountMinor * 4 >= n * 3 && c.amountMinor * 3 <= n * 4 }
            .filter(::nearDay)
            .filter { payMonth(it) !in paid }
            .groupBy(::payMonth)
            .forEach { (_, l) -> val n = nearest(l.first()).amountMinor; l.minBy { kotlin.math.abs(it.amountMinor - n) }.let { out += it } }
        return out.toList()
    }
}
