package com.myfinance.core

import java.time.LocalDate

/**
 * End dates for repeating payments that the user did not give one: an EMI the user added (as an EMI plan or a
 * loan EMI reminder) takes the last instalment from the card statements that print "<4/6>" on each instalment
 * (see [AutoDebits.instalmentEmis]).
 */
object RepeatEnds {

    /** How far after the statement line the matching payment can fall (a card EMI is paid with the card bill). */
    private const val AFTER_DAYS = 35L
    private const val BEFORE_DAYS = 10L

    private fun words(s: String): Set<String> =
        Categorizer.merchantKey(s).split(' ').filter { it.length >= 4 && it !in generic }.toSet()

    private val generic = setOf("loan", "card", "bank", "payment", "monthly", "instalment", "installment", "credit")

    /**
     * The statement EMI that a payment called [name] of [amountMinor] (on [account], when known) stands for:
     * the amount within 15%, or the name sharing a word when there is no amount. Null when none or more than
     * one fit equally well.
     */
    fun emiFor(name: String, amountMinor: Long?, account: String?, matchWords: List<String>, emis: List<AutoDebitItem>): AutoDebitItem? {
        val mine = words(name) + matchWords.flatMap { words(it) }
        val fits = emis.filter { e ->
            e.endDate != null && e.instalmentsTotal != null &&
                (account == null || e.account == null || e.account == account) &&
                if (amountMinor != null && amountMinor > 0) kotlin.math.abs(e.amountMinor - amountMinor) * 100 <= amountMinor * 15
                else mine.isNotEmpty() && words(e.name).any { it in mine }
        }
        if (fits.size <= 1) return fits.singleOrNull()
        // Several EMIs of a similar amount: the name decides, then the closest amount.
        val named = fits.filter { e -> words(e.name).any { it in mine } }
        if (named.size == 1) return named[0]
        val pool = named.ifEmpty { fits }
        val target = amountMinor ?: return null
        val sorted = pool.sortedBy { kotlin.math.abs(it.amountMinor - target) }
        return sorted[0].takeIf { kotlin.math.abs(it.amountMinor - target) < kotlin.math.abs(sorted[1].amountMinor - target) }
    }

    /** The due date, among [dues] near the last instalment [end], that pays it; [end] itself when none is near. */
    fun alignEnd(end: LocalDate, dues: (LocalDate, LocalDate) -> List<LocalDate>): LocalDate =
        dues(end.minusDays(BEFORE_DAYS), end.plusDays(AFTER_DAYS)).firstOrNull() ?: end

    /** End dates for the user's EMI plans that have none, by plan id. */
    fun forPlans(plans: List<AutoDebitPlan>, emis: List<AutoDebitItem>): Map<String, LocalDate> =
        plans.filter { it.endDate == null && it.kind == AutoDebitKind.EMI }.mapNotNull { p ->
            val e = emiFor(p.name, p.amountMinor, p.account, p.matchWords, emis) ?: return@mapNotNull null
            val end = alignEnd(e.endDate!!) { from, to ->
                (0 until 1200).asSequence().map { AutoDebits.dueAt(p, it) }.dropWhile { it.isBefore(from) }.takeWhile { !it.isAfter(to) }.toList()
            }
            p.id to end
        }.toMap()

    /** End dates for repeating loan EMI reminders that have none, by reminder id. */
    fun forBills(bills: List<BillReminder>, emis: List<AutoDebitItem>): Map<String, LocalDate> =
        bills.filter { it.endDate == null && !it.oneTime && !it.done && it.kind == ReminderKind.LOAN_EMI }.mapNotNull { b ->
            val e = emiFor(b.name, b.amountMinor, null, b.matchWords, emis) ?: return@mapNotNull null
            b.id to alignEnd(e.endDate!!) { from, to -> BillReminders.dueDates(b, from, to) }
        }.toMap()
}
