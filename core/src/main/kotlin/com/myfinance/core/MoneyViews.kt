package com.myfinance.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/*
 * Views over the stored money for the release after 1.8: balances and card use, this month against
 * last month, cash / UPI / card, fixed and flexible spending, the savings rate over time, a family
 * summary to share, the meal card and FASTag, and a weekly digest. All take transactions that count
 * in totals (left-out ones already removed).
 */

// ---- Balances and card use ----

/** One registered (or seen) bank account or card, with its newest balance or available limit. */
data class AccountBalance(
    val label: String,
    val owner: String?,
    val isCard: Boolean,
    /** Bank: the balance. Card: the available limit. Null when no SMS has said it yet. */
    val amountMinor: Long?,
    val asOf: Long?,
    /** The card's credit limit, when the user entered it. */
    val limitMinor: Long? = null,
    /** Card spends this month, used when no available-limit SMS and limit are known. */
    val spentThisMonthMinor: Long? = null,
) {
    /** What is used on the card: limit minus available, or else the spends this month. */
    val usedMinor: Long?
        get() = if (!isCard) null else limitMinor?.let { l -> amountMinor?.let { (l - it).coerceAtLeast(0) } } ?: spentThisMonthMinor

    /** Card use as a share of the limit, 0..100+, when the limit is known. */
    val utilisationPct: Int?
        get() = limitMinor?.takeIf { it > 0 }?.let { l -> usedMinor?.let { ((it * 100) / l).toInt() } }
}

data class BalancesOverview(val banks: List<AccountBalance>, val cards: List<AccountBalance>) {
    val bankTotalMinor: Long get() = banks.sumOf { it.amountMinor ?: 0 }
    val cardUsedMinor: Long get() = cards.sumOf { it.usedMinor ?: 0 }
    val cardLimitMinor: Long get() = cards.sumOf { it.limitMinor ?: 0 }
    val utilisationPct: Int?
        get() = cardLimitMinor.takeIf { it > 0 }?.let { l -> ((cards.filter { it.limitMinor != null }.sumOf { it.usedMinor ?: 0 } * 100) / l).toInt() }
}

object AccountBalances {
    /**
     * Registered accounts and cards with their newest balance / available limit from SMS, then any other
     * account an SMS gave a balance for. [cardSpends] maps a card's last 4 digits to its spends this month.
     */
    fun overview(
        readings: List<BalanceReading>,
        accounts: List<MyAccount>,
        limits: Map<Long, Long> = emptyMap(),
        cardSpends: Map<String, Long> = emptyMap(),
    ): BalancesOverview {
        val latest = Balances.latest(readings)
        val used = HashSet<String>()
        val rows = accounts.map { a ->
            val r = latest.filter { it.isCardLimit == a.isCard && it.accountTail?.takeLast(4) == a.tail && MyAccounts.sameBank(a.bank, it.bank) }
                .maxByOrNull { it.timestamp }
            r?.let { used += it.accountKey }
            AccountBalance(
                a.name?.takeIf { it.isNotBlank() }?.let { "$it ••${a.tail}" } ?: a.label, a.owner, a.isCard,
                r?.amountMinor, r?.timestamp, limits[a.id] ?: a.creditLimitMinor, if (a.isCard) cardSpends[a.tail] else null,
            )
        }
        val others = latest.filter { it.accountKey !in used && it.accountTail != null }
            .map { AccountBalance(it.label, null, it.isCardLimit, it.amountMinor, it.timestamp, spentThisMonthMinor = if (it.isCardLimit) cardSpends[it.accountTail?.takeLast(4)] else null) }
        val all = rows + others
        return BalancesOverview(all.filterNot { it.isCard }, all.filter { it.isCard })
    }
}

// ---- This month against last month ----

data class CategoryChange(val category: String, val thisMinor: Long, val lastMinor: Long) {
    val changeMinor: Long get() = thisMinor - lastMinor
}

