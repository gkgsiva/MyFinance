package com.myfinance.core

import kotlin.math.abs

/**
 * Checks that the lines read from a statement add up to the statement's own figures, so a missed or
 * misread line shows up at import time (idea from Card-Statement-Analyser's validator and expense-analyser's
 * opening / closing balance check; reimplemented here).
 *
 * Bank: opening balance + money in − money out = closing balance.
 * Card: previous balance + spends − payments and refunds = total due.
 */
data class StatementCheck(
    /** What the statement says: the closing balance, or the card's total due. */
    val expectedMinor: Long,
    /** What the lines read add up to. */
    val computedMinor: Long,
) {
    val differenceMinor: Long get() = computedMinor - expectedMinor

    /** Within a rupee: rounding on interest lines is fine. */
    val ok: Boolean get() = abs(differenceMinor) <= 100

    companion object {
        /**
         * Rows with a running balance. The opening balance is the statement's own, or worked back from the
         * first line. Statements listed newest first are read in the other order.
         */
        fun bank(rows: List<StatementRow>, opening: Long? = null): StatementCheck? {
            val withBal = rows.filter { it.balanceMinor != null }
            if (withBal.size < 2 || withBal.size < rows.size * 0.8) return null
            val newestFirst = rows.first().date.isAfter(rows.last().date) ||
                (rows.first().date == rows.last().date && looksNewestFirst(rows))
            val ordered = if (newestFirst) rows.reversed() else rows
            val first = ordered.first()
            val start = opening ?: (first.balanceMinor!! - signed(first))
            val closing = ordered.last { it.balanceMinor != null }.balanceMinor!!
            val net = ordered.sumOf { signed(it) }
            return StatementCheck(closing, start + net)
        }

        /** Card bill: [previous] balance (negative when in credit) and the [totalDue] printed on it. */
        fun card(rows: List<StatementRow>, previous: Long, totalDue: Long): StatementCheck? {
            if (rows.isEmpty()) return null
            val spends = rows.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor }
            val credits = rows.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor }
            return StatementCheck(totalDue, previous + spends - credits)
        }

        private fun signed(r: StatementRow) = if (r.type == TxnType.CREDIT) r.amountMinor else -r.amountMinor

        /** Same-day statements: the balance chain tells the order. */
        private fun looksNewestFirst(rows: List<StatementRow>): Boolean {
            if (rows.size < 2) return false
            val a = rows[0]
            val b = rows[1]
            val ab = a.balanceMinor ?: return false
            val bb = b.balanceMinor ?: return false
            return bb + signed(a) == ab && ab + signed(b) != bb
        }
    }
}
