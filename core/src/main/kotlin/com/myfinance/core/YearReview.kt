package com.myfinance.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class ReviewLine(val name: String, val amountMinor: Long, val count: Int)

/** A year (calendar or financial) in one page: totals, top sellers, record months and fun facts. */
data class YearInReview(
    val label: String,
    val startYear: Int,
    val incomeMinor: Long,
    val spentMinor: Long,
    val payments: Int,
    val topCategories: List<ReviewLine>,
    val topSellers: List<ReviewLine>,
    val mostVisited: List<ReviewLine>,
    val topLabels: List<ReviewLine>,
    /** Highest-spend month and its amount. */
    val biggestMonth: Pair<YearMonth, Long>?,
    val smallestMonth: Pair<YearMonth, Long>?,
    val bestSavingMonth: Pair<YearMonth, Long>?,
    val biggestPayment: TxnLite?,
    val facts: List<String>,
    val incomeChangePct: Double?,
    val spendChangePct: Double?,
    /** Money put into investments in the year; not part of [spentMinor], counted as saved. */
    val investedMinor: Long = 0,
) {
    val savedMinor get() = incomeMinor - spentMinor
    val savedPct: Int? get() = if (incomeMinor > 0) (savedMinor * 100 / incomeMinor).toInt() else null
}

object YearReview {

