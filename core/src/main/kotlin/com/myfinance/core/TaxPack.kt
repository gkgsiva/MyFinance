package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/*
 * Tax-time pack: one financial year's numbers gathered for ITR filing. It collects and totals what
 * the app knows; it does not work out the tax. Rules used are the old-regime deduction limits from
 * TaxHelper and the holding periods for capital gains in force from 23 July 2024.
 */

enum class InterestKind(val label: String) { SAVINGS("Savings account interest"), DEPOSIT("FD / RD interest"), OTHER("Other interest") }

data class InterestLine(val txn: TxnLite, val kind: InterestKind)

enum class GainAsset(val label: String, val longTermAfterMonths: Int?) {
    EQUITY("Listed shares / equity funds", 12),
    DEBT_FUND("Debt funds (bought after Mar 2023)", null),
    GOLD("Gold / gold funds", 24),
    PROPERTY("Property / land", 24),
    OTHER("Other", 24),
}

/** A sale the user enters: what was bought, when, and what it sold for. */
data class CapitalGainEntry(
    val id: String,
    val name: String,
    val asset: GainAsset,
    val buyDate: LocalDate,
    val buyMinor: Long,
    val sellDate: LocalDate,
    val sellMinor: Long,
)

data class GainLine(val entry: CapitalGainEntry, val longTerm: Boolean, val gainMinor: Long, val heldMonths: Long)

data class GainTotals(
    val equityShortMinor: Long,
    val equityLongMinor: Long,
    val otherShortMinor: Long,
    val otherLongMinor: Long,
) {
    val totalMinor get() = equityShortMinor + equityLongMinor + otherShortMinor + otherLongMinor
}

data class TaxPackData(
    val fyStart: Int,
    val salaryMinor: Long,
    val salaryCredits: Int,
    val otherIncomeMinor: Long,
    val interest: List<InterestLine>,
    val savingsInterestMinor: Long,
    val depositInterestMinor: Long,
    val otherInterestMinor: Long,
    /** 80TTA: savings interest, up to ₹10,000. */
    val tta80Minor: Long,
    val deductions: List<SectionTotal>,
    val deductionTotalMinor: Long,
    val gains: List<GainLine>,
    val gainTotals: GainTotals,
    val spendMinor: Long,
) {
    val label get() = Dates.financialYearLabel(fyStart)
}

object TaxPack {
    /** LTCG on listed equity is tax free up to this much a year (from FY 2024-25). */
    const val EQUITY_LTCG_EXEMPT_MINOR = 1_25_000_00L

    private val interestWords = Regex(
        """\b(int\.?\s?(pd|paid|cr|credit|credited|amt)|interest|intt?\.?\s?on|sb\s?int|fd\s?int|td\s?int|rd\s?int|int\s?payout)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val depositWords = Regex("""\b(fd|td|rd|term deposit|fixed deposit|recurring deposit|deposit)\b""", RegexOption.IGNORE_CASE)
    // A loan or card "interest" debit is not income; refunds of interest are rare and left out.
    private val notIncome = Regex("""\b(loan|emi|card|penal|overdue)\b""", RegexOption.IGNORE_CASE)

    /** Tells whether a credit is interest income, and which kind. [text] is the SMS or statement line. */
    fun interestKind(t: TxnLite, text: String?): InterestKind? {
        if (t.type != TxnType.CREDIT) return null
        val h = listOfNotNull(t.merchant, text, t.category).joinToString(" ")
        if (!interestWords.containsMatchIn(h)) return null
        if (notIncome.containsMatchIn(h)) return null
        return when {
            depositWords.containsMatchIn(h) -> InterestKind.DEPOSIT
            Regex("""\b(bond|nsc|kvp|scss|debenture|ncd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(h) -> InterestKind.OTHER
            else -> InterestKind.SAVINGS
        }
    }

    fun gain(e: CapitalGainEntry): GainLine {
        val months = ChronoUnit.MONTHS.between(e.buyDate, e.sellDate)
        val longAfter = e.asset.longTermAfterMonths
        // "More than 12 months": held 12 full months and at least a day more.
        val long = longAfter != null && (months > longAfter || (months == longAfter.toLong() && e.sellDate.isAfter(e.buyDate.plusMonths(longAfter.toLong()))))
        return GainLine(e, long, e.sellMinor - e.buyMinor, months)
    }

