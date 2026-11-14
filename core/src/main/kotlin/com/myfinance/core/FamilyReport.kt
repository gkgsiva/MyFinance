package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The household's month across everyone's phones: once the family's exports are merged on one phone (WhatsApp
 * share, then open the file with MyFinance), each person's received, spent and invested stay separate, with
 * their own categories and cards, against their own monthly limit when one is set.
 *
 * The usual rules: investments are "Invested", not spending; own-account transfers and card bill payments are
 * left out (pass only counted entries). An entry's person is its owner (set by the phone it came from, or the
 * card's owner in My cards & accounts), or this phone's owner.
 */
data class FamilyReport(
    val period: YearMonth,
    val rangeLabel: String,
    val people: List<Person>,
    /** Categories with each person's spending in them, largest first. */
    val categories: List<CategoryRow>,
    /** Each person's newest entry on this phone, so a family member whose export is old shows up. */
    val lastEntry: Map<String, LocalDate>,
) {
    val receivedMinor: Long get() = people.sumOf { it.receivedMinor }
    val spentMinor: Long get() = people.sumOf { it.spentMinor }
    val investedMinor: Long get() = people.sumOf { it.investedMinor }
    val names: List<String> get() = people.map { it.name }

    data class Person(
        val name: String,
        val receivedMinor: Long,
        val spentMinor: Long,
        val investedMinor: Long,
        val count: Int,
        val categories: List<MonthReport.Share>,
        /** Accounts and cards this person's entries came from. */
        val accounts: List<MonthReport.Share>,
        val biggest: List<TxnLite>,
        /** The monthly limit set for this person, if any. */
        val limitMinor: Long? = null,
    ) {
        val leftMinor: Long get() = receivedMinor - spentMinor - investedMinor
        val limitPercent: Int? get() = limitMinor?.takeIf { it > 0 }?.let { ((spentMinor * 100) / it).toInt() }
        fun shareOf(totalMinor: Long): Int = if (totalMinor > 0) ((spentMinor * 100 + totalMinor / 2) / totalMinor).toInt() else 0
    }

    data class CategoryRow(val category: String, val byPerson: Map<String, Long>, val totalMinor: Long)

    companion object {
        fun personOf(t: TxnLite, me: String): String = t.owner?.takeIf { it.isNotBlank() } ?: me

        fun build(
            txns: List<TxnLite>,
            period: YearMonth,
            me: String,
            cycle: MonthCycle = MonthCycle.CALENDAR,
            zone: ZoneId = ZoneId.systemDefault(),
            limits: Map<String, Long> = emptyMap(),
        ): FamilyReport {
            val inMonth = txns.filter { cycle.periodOf(it, zone) == period }
            val byPerson = inMonth.groupBy { personOf(it, me) }
            // Everyone with a limit is listed, even with nothing this month.
            val names = (byPerson.keys + limits.keys.filter { (limits[it] ?: 0) > 0 }).distinct()
            fun shares(l: List<TxnLite>, key: (TxnLite) -> String?): List<MonthReport.Share> {
                val total = l.sumOf { it.amountMinor }
                return l.groupBy { key(it)?.takeIf { k -> k.isNotBlank() } ?: "Not set" }
                    .map { (k, v) ->
                        val s = v.sumOf { it.amountMinor }
                        MonthReport.Share(k, s, v.size, if (total > 0) ((s * 100 + total / 2) / total).toInt() else 0)
                    }
                    .sortedByDescending { it.totalMinor }
            }
            val people = names.map { n ->
                val l = byPerson[n].orEmpty()
                val spends = l.filter { it.isSpend }
                Person(
                    name = n,
                    receivedMinor = l.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
                    spentMinor = spends.sumOf { it.amountMinor },
                    investedMinor = l.filter { it.isInvestment }.sumOf { it.amountMinor },
                    count = l.size,
                    categories = shares(spends) { it.category },
                    accounts = shares(spends) { it.account ?: if (it.mode == PaymentMode.CASH) "Cash" else null },
                    biggest = spends.sortedByDescending { it.amountMinor }.take(3),
                    limitMinor = limits[n]?.takeIf { it > 0 },
                )
            }.sortedWith(compareByDescending<Person> { it.name == me }.thenByDescending { it.spentMinor })
            val categories = inMonth.filter { it.isSpend }.groupBy { it.category }
                .map { (c, v) -> CategoryRow(c, v.groupBy { personOf(it, me) }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }, v.sumOf { it.amountMinor }) }
                .sortedByDescending { it.totalMinor }
            val lastEntry = txns.groupBy { personOf(it, me) }.mapValues { (_, l) -> Dates.localDate(l.maxOf { it.timestamp }, zone) }
            val start = cycle.start(period)
            val end = cycle.end(period)
            val f = java.time.format.DateTimeFormatter.ofPattern("d MMM")
            val fy = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
            return FamilyReport(period, "${start.format(f)} – ${end.format(fy)}", people, categories, lastEntry)
        }
    }
}

// ---- Monthly limit per family member ----

/** One person's spending this month against their limit. */
data class PersonBudgetLine(val person: String, val limitMinor: Long, val spentMinor: Long) {
    val percent: Int get() = if (limitMinor <= 0) 0 else ((spentMinor * 100) / limitMinor).toInt()
    val leftMinor: Long get() = limitMinor - spentMinor
    /** 80 or 100 once reached, else null. */
    val level: Int? get() = BudgetAlerts.level(spentMinor, limitMinor)
}

object PersonBudgets {
    /** The key person limits use in budget alerts, so they never clash with a category or a label ("#…"). */
    fun alertKey(person: String) = "@$person"

    fun spent(txns: List<TxnLite>, period: YearMonth, me: String, cycle: MonthCycle = MonthCycle.CALENDAR, zone: ZoneId = ZoneId.systemDefault()): Map<String, Long> =
        txns.filter { it.isSpend && cycle.periodOf(it, zone) == period }
            .groupBy { FamilyReport.personOf(it, me) }
            .mapValues { (_, l) -> l.sumOf { it.amountMinor } }

    fun lines(spent: Map<String, Long>, limits: Map<String, Long>): List<PersonBudgetLine> =
        limits.filterValues { it > 0 }.map { (p, l) -> PersonBudgetLine(p, l, spent[p] ?: 0) }.sortedByDescending { it.percent }

    /** Stored as one "name<TAB>paise" line per person. */
    fun encode(limits: Map<String, Long>): String =
        limits.filter { it.key.isNotBlank() && it.value > 0 }.entries.joinToString("\n") { "${it.key.replace('\t', ' ').replace('\n', ' ').trim()}\t${it.value}" }

    fun decode(s: String?): Map<String, Long> =
        s.orEmpty().lineSequence().mapNotNull { line ->
            val i = line.lastIndexOf('\t')
            if (i <= 0) null else line.substring(i + 1).toLongOrNull()?.takeIf { it > 0 }?.let { line.substring(0, i) to it }
        }.toMap()
}