    private fun monthName(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + m.year

    private fun pct(now: Long, before: Long): Double? = if (before <= 0) null else (now - before) * 100.0 / before

    /** Years with any transactions, newest first. */
    fun years(txns: List<TxnLite>, financialYear: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<Int> =
        txns.map { Dates.localDate(it.timestamp, zone).let { d -> if (financialYear) Dates.financialYearStart(d) else d.year } }
            .distinct().sortedDescending()

    fun build(
        txns: List<TxnLite>,
        labelsById: Map<Long, List<String>>,
        startYear: Int,
        financialYear: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): YearInReview {
        val from = if (financialYear) LocalDate.of(startYear, 4, 1) else LocalDate.of(startYear, 1, 1)
        val to = from.plusYears(1)
        fun inRange(t: TxnLite, a: LocalDate, b: LocalDate) = Dates.localDate(t.timestamp, zone).let { !it.isBefore(a) && it.isBefore(b) }
        val year = txns.filter { inRange(it, from, to) }
        val prevYear = txns.filter { inRange(it, from.minusYears(1), from) }
        val debits = year.filter { it.isSpend }
        val credits = year.filter { it.type == TxnType.CREDIT }
        val spent = debits.sumOf { it.amountMinor }
        val income = credits.sumOf { it.amountMinor }

        fun lines(key: (TxnLite) -> List<String>, byCount: Boolean = false, n: Int = 5): List<ReviewLine> {
            val acc = HashMap<String, LongArray>()
            for (t in debits) for (k in key(t).distinct()) acc.getOrPut(k) { LongArray(2) }.let { it[0] += t.amountMinor; it[1]++ }
            return acc.map { (k, v) -> ReviewLine(k, v[0], v[1].toInt()) }
                .sortedWith(if (byCount) compareByDescending<ReviewLine> { it.count }.thenByDescending { it.amountMinor } else compareByDescending { it.amountMinor })
                .take(n)
        }
        val ym = { t: TxnLite -> YearMonth.from(Dates.localDate(t.timestamp, zone)) }
        val spendByMonth = debits.groupBy(ym).mapValues { (_, v) -> v.sumOf { it.amountMinor } }
        val incomeByMonth = credits.groupBy(ym).mapValues { (_, v) -> v.sumOf { it.amountMinor } }
        val months = (spendByMonth.keys + incomeByMonth.keys).distinct()
        val saving = months.associateWith { (incomeByMonth[it] ?: 0) - (spendByMonth[it] ?: 0) }

        val sellerKey = { t: TxnLite -> listOfNotNull(t.merchant?.takeIf { it.isNotBlank() }) }
        val topSellers = lines(sellerKey)
        val mostVisited = lines(sellerKey, byCount = true)
        val facts = ArrayList<String>()
        if (debits.isNotEmpty()) {
            val days = java.time.temporal.ChronoUnit.DAYS.between(from, minOf(to, LocalDate.now().plusDays(1))).coerceAtLeast(1)
            facts += "You spent about ${formatInr(spent / days / 100 * 100)} a day."
            mostVisited.firstOrNull()?.takeIf { it.count >= 6 }?.let {
                facts += "You paid ${it.name} ${it.count} times, about once every ${(days / it.count).coerceAtLeast(1)} days."
            }
            val dow = debits.groupBy { Dates.localDate(it.timestamp, zone).dayOfWeek }.mapValues { (_, v) -> v.sumOf { it.amountMinor } }
            dow.maxByOrNull { it.value }?.let { (d, _) -> facts += "${d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} was your biggest spending day of the week." }
            val weekend = debits.count { Dates.localDate(it.timestamp, zone).dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
            if (debits.size >= 20) facts += "${weekend * 100 / debits.size}% of your payments were on weekends."
            val small = debits.count { it.amountMinor < 200_00 }
            if (small >= 20) facts += "$small payments were under ₹200, adding up to ${formatInr(debits.filter { it.amountMinor < 200_00 }.sumOf { it.amountMinor })}."
            val sellers = debits.mapNotNull { it.merchant }.distinct().size
            if (sellers >= 10) facts += "You paid $sellers different people and shops."
            val streak = saving.toSortedMap().values.fold(0 to 0) { (cur, best), v -> if (v > 0) (cur + 1) to maxOf(best, cur + 1) else 0 to best }.second
            if (streak >= 3) facts += "You saved money $streak months in a row."
            val refunds = credits.filter { c -> c.category == DefaultCategories.INCOME && c.merchant != null && Regex("(?i)refund|reversal|cashback").containsMatchIn(c.merchant) }
            if (refunds.isNotEmpty()) facts += "${refunds.size} refunds and cashbacks brought back ${formatInr(refunds.sumOf { it.amountMinor })}."
        }
        val prevSpent = prevYear.filter { it.isSpend }.sumOf { it.amountMinor }
        val prevIncome = prevYear.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor }
        return YearInReview(
            label = if (financialYear) Dates.financialYearLabel(startYear) else startYear.toString(),
            startYear = startYear,
            incomeMinor = income,
            spentMinor = spent,
            payments = debits.size,
            topCategories = lines({ listOf(it.category) }),
            topSellers = topSellers,
            mostVisited = mostVisited,
            topLabels = lines({ labelsById[it.id].orEmpty() }),
            biggestMonth = spendByMonth.maxByOrNull { it.value }?.toPair(),
            smallestMonth = spendByMonth.takeIf { it.size >= 3 }?.minByOrNull { it.value }?.toPair(),
            bestSavingMonth = saving.filterValues { it > 0 }.maxByOrNull { it.value }?.toPair(),
            biggestPayment = debits.maxByOrNull { it.amountMinor },
            facts = facts,
            incomeChangePct = pct(income, prevIncome),
            spendChangePct = pct(spent, prevSpent),
            investedMinor = year.filter { it.isInvestment }.sumOf { it.amountMinor },
        )
    }

    /** The review as a WhatsApp message (WhatsApp shows *bold*). */
    fun shareText(r: YearInReview, owner: String?): String = buildString {
        val who = owner?.takeIf { it.isNotBlank() && it != "Me" }?.let { "$it's " }.orEmpty()
        appendLine("*${who}${r.label} in review* (MyFinance)")
        appendLine()
        appendLine("💰 Income: ${formatInr(r.incomeMinor)}" + (r.incomeChangePct?.let { " (${sign(it)} on last year)" } ?: ""))
        appendLine("🛒 Spent: ${formatInr(r.spentMinor)} in ${r.payments} payments" + (r.spendChangePct?.let { " (${sign(it)})" } ?: ""))
        if (r.investedMinor > 0) appendLine("📈 Invested: ${formatInr(r.investedMinor)}")
        r.savedPct?.let { appendLine("🏦 Saved: ${formatInr(r.savedMinor)} ($it% of income)") }
        if (r.topCategories.isNotEmpty()) {
            appendLine()
            appendLine("*Where it went*")
            r.topCategories.take(3).forEachIndexed { i, l -> appendLine("${i + 1}. ${l.name}: ${formatInr(l.amountMinor)}") }
        }
        if (r.topSellers.isNotEmpty()) {
            appendLine()
            appendLine("*Top sellers*")
            r.topSellers.take(3).forEachIndexed { i, l -> appendLine("${i + 1}. ${l.name}: ${formatInr(l.amountMinor)} (${l.count}×)") }
        }
        val records = buildList {
            r.biggestMonth?.let { add("Biggest month: ${monthName(it.first)}, ${formatInr(it.second)}") }
            r.bestSavingMonth?.let { add("Best saving month: ${monthName(it.first)}, ${formatInr(it.second)} saved") }
            r.biggestPayment?.let { add("Biggest payment: ${formatInr(it.amountMinor)} to ${it.merchant ?: it.category}") }
        }
        if (records.isNotEmpty()) {
            appendLine()
            appendLine("*Records*")
            records.forEach { appendLine("🏆 $it") }
        }
        if (r.facts.isNotEmpty()) {
            appendLine()
            appendLine("*Fun facts*")
            r.facts.take(5).forEach { appendLine("✨ $it") }
        }
    }.trimEnd()

    private fun sign(p: Double) = (if (p >= 0) "up " else "down ") + "%.0f%%".format(kotlin.math.abs(p))
}
