package com.myfinance.core

import java.time.LocalDate
import kotlin.math.roundToLong

data class ForecastDay(val date: LocalDate, val balanceMinor: Long, val inMinor: Long, val outMinor: Long, val items: List<String>)

data class BalanceForecast(
    val startMinor: Long,
    val days: List<ForecastDay>,
    /** Everyday spending assumed per day, from the last 90 days. */
    val dailySpendMinor: Long,
) {
    val low: ForecastDay? get() = days.minByOrNull { it.balanceMinor }
    val endMinor: Long get() = days.lastOrNull()?.balanceMinor ?: startMinor
}

/** One expected item on a day: positive comes in, negative goes out. */
data class ExpectedFlow(val date: LocalDate, val title: String, val signedMinor: Long)

object BalanceForecasts {
    private val fixedCategories = setOf(
        DefaultCategories.EMI, DefaultCategories.INVESTMENTS, DefaultCategories.RENT, DefaultCategories.TRANSFERS, DefaultCategories.CASH,
    )

    /**
     * Average everyday spending per day over the 90 days before [today], leaving out EMIs, investments, rent,
     * transfers and payments over [bigMinor] (those are expected separately or one-offs).
     */
    fun dailySpend(txns: List<TxnLite>, today: LocalDate, zone: java.time.ZoneId, bigMinor: Long = 2_500_000): Long {
        val from = today.minusDays(90)
        val list = txns.filter {
            val d = Dates.localDate(it.timestamp, zone)
            it.type == TxnType.DEBIT && d.isAfter(from) && !d.isAfter(today) && it.category !in fixedCategories && it.amountMinor < bigMinor
        }
        return (list.sumOf { it.amountMinor } / 90.0).roundToLong()
    }

    fun build(startMinor: Long, today: LocalDate, days: Int, expected: List<ExpectedFlow>, dailySpendMinor: Long): BalanceForecast {
        val byDate = expected.groupBy { it.date }
        var bal = startMinor
        val out = (0 until days).map { i ->
            val d = today.plusDays(i.toLong())
            val items = byDate[d].orEmpty()
            val inn = items.filter { it.signedMinor > 0 }.sumOf { it.signedMinor }
            val outs = -items.filter { it.signedMinor < 0 }.sumOf { it.signedMinor } + dailySpendMinor
            bal += inn - outs
            ForecastDay(d, bal, inn, outs, items.map { it.title })
        }
        return BalanceForecast(startMinor, out, dailySpendMinor)
    }
}

enum class PaceState(val label: String) { ON_TRACK("On track"), AHEAD("Spending fast"), OVER("Over budget") }

data class SpendPace(
    val spentMinor: Long,
    val budgetMinor: Long,
    val day: Int,
    val daysInPeriod: Int,
) {
    val spentPercent: Int get() = if (budgetMinor <= 0) 0 else ((spentMinor * 100) / budgetMinor).toInt()
    val elapsedPercent: Int get() = (day * 100) / daysInPeriod.coerceAtLeast(1)
    /** At this pace, spending by the end of the period. */
    val projectedMinor: Long get() = if (day <= 0) spentMinor else spentMinor * daysInPeriod / day
    val state: PaceState
        get() = when {
            spentMinor > budgetMinor -> PaceState.OVER
            spentPercent >= elapsedPercent + SpendPaces.AHEAD_BY && spentPercent >= 30 -> PaceState.AHEAD
            else -> PaceState.ON_TRACK
        }
}

object SpendPaces {
    /** Spending this many points ahead of the calendar counts as too fast: 70% spent by day 15 of 30 (50%). */
    const val AHEAD_BY = 20

    fun of(spentMinor: Long, budgetMinor: Long, today: LocalDate, start: LocalDate, end: LocalDate): SpendPace {
        val total = (java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1).toInt()
        val day = (java.time.temporal.ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceIn(1, total)
        return SpendPace(spentMinor, budgetMinor, day, total)
    }
}

/** A payment due once a year (or rarer), with how much to set aside each month until it is due. */
data class AnnualDue(val title: String, val date: LocalDate, val amountMinor: Long?, val source: String, val ref: String?) {
    fun monthlySetAside(today: LocalDate): Long? = amountMinor?.let { a ->
        val months = java.time.temporal.ChronoUnit.MONTHS.between(today.withDayOfMonth(1), date.withDayOfMonth(1)).coerceAtLeast(1)
        a / months
    }
}

object AnnualDues {
    /** Yearly-or-rarer payment reminders and insurance renewals in the next 12 months, soonest first. */
    fun list(bills: List<BillReminder>, policies: List<Policy>, today: LocalDate): List<AnnualDue> {
        val until = today.plusMonths(12)
        val out = ArrayList<AnnualDue>()
        bills.filter { !it.finished && (it.everyMonths >= 12 || (it.oneTime && it.kind.usuallyYearly)) }.forEach { b ->
            BillReminders.dueDates(b, today, until).firstOrNull()?.let { out += AnnualDue(b.name, it, b.amountMinor, "Reminder", "bill:${b.id}") }
        }
        policies.filter { it.everyMonths >= 12 || it.everyMonths == 0 }.forEach { p ->
            var r = p.renewalDate ?: return@forEach
            while (r.isBefore(today) && p.everyMonths > 0) r = r.plusMonths(p.everyMonths.toLong())
            if (!r.isBefore(today) && !r.isAfter(until)) out += AnnualDue("${p.name} renewal", r, p.premiumMinor, "Insurance", null)
        }
        return out.sortedBy { it.date }
    }
}
