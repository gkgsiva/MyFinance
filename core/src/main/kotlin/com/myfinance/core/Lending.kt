package com.myfinance.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/* Money lent to, or borrowed from, friends and relatives. */

enum class LendDirection { LENT, BORROWED }

data class LendEntry(
    val id: String,
    val person: String,
    val direction: LendDirection,
    val amountMinor: Long,
    val date: LocalDate,
    val dueDate: LocalDate?,
    val note: String?,
    /** Marked settled by hand (e.g. part waived). */
    val closed: Boolean = false,
)

data class LendRepayment(val id: String, val entryId: String, val amountMinor: Long, val date: LocalDate)

enum class LendState(val label: String) { OPEN("Open"), DUE_SOON("Due soon"), OVERDUE("Overdue"), SETTLED("Settled") }

data class LendStatus(
    val entry: LendEntry,
    val repaidMinor: Long,
    val outstandingMinor: Long,
    val state: LendState,
    val daysLeft: Long?,
    val repayments: List<LendRepayment>,
)

data class PersonBalance(
    val person: String,
    /** Positive = they owe you; negative = you owe them. */
    val netMinor: Long,
    val open: Int,
    val overdue: Int,
)

data class LendingSummary(
    val owedToYouMinor: Long,
    val youOweMinor: Long,
    val overdueCount: Int,
    val people: List<PersonBalance>,
)

object Lending {
    private val day = DateTimeFormatter.ofPattern("d MMM yyyy")

    fun status(e: LendEntry, repayments: List<LendRepayment>, today: LocalDate): LendStatus {
        val mine = repayments.filter { it.entryId == e.id }.sortedBy { it.date }
        val repaid = mine.sumOf { it.amountMinor }
        val outstanding = (e.amountMinor - repaid).coerceAtLeast(0)
        val days = e.dueDate?.let { ChronoUnit.DAYS.between(today, it) }
        val state = when {
            e.closed || outstanding == 0L -> LendState.SETTLED
            days != null && days < 0 -> LendState.OVERDUE
            days != null && days <= 7 -> LendState.DUE_SOON
            else -> LendState.OPEN
        }
        return LendStatus(e, repaid, if (e.closed) 0 else outstanding, state, days, mine)
    }

    fun summary(statuses: List<LendStatus>): LendingSummary {
        val open = statuses.filter { it.state != LendState.SETTLED }
        val people = open.groupBy { it.entry.person.trim().lowercase() }.map { (_, list) ->
            PersonBalance(
                person = list.first().entry.person.trim(),
                netMinor = list.sumOf { if (it.entry.direction == LendDirection.LENT) it.outstandingMinor else -it.outstandingMinor },
                open = list.size,
                overdue = list.count { it.state == LendState.OVERDUE },
            )
        }.sortedByDescending { kotlin.math.abs(it.netMinor) }
        return LendingSummary(
            owedToYouMinor = open.filter { it.entry.direction == LendDirection.LENT }.sumOf { it.outstandingMinor },
            youOweMinor = open.filter { it.entry.direction == LendDirection.BORROWED }.sumOf { it.outstandingMinor },
            overdueCount = open.count { it.state == LendState.OVERDUE },
            people = people,
        )
    }

    /** A polite reminder to send on WhatsApp. */
    fun reminderMessage(s: LendStatus, myName: String?): String {
        val e = s.entry
        val first = e.person.trim().split(' ').first()
        val amount = formatInr(s.outstandingMinor)
        val forWhat = e.note?.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()
        return if (e.direction == LendDirection.LENT) {
            val due = e.dueDate?.let { if (s.state == LendState.OVERDUE) " It was due on ${it.format(day)}." else " Could you return it by ${it.format(day)}?" }
                ?: " Could you return it when you can?"
            "Hi $first, a gentle reminder about the $amount I gave you on ${e.date.format(day)}$forWhat.$due Thanks!" +
                (myName?.takeIf { it.isNotBlank() && it != "Me" }?.let { " - $it" } ?: "")
        } else {
            "Hi $first, I haven't forgotten the $amount you lent me on ${e.date.format(day)}$forWhat. I'll return it" +
                (e.dueDate?.let { " by ${it.format(day)}" } ?: " soon") + ". Thank you!"
        }
    }

    /** Due-date reminders to show today: the day before, on the day, and each week once overdue. */
    fun remindToday(s: LendStatus): Boolean {
        val d = s.daysLeft ?: return false
        if (s.state == LendState.SETTLED) return false
        return d == 1L || d == 0L || (d < 0 && (-d) % 7 == 0L)
    }
}
