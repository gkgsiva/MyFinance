package com.myfinance.core

import java.time.YearMonth
import java.time.ZoneId

/** A month when the salary went up and stayed up. */
data class Raise(val month: YearMonth, val fromMinor: Long, val toMinor: Long) {
    val pct: Double get() = if (fromMinor <= 0) 0.0 else (toMinor - fromMinor) * 100.0 / fromMinor
}

/** A month with a one-off extra on top of the usual salary, or money labelled "Bonus". */
data class BonusMonth(val month: YearMonth, val extraMinor: Long, val labelled: Boolean)

/** One year: income and spending, and how both grew from the year before. */
data class GrowthYear(
    val label: String,
    val startYear: Int,
    val incomeMinor: Long,
    val spentMinor: Long,
    val salaryMinor: Long,
    val bonusMinor: Long,
    val incomeGrowthPct: Double?,
    val spendGrowthPct: Double?,
    /** Share of income kept, or null with no income. */
    val savedPct: Int?,
) {
    val savedMinor get() = incomeMinor - spentMinor
}

data class SalaryReport(
    val raises: List<Raise>,
    val bonuses: List<BonusMonth>,
    val years: List<GrowthYear>,
    /** Average yearly raise over the raises found, in percent. */
    val averageRaisePct: Double?,
    /** Typical monthly salary now (median of the last three salary months). */
    val currentMonthlyMinor: Long,
)

/** Salary breakdown: yearly raises, bonus months, and income against spending over the years. */
object SalaryBreakdown {

    private fun median(v: List<Long>): Long = if (v.isEmpty()) 0 else v.sorted()[v.size / 2]

    private fun pct(now: Long, before: Long?): Double? = if (before == null || before <= 0) null else (now - before) * 100.0 / before

    /** Bonus months: salary more than 40% above the usual level around it, or credits labelled Bonus. */
    fun bonuses(salary: List<Pair<YearMonth, Long>>, bonusLabelled: Map<YearMonth, Long> = emptyMap()): List<BonusMonth> {
        val out = HashMap<YearMonth, BonusMonth>()
        for ((i, p) in salary.withIndex()) {
            // The usual level: the months before (a raise right after must not inflate it), else the months after.
            val before = (maxOf(0, i - 3) until i).map { salary[it].second }
            val around = if (before.size >= 2) before else (maxOf(0, i - 3) until minOf(salary.size, i + 4)).filter { it != i }.map { salary[it].second }
            if (around.size < 2) continue
            val usual = median(around)
            if (usual > 0 && p.second * 10 > usual * 14) out[p.first] = BonusMonth(p.first, p.second - usual, false)
        }
        for ((m, v) in bonusLabelled) if (v > 0) {
            val prior = out[m]?.extraMinor ?: 0
            out[m] = BonusMonth(m, prior + v, true)
        }
        return out.values.sortedBy { it.month }
    }

    /**
     * Raises: a month whose salary is at least 3% above the usual level of the months before it, and the next
     * months stay at that level (so a bonus or arrears month does not count).
     */
    fun raises(salary: List<Pair<YearMonth, Long>>, bonusMonths: Set<YearMonth>): List<Raise> {
        val regular = salary.filter { it.first !in bonusMonths }
        val out = ArrayList<Raise>()
        var i = 2
        while (i < regular.size) {
            val before = median(regular.subList(maxOf(0, i - 3), i).map { it.second })
            val after = regular.subList(i, minOf(regular.size, i + 3)).map { it.second }
            val level = median(after)
            val stays = after.size >= 2 || i == regular.size - 1
            if (before > 0 && level * 100 >= before * 103 && regular[i].second * 100 >= before * 103 && stays &&
                after.count { it * 100 >= before * 102 } * 3 >= after.size * 2
            ) {
                out += Raise(regular[i].first, before, level)
                i += 3
                continue
            }
            i++
        }
        return out
    }

    fun report(
        txns: List<TxnLite>,
        labelsById: Map<Long, List<String>>,
        financialYear: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): SalaryReport {
        val ym = { t: TxnLite -> YearMonth.from(Dates.localDate(t.timestamp, zone)) }
        val isBonusLabel = { t: TxnLite -> labelsById[t.id].orEmpty().any { it.equals("Bonus", true) } }
        val salaryRows = txns.filter { it.type == TxnType.CREDIT && it.category == DefaultCategories.SALARY && !isBonusLabel(it) }
        val salary = salaryRows.groupBy(ym).mapValues { (_, v) -> v.sumOf { it.amountMinor } }.toSortedMap().toList()
        val bonusLabelled = txns.filter { it.type == TxnType.CREDIT && isBonusLabel(it) }
            .groupBy(ym).mapValues { (_, v) -> v.sumOf { it.amountMinor } }
        val bonuses = bonuses(salary, bonusLabelled)
        val raises = raises(salary, bonuses.filter { !it.labelled }.map { it.month }.toSet())

        fun yearOf(m: YearMonth) = if (financialYear) Dates.financialYearStart(m.atDay(1)) else m.year
        val years = txns.groupBy { yearOf(ym(it)) }.toSortedMap()
        val growth = ArrayList<GrowthYear>()
        var prev: GrowthYear? = null
        for ((y, list) in years) {
            val income = list.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor }
            val spent = list.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor }
            val sal = list.filter { it.type == TxnType.CREDIT && (it.category == DefaultCategories.SALARY || isBonusLabel(it)) }.sumOf { it.amountMinor }
            val bonus = bonuses.filter { yearOf(it.month) == y }.sumOf { it.extraMinor }
            val g = GrowthYear(
                label = if (financialYear) Dates.financialYearLabel(y) else y.toString(),
                startYear = y,
                incomeMinor = income,
                spentMinor = spent,
                salaryMinor = sal,
                bonusMinor = bonus,
                incomeGrowthPct = pct(income, prev?.incomeMinor),
                spendGrowthPct = pct(spent, prev?.spentMinor),
                savedPct = if (income > 0) ((income - spent) * 100 / income).toInt() else null,
            )
            growth += g
            prev = g
        }
        val avg = raises.map { it.pct }.takeIf { it.isNotEmpty() }?.average()
        val recent = salary.filter { m -> bonuses.none { it.month == m.first && !it.labelled } }.takeLast(3).map { it.second }
        return SalaryReport(raises, bonuses, growth, avg, median(recent))
    }
}
