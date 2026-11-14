package com.myfinance.core

import java.time.YearMonth
import java.time.ZoneId

/**
 * The year-end household report: twelve monthly summaries for a financial year (April to March) or a
 * calendar year, the year's income and expenditure, investments, family members, EMIs, the tax-relevant
 * items (from [TaxPack]) and the index of attached receipts. Built from one [MonthReport] per month, so the
 * rules (investments are "Invested", own transfers and card bill payments left out) are the same.
 */
data class YearReport(
    val label: String,
    val financialYear: Boolean,
    val firstMonth: YearMonth,
    val months: List<MonthLine>,
    val incomeCategories: List<MonthReport.Share>,
    val categories: List<MonthReport.Share>,
    val investments: List<MonthReport.Share>,
    val personLines: List<MonthReport.PersonLine>,
    val emis: List<MonthReport.Share>,
    val biggest: List<MonthReport.Row>,
    /** Every entry with a receipt or file attached in the year. */
    val attachments: List<MonthReport.Row>,
    /** Bank months that don't reconcile, with what looks wrong. */
    val unreconciled: List<Pair<YearMonth, MonthReport.BalanceLine>>,
    val tax: TaxPackData?,
    val insights: List<String>,
) {
    val incomeMinor: Long get() = months.sumOf { it.incomeMinor }
    val spentMinor: Long get() = months.sumOf { it.spentMinor }
    val investedMinor: Long get() = months.sumOf { it.investedMinor }
    val cardSpentMinor: Long get() = months.sumOf { it.cardSpentMinor }
    val leftMinor: Long get() = incomeMinor - spentMinor - investedMinor
    val entries: Int get() = months.sumOf { it.entries }
    val savingsRatePercent: Int? get() = if (incomeMinor <= 0) null else (((incomeMinor - spentMinor) * 100) / incomeMinor).toInt()

    data class MonthLine(
        val month: YearMonth,
        val incomeMinor: Long,
        val spentMinor: Long,
        val investedMinor: Long,
        val cardSpentMinor: Long,
        val entries: Int,
        val topCategory: String?,
        /** null: no balances to check; true: every bank account matches. */
        val reconciled: Boolean?,
    ) {
        val leftMinor: Long get() = incomeMinor - spentMinor - investedMinor
    }

    companion object {
        /** The twelve budget months of the year that starts in [firstMonth]. */
        fun monthsOf(firstMonth: YearMonth): List<YearMonth> = (0L until 12L).map { firstMonth.plusMonths(it) }

        /**
         * @param startYear the financial year's first year (2025 = FY 2025-26) or the calendar year.
         * @param monthReport makes one month's report with the same inputs as the monthly PDF.
         */
        fun build(
            startYear: Int,
            financialYear: Boolean,
            monthReport: (YearMonth) -> MonthReport,
            tax: TaxPackData? = null,
        ): YearReport {
            val first = if (financialYear) YearMonth.of(startYear, 4) else YearMonth.of(startYear, 1)
            val reports = monthsOf(first).map(monthReport)
            val lines = reports.map { r ->
                MonthLine(
                    r.period, r.incomeMinor, r.spentMinor, r.investedMinor, r.cardSpentMinor, r.rows.size,
                    r.categories.firstOrNull()?.key,
                    if (r.balances.isEmpty()) null else r.balances.all { it.differenceMinor == 0L },
                )
            }
            val rows = reports.flatMap { it.rows }
            val spends = rows.filter { it.txn.isSpend }
            fun shares(list: List<MonthReport.Row>, key: (MonthReport.Row) -> String?): List<MonthReport.Share> {
                val total = list.sumOf { it.txn.amountMinor }
                return list.groupBy { key(it)?.takeIf { k -> k.isNotBlank() } ?: "Not set" }
                    .map { (k, v) ->
                        val s = v.sumOf { it.txn.amountMinor }
                        MonthReport.Share(k, s, v.size, if (total > 0) ((s * 100 + total / 2) / total).toInt() else 0)
                    }
                    .sortedByDescending { it.totalMinor }
            }
            val personLines = rows.groupBy { it.txn.owner?.takeIf { o -> o.isNotBlank() } ?: "Not set" }
                .map { (p, l) ->
                    MonthReport.PersonLine(
                        p,
                        l.filter { it.isCredit }.sumOf { it.txn.amountMinor },
                        l.filter { it.txn.isSpend }.sumOf { it.txn.amountMinor },
                        l.filter { it.txn.isInvestment }.sumOf { it.txn.amountMinor },
                        l.size,
                    )
                }
                .sortedByDescending { it.spentMinor + it.receivedMinor }
            val label = if (financialYear) Dates.financialYearLabel(startYear) else "$startYear"
            val categories = shares(spends) { it.txn.category }
            val invest = shares(rows.filter { it.txn.isInvestment }) { it.txn.merchant ?: it.txn.category }
            return YearReport(
                label = label,
                financialYear = financialYear,
                firstMonth = first,
                months = lines,
                incomeCategories = shares(rows.filter { it.isCredit }) { it.txn.category },
                categories = categories,
                investments = invest,
                personLines = personLines,
                emis = shares(spends.filter { it.txn.category == DefaultCategories.EMI }) { it.txn.merchant ?: it.txn.category },
                biggest = spends.sortedByDescending { it.txn.amountMinor }.take(10),
                attachments = reports.flatMap { it.attachmentIndex },
                unreconciled = reports.flatMap { r -> r.balances.filter { it.differenceMinor != 0L }.map { r.period to it } },
                tax = tax,
                insights = insights(lines, categories, invest),
            )
        }

        private val mon = java.time.format.DateTimeFormatter.ofPattern("MMM yyyy")

        private fun insights(lines: List<MonthLine>, categories: List<MonthReport.Share>, invest: List<MonthReport.Share>): List<String> {
            val used = lines.filter { it.entries > 0 }
            if (used.isEmpty()) return listOf("Nothing was recorded this year.")
            val out = ArrayList<String>()
            val income = used.sumOf { it.incomeMinor }
            val spent = used.sumOf { it.spentMinor }
            val invested = used.sumOf { it.investedMinor }
            out += "Received ${formatInr(income)}, spent ${formatInr(spent)} and invested ${formatInr(invested)} over ${used.size} months with entries."
            if (income > 0) out += "Kept ${((income - spent) * 100 / income)}% of the money received after spending."
            out += "Average spending per month: ${formatInr(spent / used.size)}."
            used.maxByOrNull { it.spentMinor }?.let { out += "Highest spending: ${it.month.format(mon)}, ${formatInr(it.spentMinor)}." }
            used.minByOrNull { it.spentMinor }?.takeIf { used.size > 1 }?.let { out += "Lowest spending: ${it.month.format(mon)}, ${formatInr(it.spentMinor)}." }
            categories.take(3).takeIf { it.isNotEmpty() }?.let { top ->
                out += "Biggest categories: " + top.joinToString(", ") { "${it.key} ${formatInr(it.totalMinor)} (${it.percent}%)" } + "."
            }
            invest.firstOrNull()?.let { out += "Most invested in ${it.key}: ${formatInr(it.totalMinor)}." }
            val deficit = used.filter { it.leftMinor < 0 }
            if (deficit.isNotEmpty()) out += "${deficit.size} month${if (deficit.size == 1) "" else "s"} spent and invested more than came in: " +
                deficit.joinToString(", ") { it.month.format(java.time.format.DateTimeFormatter.ofPattern("MMM")) } + "."
            val empty = lines.size - used.size
            if (empty > 0) out += "$empty month${if (empty == 1) " has" else "s have"} no entries; import those statements for a complete year."
            return out
        }
    }
}