data class MonthComparison(
    val month: YearMonth,
    val spentMinor: Long,
    val lastSpentMinor: Long,
    /** Last month's spending up to the same day of the month, to compare a month still running fairly. */
    val lastSameDayMinor: Long,
    val receivedMinor: Long,
    val lastReceivedMinor: Long,
    val categories: List<CategoryChange>,
    /** Day of the month reached (for a month still running), else null. */
    val dayReached: Int?,
)

object MonthCompare {
    fun of(txns: List<TxnLite>, month: YearMonth, today: LocalDate, cycle: MonthCycle = MonthCycle.CALENDAR, zone: ZoneId = ZoneId.systemDefault()): MonthComparison {
        val last = month.minusMonths(1)
        val by = txns.groupBy { cycle.periodOf(it, zone) }
        val now = by[month].orEmpty()
        val before = by[last].orEmpty()
        fun spent(l: List<TxnLite>) = l.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor }
        fun received(l: List<TxnLite>) = l.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor }
        val start = cycle.start(month)
        val running = !today.isBefore(start) && !today.isAfter(cycle.end(month))
        val dayIndex = if (running) ChronoUnit.DAYS.between(start, today) else null
        val lastStart = cycle.start(last)
        val sameDay = if (dayIndex == null) spent(before) else spent(before.filter { Dates.localDate(it.timestamp, zone) <= lastStart.plusDays(dayIndex) })
        val cats = (now + before).filter { it.type == TxnType.DEBIT }.map { it.category }.distinct().map { c ->
            CategoryChange(c, now.filter { it.type == TxnType.DEBIT && it.category == c }.sumOf { it.amountMinor }, before.filter { it.type == TxnType.DEBIT && it.category == c }.sumOf { it.amountMinor })
        }.sortedByDescending { kotlin.math.abs(it.changeMinor) }
        return MonthComparison(month, spent(now), spent(before), sameDay, received(now), received(before), cats, dayIndex?.let { it.toInt() + 1 })
    }
}

// ---- Cash, UPI and card ----


object ModeSplit {
    /** Spending in [months] by how it was paid, largest first. */
    fun of(txns: List<TxnLite>, months: Set<YearMonth>, cycle: MonthCycle = MonthCycle.CALENDAR, zone: ZoneId = ZoneId.systemDefault()): List<ModeShare> =
        txns.filter { it.type == TxnType.DEBIT && cycle.periodOf(it, zone) in months }
            .groupBy { it.mode }
            .map { (m, l) -> ModeShare(m, l.sumOf { it.amountMinor }, l.size) }
            .sortedByDescending { it.totalMinor }
}

// ---- Fixed and flexible spending ----

data class FixedFlexible(val fixedMinor: Long, val flexibleMinor: Long, val fixed: List<GroupTotal>, val flexible: List<GroupTotal>) {
    val fixedPct: Int get() = (fixedMinor + flexibleMinor).takeIf { it > 0 }?.let { ((fixedMinor * 100) / it).toInt() } ?: 0
}

object FixedSpend {
    /** Categories that are commitments: they come every month whatever you do. */
    val fixedCategories = setOf(DefaultCategories.RENT, DefaultCategories.EMI, DefaultCategories.BILLS, DefaultCategories.EDUCATION, DefaultCategories.INVESTMENTS)

