package com.myfinance.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A savings target such as "Vacation ₹1L by June". */
data class SavingsGoal(
    val id: String,
    val name: String,
    val targetMinor: Long,
    val deadline: LocalDate?,
    val savedMinor: Long,
    val createdOn: LocalDate,
)

enum class GoalStatus { DONE, ON_TRACK, BEHIND, OVERDUE, NO_DEADLINE }

data class GoalProgress(
    val goal: SavingsGoal,
    val percent: Int,
    val remainingMinor: Long,
    /** What has to be saved each month from now to hit the deadline. Null without a deadline or when done. */
    val perMonthNeededMinor: Long?,
    val status: GoalStatus,
)

object Goals {
    fun progress(goal: SavingsGoal, today: LocalDate): GoalProgress {
        val remaining = (goal.targetMinor - goal.savedMinor).coerceAtLeast(0)
        val percent = if (goal.targetMinor <= 0) 100
        else ((goal.savedMinor.coerceAtLeast(0) * 100) / goal.targetMinor).coerceAtMost(100).toInt()
        if (remaining == 0L) return GoalProgress(goal, 100, 0, null, GoalStatus.DONE)
        val deadline = goal.deadline ?: return GoalProgress(goal, percent, remaining, null, GoalStatus.NO_DEADLINE)
        if (today.isAfter(deadline)) return GoalProgress(goal, percent, remaining, remaining, GoalStatus.OVERDUE)

        // Count the current month too: a goal due later this month still has one month left.
        val monthsLeft = (ChronoUnit.MONTHS.between(today.withDayOfMonth(1), deadline.withDayOfMonth(1)) + 1).coerceAtLeast(1)
        val perMonth = (remaining + monthsLeft - 1) / monthsLeft

        // On track when the saved share is at least the share of time that has passed.
        val totalDays = ChronoUnit.DAYS.between(goal.createdOn, deadline).coerceAtLeast(1)
        val elapsed = ChronoUnit.DAYS.between(goal.createdOn, today).coerceIn(0, totalDays)
        val expected = goal.targetMinor * elapsed / totalDays
        val status = if (goal.savedMinor >= expected) GoalStatus.ON_TRACK else GoalStatus.BEHIND
        return GoalProgress(goal, percent, remaining, perMonth, status)
    }
}
