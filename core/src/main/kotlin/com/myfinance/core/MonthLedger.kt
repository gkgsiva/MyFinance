package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The month as a ledger, for Home: every transaction that counts in the budget month, with money out,
 * money in and a running bank balance.
 *
 * Spending on a credit card counts as spending in the month it was made, but it doesn't leave the bank account
 * yet: card rows ([cardTails]) don't move the running balance. They add up in [cardDueMinor], what this month's
 * card use will put on the card bill. The bank balance goes down when the card bill is paid from the bank:
 * [cardBillPayments] (stored as left out of totals) are listed and move the balance, but are not spending.
 *
 * The balance runs oldest to newest; [days] lists the newest day first so today is at the top.
 * Hidden rows ([hiddenIds]) still move the balance and the totals, they just aren't listed.
 * Money put into investments (SIP, mutual funds, FD ...) is not spending: it is in [investedMinor], not
 * [outMinor], but it still leaves the account, so it moves the balance.
 * A salary that counts for this month but came in at the end of the last one is listed under its own
 * date, which is before the month starts.
 */
data class MonthLedger(
    val period: YearMonth,
    val inMinor: Long,
    /** Money spent, from the bank and on cards. Investments are not in here. */
    val outMinor: Long,
    val days: List<Day>,
    /** Rows left off the list because they are hidden. */
    val hiddenCount: Int,
    /** Money put into investments this month. */
    val investedMinor: Long = 0,
    /** Where the investments went (payee, or category when there is none), largest first. Hidden ones included. */
    val investments: List<GroupTotal> = emptyList(),
    /** Spent (and invested) on credit cards this month. Part of [outMinor] / [investedMinor], not of the bank balance. */
    val cardSpentMinor: Long = 0,
    /** Refunds and cashback that came back to the cards this month. */
    val cardCreditsMinor: Long = 0,
    /** Card bills paid from the bank this month: they move the bank balance, but are not spending. */
    val cardBillPaidMinor: Long = 0,
    /** This month's money in and out of the bank (cards left out, card bills paid included): the running balance at the end. */
    val bankMinor: Long = 0,
) {
    /** The bank balance for the month: money into the bank minus what left it (card spends are on the card bill instead). */
    val netMinor: Long get() = bankMinor
    /** Money in minus everything spent and invested, cards included: what this month's money leaves once the card bill is paid. */
    val leftMinor: Long get() = inMinor - outMinor - investedMinor
    /** This month's card use still to be paid on the card bill (card spends minus refunds on the cards). */
    val cardDueMinor: Long get() = (cardSpentMinor - cardCreditsMinor).coerceAtLeast(0)
    val rowCount: Int get() = days.sumOf { it.rows.size }

    enum class Kind {
        /** From a bank account (or cash): moves the balance. */
        BANK,
        /** On a credit card: counts as spending (or a refund) but goes on the card bill, the balance doesn't move. */
        CARD,
        /** A card bill paid from the bank: moves the balance, not spending. */
        CARD_BILL,
    }

    data class Row(
        val txn: TxnLite,
        /** The bank balance for the month, up to and including this row (unchanged by card rows). */
        val balanceMinor: Long,
        val kind: Kind = Kind.BANK,
    ) {
        val isCredit: Boolean get() = txn.type == TxnType.CREDIT
        val isInvestment: Boolean get() = txn.isInvestment
        val onCard: Boolean get() = kind == Kind.CARD
        val isCardBill: Boolean get() = kind == Kind.CARD_BILL
    }

    data class Day(
        val date: LocalDate,
        val inMinor: Long,
        /** Spent this day (investments not included), cards included. */
        val outMinor: Long,
        /** Bank balance at the end of this day. */
        val closingMinor: Long,
        /** Newest first. */
        val rows: List<Row>,
        val investedMinor: Long = 0,
        /** Of [outMinor], how much was on cards. */
        val cardOutMinor: Long = 0,
        /** Card bills paid from the bank this day. */
        val cardBillMinor: Long = 0,
    )

    companion object {
        private fun tail4(s: String?): String? = s?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 }

        /** True when [t] is on one of the credit cards in [cardTails] (last 4 digits). */
        fun isOnCard(t: TxnLite, cardTails: Set<String>): Boolean = cardTails.isNotEmpty() && tail4(t.account) in cardTails

        fun build(
            txns: List<TxnLite>,
            period: YearMonth,
            cycle: MonthCycle,
            zone: ZoneId,
            hiddenIds: Set<Long> = emptySet(),
            /** Last 4 digits of the family's credit cards. */
            cardTails: Set<String> = emptySet(),
            /** Card bills paid from a bank account (left out of [txns], as they aren't spending). */
            cardBillPayments: List<TxnLite> = emptyList(),
        ): MonthLedger {
            val bills = cardBillPayments.filter { it.type == TxnType.DEBIT && !isOnCard(it, cardTails) }
            val billIds = bills.mapTo(HashSet()) { it.id }
            val inMonth = (txns.filter { it.id == 0L || it.id !in billIds } + bills)
                .filter { cycle.periodOf(it, zone) == period }
                .sortedWith(compareBy<TxnLite>({ it.timestamp }, { it.id }))
            fun kindOf(t: TxnLite) = when {
                t.id != 0L && t.id in billIds -> Kind.CARD_BILL
                isOnCard(t, cardTails) -> Kind.CARD
                else -> Kind.BANK
            }
            var balance = 0L
            var totalIn = 0L
            var totalOut = 0L
            var totalInvested = 0L
            var cardSpent = 0L
            var cardCredits = 0L
            var billPaid = 0L
            var hidden = 0
            val rows = ArrayList<Row>(inMonth.size)
            val dayIn = HashMap<LocalDate, Long>()
            val dayOut = HashMap<LocalDate, Long>()
            val dayCardOut = HashMap<LocalDate, Long>()
            val dayBill = HashMap<LocalDate, Long>()
            val dayInvested = HashMap<LocalDate, Long>()
            val dayClose = HashMap<LocalDate, Long>()
            for (t in inMonth) {
                val d = Dates.localDate(t.timestamp, zone)
                val kind = kindOf(t)
                val a = t.amountMinor
                when (kind) {
                    Kind.CARD_BILL -> { balance -= a; billPaid += a; dayBill.merge(d, a, Long::plus) }
                    Kind.CARD -> if (t.type == TxnType.CREDIT) {
                        cardCredits += a; totalIn += a; dayIn.merge(d, a, Long::plus)
                    } else {
                        cardSpent += a
                        if (t.isInvestment) { totalInvested += a; dayInvested.merge(d, a, Long::plus) }
                        else { totalOut += a; dayOut.merge(d, a, Long::plus); dayCardOut.merge(d, a, Long::plus) }
                    }
                    Kind.BANK -> if (t.type == TxnType.CREDIT) {
                        balance += a; totalIn += a; dayIn.merge(d, a, Long::plus)
                    } else {
                        balance -= a
                        if (t.isInvestment) { totalInvested += a; dayInvested.merge(d, a, Long::plus) }
                        else { totalOut += a; dayOut.merge(d, a, Long::plus) }
                    }
                }
                // Day totals include hidden rows, so the days add up to the month.
                dayClose[d] = balance
                if (t.id != 0L && t.id in hiddenIds) hidden++ else rows += Row(t, balance, kind)
            }
            val days = rows.groupBy { Dates.localDate(it.txn.timestamp, zone) }
                .map { (d, r) ->
                    Day(d, dayIn[d] ?: 0, dayOut[d] ?: 0, dayClose[d] ?: 0, r.reversed(), dayInvested[d] ?: 0, dayCardOut[d] ?: 0, dayBill[d] ?: 0)
                }
                .sortedByDescending { it.date }
            val investments = inMonth.filter { it.isInvestment && kindOf(it) != Kind.CARD_BILL }
                .groupBy { it.merchant?.takeIf { m -> m.isNotBlank() } ?: it.category }
                .map { (k, v) -> GroupTotal(k, v.sumOf { it.amountMinor }, v.size) }
                .sortedByDescending { it.totalMinor }
            return MonthLedger(period, totalIn, totalOut, days, hidden, totalInvested, investments, cardSpent, cardCredits, billPaid, balance)
        }
    }
}
