package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId

/** One card line as the bill view needs it. */
data class CardLine(val tail: String?, val type: TxnType, val amountMinor: Long, val timestamp: Long, val billPayment: Boolean)

/**
 * A card's bills by its statement day. Spending still counts in the month it was made; this only says
 * what is on the last bill, what will be on the next one, and whether the last one was paid.
 */
data class CardCycleView(
    val card: MyAccount,
    /** The last statement date (on or before today). */
    val billDate: LocalDate,
    /** Spends minus refunds in the cycle that bill covers (previous bill day + 1 to [billDate]). */
    val billedMinor: Long,
    /** Spends since [billDate], for the next bill. */
    val unbilledMinor: Long,
    /** The user's pay-by date for the last bill, when a pay day is set. */
    val payBy: LocalDate?,
    /** A payment reached the card after the bill date. */
    val paid: Boolean,
    /** The next statement date (after today). */
    val nextBillDate: LocalDate = billDate.plusMonths(1),
)

/** A card due alert to send once: [key] tells it apart from every other. */
data class CardAlert(val key: String, val title: String, val text: String)

object CardCycles {
    private fun onDay(year: Int, month: Int, day: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        return first.withDayOfMonth(minOf(day, first.lengthOfMonth()))
    }

    /** The latest statement date on or before [today]. */
    fun lastBillDate(billDay: Int, today: LocalDate): LocalDate {
        val thisMonth = onDay(today.year, today.monthValue, billDay)
        return if (!thisMonth.isAfter(today)) thisMonth else today.minusMonths(1).let { onDay(it.year, it.monthValue, billDay) }
    }

    /** The pay-by date for a bill made on [billDate]: the same month when the pay day is after the bill day, else the next. */
    fun payBy(billDate: LocalDate, payDay: Int): LocalDate =
        if (payDay > billDate.dayOfMonth) onDay(billDate.year, billDate.monthValue, payDay)
        else billDate.plusMonths(1).let { onDay(it.year, it.monthValue, payDay) }

    fun view(card: MyAccount, lines: List<CardLine>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): CardCycleView? {
        val day = card.billDay ?: return null
        val bill = lastBillDate(day, today)
        val prev = lastBillDate(day, bill.minusDays(1))
        val mine = lines.filter { it.tail?.filter(Char::isDigit)?.takeLast(4) == card.tail }
        fun date(l: CardLine) = Dates.localDate(l.timestamp, zone)
        fun net(l: List<CardLine>) = l.filter { !it.billPayment }.sumOf { if (it.type == TxnType.DEBIT) it.amountMinor else -it.amountMinor }
        val billed = net(mine.filter { date(it).isAfter(prev) && !date(it).isAfter(bill) })
        val unbilled = net(mine.filter { date(it).isAfter(bill) })
        val paid = mine.any { it.billPayment && it.type == TxnType.CREDIT && date(it).isAfter(bill) }
        return CardCycleView(card, bill, billed.coerceAtLeast(0), unbilled.coerceAtLeast(0), card.payDay?.let { payBy(bill, it) }, paid, nextBillDate(day, today))
    }

    /** The first statement date after [today]. */
    fun nextBillDate(billDay: Int, today: LocalDate): LocalDate {
        val thisMonth = onDay(today.year, today.monthValue, billDay)
        return if (thisMonth.isAfter(today)) thisMonth else today.plusMonths(1).let { onDay(it.year, it.monthValue, billDay) }
    }

    private val dm = java.time.format.DateTimeFormatter.ofPattern("d MMM")

    /**
     * Card due alerts for [today]:
     * - [daysBefore] days before the bill date up to the day before it: what is on the coming bill so far;
     * - on the bill date and the day after: the bill's amount and its pay-by date, while not paid.
     * The pay-by reminder ([reminderDue]) is separate and stays as it is.
     */
    fun alerts(v: CardCycleView, today: LocalDate, daysBefore: Int = 3): List<CardAlert> {
        val out = ArrayList<CardAlert>()
        val whose = v.card.owner?.takeIf { it.isNotBlank() }?.let { " ($it's card)" } ?: ""
        val next = v.nextBillDate
        if (v.unbilledMinor > 0 && !today.isBefore(next.minusDays(daysBefore.toLong())) && today.isBefore(next)) {
            out += CardAlert(
                "cardpre|${v.card.tail}|$next",
                "${v.card.label} bill on ${next.format(dm)}: ${formatInr(v.unbilledMinor)} so far",
                "Spends since the ${v.billDate.format(dm)} bill, less refunds$whose. Anything you spend before ${next.format(dm)} goes on this bill too." +
                    (v.card.payDay?.let { " Pay by ${payBy(next, it).format(dm)}." } ?: ""),
            )
        }
        if (!v.paid && v.billedMinor > 0 && !today.isBefore(v.billDate) && !today.isAfter(v.billDate.plusDays(1))) {
            out += CardAlert(
                "cardmade|${v.card.tail}|${v.billDate}",
                "${v.card.label} bill: ${formatInr(v.billedMinor)} due" + (v.payBy?.let { " by ${it.format(dm)}" } ?: ""),
                "The ${v.billDate.format(dm)} bill has ${formatInr(v.billedMinor)} of spends, less refunds$whose. Import the card statement to see the bank's exact total.",
            )
        }
        return out
    }

    /** Remind from 3 days before the pay-by date until it, when the bill has something on it and isn't paid. */
    fun reminderDue(v: CardCycleView, today: LocalDate): Boolean {
        val by = v.payBy ?: return false
        return !v.paid && v.billedMinor > 0 && !today.isBefore(by.minusDays(3)) && !today.isAfter(by)
    }
}
