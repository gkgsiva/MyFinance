package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One year of income: all money received, the salary part, and how the salary moved from the year before. */
data class IncomeYear(
    /** "2025" or "FY 2025-26". */
    val label: String,
    val startYear: Int,
    val incomeMinor: Long,
    val salaryMinor: Long,
    /** Months in which a salary arrived. */
    val salaryMonths: Int,
    /** Typical monthly salary: the median of the months it arrived in. */
    val monthlySalaryMinor: Long,
    /** Change in [monthlySalaryMinor] from the previous year, in percent; null for the first year. */
    val salaryChangePct: Double?,
    /** Change in [incomeMinor] from the previous year, in percent. */
    val incomeChangePct: Double?,
)

enum class Granularity(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year") }

/** One "did you know" line, e.g. "Vegetables: highest month ever". Higher [score] = more notable. */
data class Fact(val title: String, val detail: String, val score: Int, val kind: String)

/** A point in a spending trend. */
data class PeriodTotal(val label: String, val totalMinor: Long, val count: Int)

object Insights {

    private fun pct(now: Long, before: Long?): Double? =
        if (before == null || before == 0L) null else (now - before) * 100.0 / before

    /**
     * Income per calendar year, or per financial year (April to March) when [financialYear] is set. Excluded
     * rows should be filtered out by the caller.
     */
    fun incomeByYear(txns: List<TxnLite>, financialYear: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<IncomeYear> {
        val credits = txns.filter { it.type == TxnType.CREDIT }
        if (credits.isEmpty()) return emptyList()
        fun yearOf(d: LocalDate) = if (financialYear) Dates.financialYearStart(d) else d.year
        val byYear = credits.groupBy { yearOf(Dates.localDate(it.timestamp, zone)) }.toSortedMap()
        val out = ArrayList<IncomeYear>()
        var prev: IncomeYear? = null
        for ((year, list) in byYear) {
            val salary = list.filter { it.category == DefaultCategories.SALARY }
            val perMonth = salary.groupBy { YearMonth.from(Dates.localDate(it.timestamp, zone)) }
                .mapValues { (_, v) -> v.sumOf { it.amountMinor } }.values.sorted()
            val monthly = if (perMonth.isEmpty()) 0L else perMonth[perMonth.size / 2]
            val income = list.sumOf { it.amountMinor }
            val y = IncomeYear(
                label = if (financialYear) TaxHelper.fyLabel(year) else year.toString(),
                startYear = year,
                incomeMinor = income,
                salaryMinor = salary.sumOf { it.amountMinor },
                salaryMonths = perMonth.size,
                monthlySalaryMinor = monthly,
                salaryChangePct = pct(monthly, prev?.monthlySalaryMinor?.takeIf { it > 0 && monthly > 0 }),
                incomeChangePct = pct(income, prev?.incomeMinor),
            )
            out += y
            prev = y
        }
        return out
    }

    /** Monthly salary over time (one value per month that had a salary), oldest first. */
    fun salaryByMonth(txns: List<TxnLite>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<YearMonth, Long>> =
        txns.filter { it.type == TxnType.CREDIT && it.category == DefaultCategories.SALARY }
            .groupBy { YearMonth.from(Dates.localDate(it.timestamp, zone)) }
            .mapValues { (_, v) -> v.sumOf { it.amountMinor } }
            .toSortedMap().toList()

    /**
     * Totals of [txns] between [from] and [to] (inclusive), per week (Monday start), month or year, with empty
     * periods included so a trend shows gaps. Labels are "2025-W03"-style ISO dates of the week start, "2025-01"
     * or "2025".
     */
    fun trend(txns: List<TxnLite>, from: LocalDate, to: LocalDate, by: Granularity, zone: ZoneId = ZoneId.systemDefault()): List<PeriodTotal> {
        if (to.isBefore(from)) return emptyList()
        fun keyOf(d: LocalDate): LocalDate = when (by) {
            Granularity.WEEK -> d.minusDays((d.dayOfWeek.value - 1).toLong())
            Granularity.MONTH -> d.withDayOfMonth(1)
            Granularity.YEAR -> d.withDayOfYear(1)
        }
        fun next(d: LocalDate): LocalDate = when (by) {
            Granularity.WEEK -> d.plusWeeks(1)
            Granularity.MONTH -> d.plusMonths(1)
            Granularity.YEAR -> d.plusYears(1)
        }
        fun label(d: LocalDate): String = when (by) {
            Granularity.WEEK -> d.toString()
            Granularity.MONTH -> YearMonth.from(d).toString()
            Granularity.YEAR -> d.year.toString()
        }
        val g = txns.groupBy { keyOf(Dates.localDate(it.timestamp, zone)) }
        val out = ArrayList<PeriodTotal>()
        var k = keyOf(from)
        val end = keyOf(to)
        while (!k.isAfter(end) && out.size < 2000) {
            val list = g[k].orEmpty()
            out += PeriodTotal(label(k), list.sumOf { it.amountMinor }, list.size)
            k = next(k)
        }
        return out
    }

    /**
     * Short, interesting facts about [month]: record months for a category, label or payee, big jumps from last
     * month, the top category, the most-paid payee, the biggest payment and saving streaks. Most notable first.
     */
    fun facts(
        txns: List<TxnLite>,
        labelsById: Map<Long, List<String>>,
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = 8,
    ): List<Fact> {
        val out = ArrayList<Fact>()
        val debits = txns.filter { it.isSpend }
        val monthOf = { t: TxnLite -> YearMonth.from(Dates.localDate(t.timestamp, zone)) }
        val thisMonth = debits.filter { monthOf(it) == month }
        if (thisMonth.isEmpty()) return emptyList()
        val spent = thisMonth.sumOf { it.amountMinor }
        val minNotable = 50_000L // ₹500

        fun groupFacts(kind: String, keyOf: (TxnLite) -> List<String>) {
            val byKeyMonth = HashMap<String, HashMap<YearMonth, Long>>()
            for (t in debits) {
                val m = monthOf(t)
                if (m.isAfter(month)) continue
                for (k in keyOf(t)) byKeyMonth.getOrPut(k) { HashMap() }.merge(m, t.amountMinor, Long::plus)
            }
            for ((key, perMonth) in byKeyMonth) {
                val now = perMonth[month] ?: continue
                if (now < minNotable) continue
                val before = perMonth.filterKeys { it.isBefore(month) }
                if (before.size >= 3 && now > before.values.max()) {
                    out += Fact("$key: highest month ever", "${formatInr(now)} this month, beating ${formatInr(before.values.max())}", 90 + (now / 100_000).toInt().coerceAtMost(9), kind)
                } else {
                    val thisYear = before.filterKeys { it.year == month.year }
                    if (thisYear.size >= 2 && now > thisYear.values.max()) {
                        out += Fact("$key: highest this year", "${formatInr(now)} so far in ${month.month.name.lowercase().replaceFirstChar { it.uppercase() }}", 70, kind)
                    }
                }
                val last = perMonth[month.minusMonths(1)]
                if (last != null && last >= minNotable && now >= 2 * last && now - last >= 100_000) {
                    out += Fact("$key up ${((now - last) * 100 / last)}%", "${formatInr(now)} vs ${formatInr(last)} last month", 60, kind)
                }
            }
        }
        groupFacts("category") { listOf(it.category) }
        groupFacts("label") { labelsById[it.id].orEmpty() }
        groupFacts("payee") { listOfNotNull(it.merchant) }

        thisMonth.groupBy { it.category }.maxByOrNull { (_, v) -> v.sumOf { it.amountMinor } }?.let { (cat, v) ->
            val sum = v.sumOf { it.amountMinor }
            out += Fact("$cat tops this month", "${formatInr(sum)}, ${sum * 100 / spent.coerceAtLeast(1)}% of what you spent", 50, "category")
        }
        thisMonth.filter { it.merchant != null }.groupBy { it.merchant!! }.maxByOrNull { it.value.size }?.takeIf { it.value.size >= 4 }?.let { (m, v) ->
            out += Fact("Paid $m ${v.size} times", "${formatInr(v.sumOf { it.amountMinor })} in total this month", 45, "payee")
        }
        thisMonth.maxByOrNull { it.amountMinor }?.let { t ->
            out += Fact("Biggest payment: ${formatInr(t.amountMinor)}", "${t.merchant ?: t.category} on ${Dates.localDate(t.timestamp, zone).dayOfMonth} ${month.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}", 40, "payment")
        }
        // Saving streak: months in a row (ending last month) where income beat spending.
        val credits = txns.filter { it.type == TxnType.CREDIT }.groupBy(monthOf).mapValues { (_, v) -> v.sumOf { it.amountMinor } }
        val spends = debits.groupBy(monthOf).mapValues { (_, v) -> v.sumOf { it.amountMinor } }
        var streak = 0
        var m = month.minusMonths(1)
        while ((credits[m] ?: 0L) > (spends[m] ?: 0L) && (credits[m] ?: 0L) > 0) { streak++; m = m.minusMonths(1) }
        if (streak >= 3) out += Fact("Saved money $streak months in a row", "Income was more than spending every month since ${m.plusMonths(1).month.name.lowercase().replaceFirstChar { it.uppercase() }}", 55, "savings")

        return out.sortedByDescending { it.score }.distinctBy { it.title }.take(limit)
    }
}