    /**
     * Splits a month's spending into fixed (rent, EMIs, bills, fees, SIPs, and payments seen repeating:
     * [recurringMerchants] as merchant keys) and flexible (everything else, which you can cut).
     */
    fun of(
        txns: List<TxnLite>,
        month: YearMonth,
        recurringMerchants: Set<String> = emptySet(),
        cycle: MonthCycle = MonthCycle.CALENDAR,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FixedFlexible {
        val debits = txns.filter { it.type == TxnType.DEBIT && cycle.periodOf(it, zone) == month }
        val (fixed, flex) = debits.partition { t ->
            t.category in fixedCategories || t.merchant?.let { Categorizer.merchantKey(it) in recurringMerchants } == true
        }
        fun groups(l: List<TxnLite>) = l.groupBy { it.category }.map { (k, v) -> GroupTotal(k, v.sumOf { it.amountMinor }, v.size) }.sortedByDescending { it.totalMinor }
        return FixedFlexible(fixed.sumOf { it.amountMinor }, flex.sumOf { it.amountMinor }, groups(fixed), groups(flex))
    }
}

// ---- Savings rate over time ----

data class SavingsPoint(val month: YearMonth, val receivedMinor: Long, val spentMinor: Long) {
    val savedMinor: Long get() = receivedMinor - spentMinor
    /** Share of money received that was not spent; null without income. */
    val ratePct: Int? get() = receivedMinor.takeIf { it > 0 }?.let { ((savedMinor * 100) / it).toInt() }
}

object SavingsTrend {
    fun of(txns: List<TxnLite>, end: YearMonth, months: Int = 12, cycle: MonthCycle = MonthCycle.CALENDAR, zone: ZoneId = ZoneId.systemDefault()): List<SavingsPoint> {
        val by = txns.groupBy { cycle.periodOf(it, zone) }
        return Reports.months(end, months).map { m ->
            val l = by[m].orEmpty()
            SavingsPoint(m, l.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor }, l.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor })
        }
    }

    /** The average rate over the months that had income. */
    fun averagePct(points: List<SavingsPoint>): Int? {
        val withIncome = points.filter { it.receivedMinor > 0 }
        if (withIncome.isEmpty()) return null
        return ((withIncome.sumOf { it.savedMinor } * 100) / withIncome.sumOf { it.receivedMinor }).toInt()
    }
}

// ---- Family summary to share ----

object FamilySummary {
    private val monthName = DateTimeFormatter.ofPattern("MMMM yyyy")

    /** A short plain-text month summary for WhatsApp: totals, each person's spending, top categories. */
    fun text(txns: List<TxnLite>, month: YearMonth, me: String, cycle: MonthCycle = MonthCycle.CALENDAR, zone: ZoneId = ZoneId.systemDefault()): String {
        val l = txns.filter { cycle.periodOf(it, zone) == month }
        val debits = l.filter { it.type == TxnType.DEBIT }
        val spent = debits.sumOf { it.amountMinor }
        val received = l.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor }
        val sb = StringBuilder()
        sb.append("*MyFinance: ${month.format(monthName)}*\n")
        sb.append("Spent ${formatInr(spent)} · Received ${formatInr(received)}")
        if (received > 0) sb.append(" · Saved ${((received - spent) * 100 / received)}%")
        sb.append('\n')
        val people = debits.groupBy { it.owner?.takeIf { o -> o.isNotBlank() } ?: me }
        if (people.size > 1) {
            sb.append("\n*By person*\n")
            people.map { (p, v) -> p to v.sumOf { it.amountMinor } }.sortedByDescending { it.second }
                .forEach { (p, v) -> sb.append("• $p: ${formatInr(v)}\n") }
        }
        val cats = debits.groupBy { it.category }.map { (c, v) -> c to v.sumOf { it.amountMinor } }.sortedByDescending { it.second }.take(5)
        if (cats.isNotEmpty()) {
            sb.append("\n*Top spending*\n")
            cats.forEach { (c, v) -> sb.append("• $c: ${formatInr(v)}\n") }
        }
        return sb.toString().trimEnd()
    }
}

// ---- Meal card and FASTag ----

/** A prepaid instrument (meal card, FASTag) shown as its own page. */
enum class Instrument(val label: String, private val match: Regex) {
    MEAL_CARD("Meal card", Regex("""meal\s*card|pluxee|sodexo|zeta|ticket\s*restaurant""", RegexOption.IGNORE_CASE)),
    FASTAG("FASTag", Regex("""fastag|fas\s*tag|\btoll\b""", RegexOption.IGNORE_CASE));

