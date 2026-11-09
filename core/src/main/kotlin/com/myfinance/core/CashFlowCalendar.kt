package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/* Cash flow calendar: a month of expected salary, EMIs, SIPs, bills and renewals, next to what actually happened. */

enum class FlowKind(val label: String) {
    SALARY("Salary"), EMI("EMI / loan"), SIP("SIP / investment"), BILL("Bill"), SUBSCRIPTION("Subscription"),
    INSURANCE("Insurance"), LENDING("Lent / borrowed"), AUTO_DEBIT("Auto debit"), SPENT("Spent"), RECEIVED("Received"),
}

data class FlowEvent(
    val date: LocalDate,
    val title: String,
    val amountMinor: Long?,
    val incoming: Boolean,
    val kind: FlowKind,
    /** True for what is expected, false for transactions that happened. */
    val expected: Boolean,
    val txnId: Long? = null,
    /** Expected earlier but not seen yet. */
    val late: Boolean = false,
)

data class CalendarDay(
    val date: LocalDate,
    val events: List<FlowEvent>,
    val inMinor: Long,
    val outMinor: Long,
    val expectedInMinor: Long,
    val expectedOutMinor: Long,
)

data class CashFlowMonth(
    val month: YearMonth,
    val days: List<CalendarDay>,
    val actualInMinor: Long,
    val actualOutMinor: Long,
    val expectedInMinor: Long,
    val expectedOutMinor: Long,
) {
    /** Actual so far plus what is still expected. */
    val projectedInMinor get() = actualInMinor + expectedInMinor
    val projectedOutMinor get() = actualOutMinor + expectedOutMinor
}

data class SalaryPattern(val dayOfMonth: Int, val amountMinor: Long, val name: String)

object CashFlowCalendar {