    fun build(
        fyStart: Int,
        txns: List<TxnLite>,
        /** SMS or statement text for each transaction id, used to spot interest credits. */
        textById: Map<Long, String?>,
        taxItems: List<TaxItem>,
        gains: List<CapitalGainEntry>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TaxPackData {
        val from = LocalDate.of(fyStart, 4, 1)
        val to = LocalDate.of(fyStart + 1, 3, 31)
        val inYear = txns.filter { val d = Dates.localDate(it.timestamp, zone); !d.isBefore(from) && !d.isAfter(to) }
        val credits = inYear.filter { it.type == TxnType.CREDIT && it.category != DefaultCategories.TRANSFERS }
        val salary = credits.filter { it.category == DefaultCategories.SALARY }
        val interest = credits.mapNotNull { t -> interestKind(t, textById[t.id])?.let { InterestLine(t, it) } }
        val interestIds = interest.map { it.txn.id }.toSet()
        val other = credits.filter { it.category != DefaultCategories.SALARY && it.id !in interestIds }
        fun sum(k: InterestKind) = interest.filter { it.kind == k }.sumOf { it.txn.amountMinor }
        val savings = sum(InterestKind.SAVINGS)

        val deductions = TaxHelper.yearTotals(taxItems, fyStart)
        val gainLines = gains.filter { !it.sellDate.isBefore(from) && !it.sellDate.isAfter(to) }.map(::gain).sortedBy { it.entry.sellDate }
        fun g(equity: Boolean, long: Boolean) = gainLines.filter { (it.entry.asset == GainAsset.EQUITY) == equity && it.longTerm == long }.sumOf { it.gainMinor }

        return TaxPackData(
            fyStart = fyStart,
            salaryMinor = salary.sumOf { it.amountMinor },
            salaryCredits = salary.size,
            otherIncomeMinor = other.sumOf { it.amountMinor },
            interest = interest.sortedBy { it.txn.timestamp },
            savingsInterestMinor = savings,
            depositInterestMinor = sum(InterestKind.DEPOSIT),
            otherInterestMinor = sum(InterestKind.OTHER),
            tta80Minor = minOf(savings, TaxSection.S80TTA.limitMinor ?: savings),
            deductions = deductions,
            deductionTotalMinor = TaxHelper.totalDeduction(deductions),
            gains = gainLines,
            gainTotals = GainTotals(g(true, false), g(true, true), g(false, false), g(false, true)),
            spendMinor = inYear.filter { it.isSpend }.sumOf { it.amountMinor },
        )
    }

    private fun rupees(minor: Long) = java.math.BigDecimal.valueOf(minor, 2)
    private val dmy = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    /** The pack as Excel sheets: Summary, Deductions, Interest, Capital gains. */
    fun sheets(p: TaxPackData, zone: ZoneId = ZoneId.systemDefault()): List<XlsxSheet> {
        val summary = XlsxSheet(
            "Summary", listOf("Item", "Amount (₹)", "Notes"),
            listOf(
                listOf("Financial year", p.label, "1 Apr ${p.fyStart} to 31 Mar ${p.fyStart + 1}"),
                listOf("Salary received (in bank)", rupees(p.salaryMinor), "${p.salaryCredits} credits. Take gross salary and TDS from Form 16."),
                listOf("Savings interest", rupees(p.savingsInterestMinor), "Income from other sources"),
                listOf("FD / RD interest", rupees(p.depositInterestMinor), "Income from other sources. Check TDS in Form 26AS / AIS."),
                listOf("Other interest", rupees(p.otherInterestMinor), null),
                listOf("Other money received", rupees(p.otherIncomeMinor), "Refunds, gifts and transfers in; most are not income"),
                listOf("Short-term gains, equity", rupees(p.gainTotals.equityShortMinor), "Section 111A"),
                listOf("Long-term gains, equity", rupees(p.gainTotals.equityLongMinor), "Section 112A, first ₹1.25 lakh a year exempt"),
                listOf("Short-term gains, other", rupees(p.gainTotals.otherShortMinor), "Taxed at slab rate"),
                listOf("Long-term gains, other", rupees(p.gainTotals.otherLongMinor), "Section 112"),
                listOf("Deductions counted (old regime)", rupees(p.deductionTotalMinor), "Each section capped at its limit"),
                listOf("80TTA (savings interest)", rupees(p.tta80Minor), "Up to ₹10,000; 80TTB for seniors"),
                listOf("Total spending in the year", rupees(p.spendMinor), null),
            ),
        )
        val deductions = XlsxSheet(
            "Deductions", listOf("Section", "Paid (₹)", "Counted (₹)", "Room left (₹)"),
            p.deductions.map { listOf(it.section.label, rupees(it.claimedMinor), rupees(it.countedMinor), it.remainingMinor?.let(::rupees)) },
        )
        val interest = XlsxSheet(
            "Interest", listOf("Date", "Kind", "Amount (₹)", "From"),
            p.interest.map {
                listOf(Dates.localDate(it.txn.timestamp, zone).format(dmy), it.kind.label, rupees(it.txn.amountMinor), it.txn.merchant ?: it.txn.account)
            },
        )
        val gains = XlsxSheet(
            "Capital gains", listOf("Name", "Kind", "Bought on", "Cost (₹)", "Sold on", "Sale value (₹)", "Gain (₹)", "Term", "Months held"),
            p.gains.map {
                listOf(
                    it.entry.name, it.entry.asset.label, it.entry.buyDate.format(dmy), rupees(it.entry.buyMinor),
                    it.entry.sellDate.format(dmy), rupees(it.entry.sellMinor), rupees(it.gainMinor),
                    if (it.longTerm) "Long term" else "Short term", it.heldMonths,
                )
            },
        )
        return listOf(summary, deductions, interest, gains)
    }

    /** A short text version for WhatsApp or email to a CA. */
    fun summaryText(p: TaxPackData): String = buildString {
        appendLine("MyFinance tax-time pack, ${p.label}")
        appendLine("Salary received: ${formatInr(p.salaryMinor)}")
        appendLine("Savings interest: ${formatInr(p.savingsInterestMinor)}")
        appendLine("FD / RD interest: ${formatInr(p.depositInterestMinor)}")
        if (p.gains.isNotEmpty()) {
            appendLine("Equity gains: short ${formatInr(p.gainTotals.equityShortMinor)}, long ${formatInr(p.gainTotals.equityLongMinor)}")
            appendLine("Other gains: short ${formatInr(p.gainTotals.otherShortMinor)}, long ${formatInr(p.gainTotals.otherLongMinor)}")
        }
        appendLine("Deductions (old regime):")
        p.deductions.forEach { appendLine("  ${it.section.label.substringBefore(':')}: ${formatInr(it.countedMinor)}") }
        append("Total deductions: ${formatInr(p.deductionTotalMinor)}")
    }
}
