package com.myfinance.core

import java.time.YearMonth

/** A category that has crossed a share of its monthly budget. */
data class BudgetAlert(
    val category: String,
    val spentMinor: Long,
    val budgetMinor: Long,
    /** 80 or 100. */
    val percent: Int,
    val month: YearMonth,
) {
    /** One alert per category, month and level, so nobody is told twice. */
    val key: String get() = "$month|$category|$percent"

    /** A family member's monthly limit (keyed "@Name"), not a category. */
    val isPerson: Boolean get() = category.startsWith("@")

    /** What to call it in a notification: "Priya's spending" for a person's limit. */
    val title: String get() = if (isPerson) "${category.drop(1)}'s spending" else category

    val message: String
        get() = if (isPerson) {
            if (percent >= 100) "${category.drop(1)} is over the monthly limit: ${formatInr(spentMinor)} spent of ${formatInr(budgetMinor)}."
            else "${category.drop(1)} has used ${(spentMinor * 100 / budgetMinor)}% of the monthly limit: ${formatInr(spentMinor)} of ${formatInr(budgetMinor)}."
        } else if (percent >= 100) {
            "$category is over budget: ${formatInr(spentMinor)} spent of ${formatInr(budgetMinor)}."
        } else {
            "$category has used ${(spentMinor * 100 / budgetMinor)}% of its budget: " +
                "${formatInr(spentMinor)} of ${formatInr(budgetMinor)}."
        }
}

object BudgetAlerts {
    val levels = listOf(80, 100)

    /** The highest level reached, or null when under 80%. */
    fun level(spentMinor: Long, budgetMinor: Long): Int? {
        if (budgetMinor <= 0) return null
        return levels.lastOrNull { spentMinor * 100 >= budgetMinor * it }
    }

    /**
     * Alerts to send now. Only the highest new level is returned for each category (going from 70% to
     * 105% in one go sends just the "over budget" alert); the caller records [keysToRecord].
     */
    fun due(
        spentByCategory: Map<String, Long>,
        budgets: Map<String, Long>,
        alreadySent: Set<String>,
        month: YearMonth,
    ): List<BudgetAlert> = budgets.mapNotNull { (category, budget) ->
        val spent = spentByCategory[category] ?: 0
        val lvl = level(spent, budget) ?: return@mapNotNull null
        val alert = BudgetAlert(category, spent, budget, lvl, month)
        if (alert.key in alreadySent) null else alert
    }

    /** Keys to remember after sending [alert]: its level and every lower one. */
    fun keysToRecord(alert: BudgetAlert): List<String> =
        levels.filter { it <= alert.percent }.map { alert.copy(percent = it).key }
}
