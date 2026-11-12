package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The month as a ledger, for Home: every transaction that counts in the budget month, with money out,
 * money in and a running balance (money in minus money out so far this month).
 *
 * The balance runs oldest to newest; [days] lists the newest day first so today is at the top.
 * Hidden rows ([hiddenIds]) still move the balance and the totals, they just aren't listed.
 * A salary that counts for this month but came in at the end of the last one is listed under its own
 * date, which is before the month starts.
 */
data class MonthLedger(
    val period: YearMonth,
    val inMinor: Long,
    val outMinor: Long,
    val days: List<Day>,
    /** Rows left off the list because they are hidden. */
    val hiddenCount: Int,
) {
    val netMinor: Long get() = inMinor - outMinor
    val rowCount: Int get() = days.sumOf { it.rows.size }

    data class Row(
        val txn: TxnLite,
        /** Money in minus money out for the month, up to and including this row. */
        val balanceMinor: Long,
    ) {
        val isCredit: Boolean get() = txn.type == TxnType.CREDIT
    }

    data class Day(
        val date: LocalDate,
        val inMinor: Long,
        val outMinor: Long,
        /** Balance at the end of this day. */
        val closingMinor: Long,
        /** Newest first. */
        val rows: List<Row>,
    )

    companion object {
        fun build(
            txns: List<TxnLite>,
            period: YearMonth,
            cycle: MonthCycle,
            zone: ZoneId,
            hiddenIds: Set<Long> = emptySet(),
        ): MonthLedger {
            val inMonth = txns.filter { cycle.periodOf(it, zone) == period }
                .sortedWith(compareBy<TxnLite>({ it.timestamp }, { it.id }))
            var balance = 0L
            var totalIn = 0L
            var totalOut = 0L
            var hidden = 0
            val rows = ArrayList<Row>(inMonth.size)
            for (t in inMonth) {
                if (t.type == TxnType.CREDIT) { balance += t.amountMinor; totalIn += t.amountMinor }
                else { balance -= t.amountMinor; totalOut += t.amountMinor }
                if (t.id != 0L && t.id in hiddenIds) hidden++ else rows += Row(t, balance)
            }
            // Day totals include hidden rows, so the days add up to the month.
            val dayIn = HashMap<LocalDate, Long>()
            val dayOut = HashMap<LocalDate, Long>()
            val dayClose = HashMap<LocalDate, Long>()
            balance = 0L
            for (t in inMonth) {
                val d = Dates.localDate(t.timestamp, zone)
                if (t.type == TxnType.CREDIT) { balance += t.amountMinor; dayIn.merge(d, t.amountMinor, Long::plus) }
                else { balance -= t.amountMinor; dayOut.merge(d, t.amountMinor, Long::plus) }
                dayClose[d] = balance
            }
            val days = rows.groupBy { Dates.localDate(it.txn.timestamp, zone) }
                .map { (d, r) -> Day(d, dayIn[d] ?: 0, dayOut[d] ?: 0, dayClose[d] ?: 0, r.reversed()) }
                .sortedByDescending { it.date }
            return MonthLedger(period, totalIn, totalOut, days, hidden)
        }
    }
}