    fun matches(account: String?, text: String? = null): Boolean =
        listOfNotNull(account, text).any { match.containsMatchIn(it) }
}

data class InstrumentEntry(val txn: TxnLite, val balanceMinor: Long?)

data class InstrumentView(
    val instrument: Instrument,
    val entries: List<InstrumentEntry>,
    val month: YearMonth,
    val spentThisMonthMinor: Long,
    val loadedThisMonthMinor: Long,
    /** The balance in the newest message that gave one. */
    val balanceMinor: Long?,
    val byPlace: List<GroupTotal>,
)

object Instruments {
    /** [rows] pairs each transaction with its SMS text and the balance that SMS gave. */
    fun view(instrument: Instrument, rows: List<Triple<TxnLite, String?, Long?>>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): InstrumentView {
        val mine = rows.filter { (t, text, _) -> instrument.matches(t.account, text) }.sortedByDescending { it.first.timestamp }
        val inMonth = mine.filter { YearMonth.from(Dates.localDate(it.first.timestamp, zone)) == month }.map { it.first }
        val debits = inMonth.filter { it.type == TxnType.DEBIT }
        return InstrumentView(
            instrument,
            mine.map { InstrumentEntry(it.first, it.third) },
            month,
            debits.sumOf { it.amountMinor },
            inMonth.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
            mine.firstOrNull { it.third != null }?.third,
            debits.groupBy { it.merchant?.takeIf { m -> m.isNotBlank() } ?: "Other" }
                .map { (k, v) -> GroupTotal(k, v.sumOf { it.amountMinor }, v.size) }.sortedByDescending { it.totalMinor },
        )
    }
}

// ---- Weekly digest ----

data class WeeklyDigest(
    val weekStart: LocalDate,
    val spentMinor: Long,
    val previousWeekMinor: Long,
    val topCategory: GroupTotal?,
    val biggest: TxnLite?,
    /** Payments due in the coming 7 days: (what, amount or null, date). */
    val dueNext: List<Triple<String, Long?, LocalDate>>,
) {
    val title: String get() = "Last week you spent ${formatInr(spentMinor)}"

    val body: String
        get() = buildList {
            if (previousWeekMinor > 0) {
                val diff = spentMinor - previousWeekMinor
                add(if (diff >= 0) "${formatInr(diff)} more than the week before." else "${formatInr(-diff)} less than the week before.")
            }
            topCategory?.let { add("Most on ${it.key} (${formatInr(it.totalMinor)}).") }
            if (dueNext.isNotEmpty()) {
                val total = dueNext.sumOf { it.second ?: 0 }
                add("${dueNext.size} payment${if (dueNext.size == 1) "" else "s"} due this week" + (if (total > 0) ", ${formatInr(total)}." else "."))
            }
        }.joinToString(" ")
}

object Weekly {
    /** The Monday that starts the week before [today]'s week. */
    fun lastWeekStart(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)

    fun digest(txns: List<TxnLite>, today: LocalDate, due: List<Triple<String, Long?, LocalDate>>, zone: ZoneId = ZoneId.systemDefault()): WeeklyDigest {
        val start = lastWeekStart(today)
        fun week(from: LocalDate) = txns.filter { t ->
            t.type == TxnType.DEBIT && Dates.localDate(t.timestamp, zone).let { !it.isBefore(from) && it.isBefore(from.plusDays(7)) }
        }
        val w = week(start)
        return WeeklyDigest(
            start, w.sumOf { it.amountMinor }, week(start.minusWeeks(1)).sumOf { it.amountMinor },
            w.groupBy { it.category }.map { (k, v) -> GroupTotal(k, v.sumOf { it.amountMinor }, v.size) }.maxByOrNull { it.totalMinor },
            w.maxByOrNull { it.amountMinor },
            due.filter { !it.third.isBefore(today) && it.third.isBefore(today.plusDays(7)) }.sortedBy { it.third },
        )
    }
}
