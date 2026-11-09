package com.myfinance.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/* Insurance and warranty vault: policies and product warranties with their renewal and end dates. */

enum class PolicyKind(val label: String) {
    HEALTH("Health"), TERM("Term life"), LIFE("Life / endowment"), MOTOR("Car / bike"), HOME("Home"),
    TRAVEL("Travel"), ACCIDENT("Personal accident"), OTHER("Other"),
}

data class Policy(
    val id: String,
    val name: String,
    val kind: PolicyKind,
    val insurer: String,
    val policyNumber: String?,
    val coverMinor: Long?,
    val premiumMinor: Long?,
    /** Next renewal or premium due date. */
    val renewalDate: LocalDate?,
    /** Premium every N months (12 = yearly). 0 = single premium. */
    val everyMonths: Int = 12,
    val members: String? = null,
    val note: String? = null,
)

data class Warranty(
    val id: String,
    val item: String,
    val shop: String?,
    val purchaseDate: LocalDate,
    val priceMinor: Long?,
    val months: Int,
    val note: String? = null,
) {
    val endDate: LocalDate get() = purchaseDate.plusMonths(months.toLong()).minusDays(1)
}

enum class VaultState(val label: String) { OK("Active"), SOON("Due soon"), URGENT("Due this week"), DUE_TODAY("Due today"), LAPSED("Lapsed"), ENDED("Ended") }

data class VaultStatus(val daysLeft: Long?, val state: VaultState)

object Vault {
    /** Days before a date when reminders go out. */
    val reminderDays = listOf(30L, 7L, 1L, 0L)

    fun state(date: LocalDate?, today: LocalDate, endedLabel: VaultState = VaultState.LAPSED): VaultStatus {
        date ?: return VaultStatus(null, VaultState.OK)
        val days = ChronoUnit.DAYS.between(today, date)
        val state = when {
            days < 0 -> endedLabel
            days == 0L -> VaultState.DUE_TODAY
            days <= 7 -> VaultState.URGENT
            days <= 30 -> VaultState.SOON
            else -> VaultState.OK
        }
        return VaultStatus(days, state)
    }

    fun policyStatus(p: Policy, today: LocalDate) = state(p.renewalDate, today, VaultState.LAPSED)

    fun warrantyStatus(w: Warranty, today: LocalDate) = state(w.endDate, today, VaultState.ENDED)

    /** Moves a renewal date on by one premium period, after the user marks it renewed. */
    fun renewed(p: Policy): Policy {
        val d = p.renewalDate ?: return p
        if (p.everyMonths <= 0) return p.copy(renewalDate = null)
        return p.copy(renewalDate = d.plusMonths(p.everyMonths.toLong()))
    }

    /** Yearly premium total across policies. */
    fun yearlyPremium(policies: List<Policy>): Long = policies.sumOf { p ->
        val prem = p.premiumMinor ?: 0
        if (p.everyMonths <= 0) 0 else prem * 12 / p.everyMonths
    }

    /** True when [today] is one of the reminder days for [date]. */
    fun remindToday(date: LocalDate?, today: LocalDate): Boolean =
        date != null && ChronoUnit.DAYS.between(today, date) in reminderDays

    /** Policies the user should hear about today, as (key, message). The key stops a repeat on the same day. */
    fun reminders(policies: List<Policy>, warranties: List<Warranty>, today: LocalDate): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (p in policies) {
            val d = p.renewalDate ?: continue
            if (!remindToday(d, today)) continue
            val days = ChronoUnit.DAYS.between(today, d)
            val whenText = if (days == 0L) "today" else if (days == 1L) "tomorrow" else "in $days days"
            out += "policy:${p.id}:$d:$days" to "${p.name} (${p.insurer}) renews $whenText" + (p.premiumMinor?.let { ", premium ${formatInr(it)}" } ?: "")
        }
        for (w in warranties) {
            if (!remindToday(w.endDate, today)) continue
            val days = ChronoUnit.DAYS.between(today, w.endDate)
            val whenText = if (days == 0L) "today" else if (days == 1L) "tomorrow" else "in $days days"
            out += "warranty:${w.id}:$days" to "Warranty on ${w.item} ends $whenText. Get any repairs done before then."
        }
        return out
    }
}
