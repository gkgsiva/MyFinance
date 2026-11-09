package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Goal-linked savings: a SIP, FD, holding or label tied to a goal. Linked money counts towards
 * the goal, and the pace of contributions gives the month the goal will be reached.
 */

enum class GoalLinkKind(val label: String) {
    /** An investment from SMS, by name (SIP, mutual fund, FD, RD). Contributions since the link count. */
    INVESTMENT("SIP / FD from SMS"),
    /** A holding typed into Net worth: its whole current value counts. */
    HOLDING("Holding in Net worth"),
    /** Payments with this label (e.g. "Vacation fund") count as saved for the goal. */
    LABEL("Payments with a label"),
}

data class GoalLink(
    val id: String,
    val goalId: String,
    val kind: GoalLinkKind,
    /** Investment name, holding name or label. */
    val ref: String,
    val since: LocalDate,
    /** Planned monthly amount, when the user knows it (e.g. SIP of ₹5,000). */
    val monthlyMinor: Long? = null,
)

data class LinkedAmount(val link: GoalLink, val savedMinor: Long, val monthlyMinor: Long)

data class GoalForecast(
    val goal: SavingsGoal,
    /** Typed-in savings plus linked savings. */
    val totalSavedMinor: Long,
    val linked: List<LinkedAmount>,
    val monthlyMinor: Long,
    /** Month the target is reached at this pace; null when nothing is going in. */
    val reachBy: YearMonth?,
    /** Months later (+) or earlier (-) than the deadline; null without a deadline or forecast. */
    val monthsVsDeadline: Long?,
    val percent: Int,
)

/** One money movement that may belong to a link. */
data class GoalMoney(val name: String, val amountMinor: Long, val date: LocalDate, val redemption: Boolean = false)

object GoalLinks {

    private fun sameName(a: String, b: String): Boolean {
        val ka = Categorizer.merchantKey(a)
        val kb = Categorizer.merchantKey(b)
        return ka == kb || (ka.length >= 4 && kb.contains(ka)) || (kb.length >= 4 && ka.contains(kb))
    }

    /**
     * [investments]: investment SMS events; [holdings]: name to current value; [labelled]: label to its payments.
     */
    fun linkedAmount(
        link: GoalLink,
        investments: List<GoalMoney>,
        holdings: Map<String, Long>,
        labelled: Map<String, List<GoalMoney>>,
        today: LocalDate,
    ): LinkedAmount {
        val flows: List<GoalMoney> = when (link.kind) {
            GoalLinkKind.INVESTMENT -> investments.filter { sameName(it.name, link.ref) }
            GoalLinkKind.LABEL -> labelled.entries.firstOrNull { it.key.equals(link.ref, ignoreCase = true) }?.value.orEmpty()
            GoalLinkKind.HOLDING -> emptyList()
        }.filter { !it.date.isBefore(link.since) && !it.date.isAfter(today) }

        val saved = when (link.kind) {
            GoalLinkKind.HOLDING -> holdings.entries.firstOrNull { sameName(it.key, link.ref) }?.value ?: 0
            else -> flows.sumOf { if (it.redemption) -it.amountMinor else it.amountMinor }.coerceAtLeast(0)
        }
        // Pace: the planned amount, else the average of the last three full months of contributions.
        val pace = link.monthlyMinor ?: run {
            val thisMonth = YearMonth.from(today)
            val window = (1..3).map { thisMonth.minusMonths(it.toLong()) }.filter { !it.atEndOfMonth().isBefore(link.since) }
            if (window.isEmpty()) {
                // Linked this month: use what has gone in so far.
                flows.filter { YearMonth.from(it.date) == thisMonth && !it.redemption }.sumOf { it.amountMinor }
            } else {
                val inWindow = flows.filter { YearMonth.from(it.date) in window && !it.redemption }.sumOf { it.amountMinor }
                inWindow / window.size
            }
        }
        return LinkedAmount(link, saved, pace)
    }

    /**
     * Months to go from [saved] to [target] putting in [monthly] at the start of each month, with
     * [annualReturnPct] growth. Null when the target is out of reach (no contributions and no growth).
     */
    fun monthsToReach(saved: Long, target: Long, monthly: Long, annualReturnPct: Double): Int? {
        if (saved >= target) return 0
        if (monthly <= 0 && (annualReturnPct <= 0 || saved <= 0)) return null
        val r = annualReturnPct / 100.0 / 12.0
        var balance = saved.toDouble()
        for (m in 1..1200) {
            balance = (balance + monthly) * (1 + r)
            if (balance >= target) return m
        }
        return null
    }

    fun forecast(
        goal: SavingsGoal,
        linked: List<LinkedAmount>,
        today: LocalDate,
        annualReturnPct: Double = 0.0,
    ): GoalForecast {
        val total = goal.savedMinor + linked.sumOf { it.savedMinor }
        val monthly = linked.sumOf { it.monthlyMinor }
        val months = monthsToReach(total, goal.targetMinor, monthly, annualReturnPct)
        val reach = months?.let { YearMonth.from(today).plusMonths(it.toLong()) }
        val vs = if (reach != null && goal.deadline != null) ChronoUnit.MONTHS.between(YearMonth.from(goal.deadline), reach) else null
        val pct = if (goal.targetMinor <= 0) 100 else ((total.coerceAtLeast(0) * 100) / goal.targetMinor).coerceAtMost(100).toInt()
        return GoalForecast(goal, total, linked, monthly, reach, vs, pct)
    }

    /** Turns investment SMS events into goal money. */
    fun fromEvents(events: List<InvestmentEvent>, zone: ZoneId = ZoneId.systemDefault()): List<GoalMoney> =
        events.map { GoalMoney(it.name, it.amountMinor, Dates.localDate(it.timestamp, zone), it.redemption) }
}
