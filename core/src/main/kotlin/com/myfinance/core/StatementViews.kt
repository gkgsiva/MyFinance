package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One entry as the statement view sees it. */
data class LedgerRow(
    val id: Long,
    val type: TxnType,
    val amountMinor: Long,
    val timestamp: Long,
    val accountTail: String?,
    val description: String,
    /** From an imported statement (else an SMS or the user's own entry). */
    val fromStatement: Boolean,
    /** Left out of totals (a card bill payment, a transfer, a reversal): still on the statement. */
    val excluded: Boolean = false,
    val manual: Boolean = false,
)

data class StatementLine(val row: LedgerRow, val date: LocalDate, val debitMinor: Long, val creditMinor: Long, val runningMinor: Long)

/** An account's or card's entries for [from]..[to], like a statement: oldest first with a running total. */
data class StatementPeriod(
    val tail: String,
    val from: LocalDate,
    val to: LocalDate,
    val lines: List<StatementLine>,
) {
    val debitsMinor: Long get() = lines.sumOf { it.debitMinor }
    val creditsMinor: Long get() = lines.sumOf { it.creditMinor }
    /** Credits minus debits. For a card, a negative number is what the period adds to the bill. */
    val netMinor: Long get() = creditsMinor - debitsMinor
    val fromStatementCount: Int get() = lines.count { it.row.fromStatement }
    val fromSmsCount: Int get() = lines.count { !it.row.fromStatement && !it.row.manual }
}

/** Card and bank "statements" for any period, built from what is stored (statement lines and SMS alike). */
object StatementViews {

    private fun tail4(s: String?) = s?.filter(Char::isDigit)?.takeLast(4)?.ifEmpty { null }

    fun build(rows: List<LedgerRow>, tail: String, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): StatementPeriod {
        val picked = rows.filter { r ->
            val t = tail4(r.accountTail) ?: return@filter false
            val d = Dates.localDate(r.timestamp, zone)
            StatementImport.sameAccount(t, tail) && !d.isBefore(from) && !d.isAfter(to)
        }.sortedWith(compareBy<LedgerRow>({ it.timestamp }, { it.id }))
        var running = 0L
        val lines = picked.map { r ->
            val debit = if (r.type == TxnType.DEBIT) r.amountMinor else 0L
            val credit = if (r.type == TxnType.CREDIT) r.amountMinor else 0L
            running += credit - debit
            StatementLine(r, Dates.localDate(r.timestamp, zone), debit, credit, running)
        }
        return StatementPeriod(tail, from, to, lines)
    }

    /**
     * A card's billing cycle that contains [day]: the day after one bill day to the next bill day, e.g. 15 Oct to
     * 14 Nov for a bill on the 14th. A bill day past the month's end falls on its last day.
     */
    fun cardCycle(billDay: Int, day: LocalDate): Pair<LocalDate, LocalDate> {
        fun billIn(m: YearMonth) = m.atDay(billDay.coerceIn(1, m.lengthOfMonth()))
        val thisMonth = YearMonth.from(day)
        val end = if (day.isAfter(billIn(thisMonth))) billIn(thisMonth.plusMonths(1)) else billIn(thisMonth)
        val start = billIn(YearMonth.from(end).minusMonths(1)).plusDays(1)
        return start to end
    }

    /** The calendar month that contains [day]. */
    fun month(day: LocalDate): Pair<LocalDate, LocalDate> = YearMonth.from(day).let { it.atDay(1) to it.atEndOfMonth() }
}