    /** Typical salary day and amount from the last six months of salary credits. */
    fun salaryPattern(txns: List<TxnLite>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SalaryPattern? {
        val from = today.minusMonths(6)
        val salary = txns.filter {
            it.type == TxnType.CREDIT && it.category == DefaultCategories.SALARY && !Dates.localDate(it.timestamp, zone).isBefore(from)
        }
        if (salary.isEmpty()) return null
        val byMonth = salary.groupBy { Dates.yearMonth(it.timestamp, zone) }.toSortedMap()
        val days = byMonth.values.map { list -> list.minOf { Dates.localDate(it.timestamp, zone).dayOfMonth } }.sorted()
        val day = days[days.size / 2]
        val lastMonth = byMonth.values.last()
        val name = lastMonth.maxBy { it.amountMinor }.merchant ?: "Salary"
        return SalaryPattern(day, lastMonth.sumOf { it.amountMinor }, name)
    }

    private fun kindOf(k: AutoDebitKind) = when (k) {
        AutoDebitKind.EMI -> FlowKind.EMI
        AutoDebitKind.SIP -> FlowKind.SIP
        AutoDebitKind.INSURANCE -> FlowKind.INSURANCE
        AutoDebitKind.SUBSCRIPTION -> FlowKind.SUBSCRIPTION
        AutoDebitKind.AUTO_DEBIT, AutoDebitKind.OTHER -> FlowKind.AUTO_DEBIT
    }

    /** Dates in [month] on or after [notBefore] for something due on [next] every [everyMonths] months. */
    private fun occurrences(next: LocalDate, everyMonths: Int, month: YearMonth, notBefore: LocalDate): List<LocalDate> {
        val step = everyMonths.coerceAtLeast(1).toLong()
        val out = ArrayList<LocalDate>()
        var d = next
        var n = 0
        // Walk to the month, keeping the original day of month where the month allows it.
        while (YearMonth.from(d).isBefore(month) && n < 600) { n++; d = next.plusMonths(step * n) }
        while (YearMonth.from(d) == month) {
            if (!d.isBefore(notBefore)) out += d
            n++
            d = next.plusMonths(step * n)
        }
        return out
    }

    fun build(
        month: YearMonth,
        today: LocalDate,
        txns: List<TxnLite>,
        autoDebits: List<AutoDebitItem> = emptyList(),
        bills: List<BillReminder> = emptyList(),
        policies: List<Policy> = emptyList(),
        lending: List<LendStatus> = emptyList(),
        subscriptions: List<SubscriptionItem> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): CashFlowMonth {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val events = ArrayList<FlowEvent>()

        // What happened, up to today.
        val actual = txns.filter {
            val d = Dates.localDate(it.timestamp, zone)
            !d.isBefore(first) && !d.isAfter(last) && !d.isAfter(today) && it.category != DefaultCategories.TRANSFERS
        }
        for (t in actual) {
            val incoming = t.type == TxnType.CREDIT
            events += FlowEvent(
                Dates.localDate(t.timestamp, zone), t.merchant ?: t.category, t.amountMinor, incoming,
                if (incoming) (if (t.category == DefaultCategories.SALARY) FlowKind.SALARY else FlowKind.RECEIVED) else FlowKind.SPENT,
                expected = false, txnId = t.id,
            )
        }

        if (!last.isBefore(today)) {
            val expected = ArrayList<FlowEvent>()
            // Salary, unless it has already come in this month.
            salaryPattern(txns, today, zone)?.let { s ->
                val due = month.atDay(minOf(s.dayOfMonth, month.lengthOfMonth()))
                val alreadyIn = actual.any { it.type == TxnType.CREDIT && it.category == DefaultCategories.SALARY }
                if (!alreadyIn) {
                    if (!due.isBefore(today)) expected += FlowEvent(due, "Salary (expected)", s.amountMinor, true, FlowKind.SALARY, true)
                    else if (YearMonth.from(today) == month) expected += FlowEvent(today, "Salary (expected on ${due.dayOfMonth}, not in yet)", s.amountMinor, true, FlowKind.SALARY, true, late = true)
                }
            }
            for (a in autoDebits) {
                if (a.state == DueState.OVERDUE && YearMonth.from(today) == month) {
                    expected += FlowEvent(today, "${a.name} (was due ${a.nextDate.dayOfMonth} ${a.nextDate.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }})", a.amountMinor, false, kindOf(a.kind), true, late = true)
                }
                val dates = occurrences(a.nextDate, a.everyMonths, month, today)
                val ends = a.endDate
                for (d in dates) if (ends == null || !d.isAfter(ends)) expected += FlowEvent(d, a.name, a.amountMinor, false, kindOf(a.kind), true)
            }
            for (b in bills) {
                for (d in occurrences(b.nextDue, b.everyMonths, month, today)) {
                    val kind = when (b.kind) {
                        ReminderKind.INSURANCE -> FlowKind.INSURANCE
                        ReminderKind.LOAN_EMI -> FlowKind.EMI
                        ReminderKind.SUBSCRIPTION -> FlowKind.SUBSCRIPTION
                        else -> FlowKind.BILL
                    }
                    expected += FlowEvent(d, b.name, b.amountMinor, false, kind, true)
                }
            }
            for (p in policies) {
                val r = p.renewalDate ?: continue
                val dates = if (p.everyMonths > 0) occurrences(r, p.everyMonths, month, today) else listOf(r).filter { YearMonth.from(it) == month && !it.isBefore(today) }
                for (d in dates) expected += FlowEvent(d, "${p.name} renewal", p.premiumMinor, false, FlowKind.INSURANCE, true)
            }
            for (s in subscriptions) {
                val every = when (s.cadence) { Cadence.YEARLY -> 12; Cadence.QUARTERLY -> 3; else -> 1 }
                for (d in occurrences(s.nextDate, every, month, today)) expected += FlowEvent(d, s.name, s.amountMinor, false, FlowKind.SUBSCRIPTION, true)
            }
            for (l in lending) {
                val due = l.entry.dueDate ?: continue
                if (l.state == LendState.SETTLED) continue
                val d = if (due.isBefore(today)) (if (YearMonth.from(today) == month) today else continue) else due
                if (YearMonth.from(d) != month) continue
                val lent = l.entry.direction == LendDirection.LENT
                val title = if (lent) "${l.entry.person} to return" else "Return to ${l.entry.person}"
                expected += FlowEvent(d, title + if (due.isBefore(today)) " (overdue)" else "", l.outstandingMinor, lent, FlowKind.LENDING, true, late = due.isBefore(today))
            }
            // The same payment can come from more than one list (a bill reminder and an auto debit): keep one.
            val kept = ArrayList<FlowEvent>()
            for (e in expected.sortedBy { it.date }) {
                val dup = kept.any { k ->
                    k.incoming == e.incoming && kotlin.math.abs(ChronoUnit.DAYS.between(k.date, e.date)) <= 3 &&
                        (sameTitle(k.title, e.title) || (k.amountMinor != null && k.amountMinor == e.amountMinor && k.kind == e.kind))
                }
                if (!dup) kept += e
            }
            events += kept
        }

        val byDate = events.groupBy { it.date }
        val days = (1..month.lengthOfMonth()).map { i ->
            val d = month.atDay(i)
            val list = byDate[d].orEmpty().sortedWith(compareBy<FlowEvent> { it.expected }.thenByDescending { it.amountMinor ?: 0 })
            CalendarDay(
                date = d,
                events = list,
                inMinor = list.filter { !it.expected && it.incoming }.sumOf { it.amountMinor ?: 0 },
                outMinor = list.filter { !it.expected && !it.incoming }.sumOf { it.amountMinor ?: 0 },
                expectedInMinor = list.filter { it.expected && it.incoming }.sumOf { it.amountMinor ?: 0 },
                expectedOutMinor = list.filter { it.expected && !it.incoming }.sumOf { it.amountMinor ?: 0 },
            )
        }
        return CashFlowMonth(
            month, days,
            actualInMinor = days.sumOf { it.inMinor }, actualOutMinor = days.sumOf { it.outMinor },
            expectedInMinor = days.sumOf { it.expectedInMinor }, expectedOutMinor = days.sumOf { it.expectedOutMinor },
        )
    }

    private fun sameTitle(a: String, b: String): Boolean {
        val wa = Categorizer.merchantKey(a).split(' ').filter { it.length >= 4 }.toSet()
        val wb = Categorizer.merchantKey(b).split(' ').filter { it.length >= 4 }.toSet()
        return wa.intersect(wb).any { it !in setOf("renewal", "expected", "payment", "bill", "card", "loan") }
    }
}
