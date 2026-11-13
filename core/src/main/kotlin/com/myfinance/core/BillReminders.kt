package com.myfinance.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class ReminderKind {
    ELECTRICITY, MOBILE, INTERNET, INSURANCE, SCHOOL_FEES, RENT, CREDIT_CARD, LOAN_EMI, SUBSCRIPTION, OTHER,
    WATER, GAS, PROPERTY_TAX, VEHICLE, MEMBERSHIP;

    /** Usually paid once a year: these get an early heads-up a month before. */
    val usuallyYearly: Boolean get() = this == INSURANCE || this == PROPERTY_TAX || this == VEHICLE || this == MEMBERSHIP
}

/**
 * A bill or payment reminder. [everyMonths] > 0 repeats every that many months (1 = monthly, 3 = quarterly,
 * 12 = yearly); 0 means only once; a negative value repeats every that many days (-1 daily, -7 weekly, -14 fortnightly).
 */
data class BillReminder(
    val id: String,
    val name: String,
    val kind: ReminderKind,
    val amountMinor: Long?,
    val nextDue: LocalDate,
    val everyMonths: Int = 1,
    /** Remind this many days before the due date. */
    val remindDaysBefore: Int = 3,
    /** Merchant words that identify the payment, e.g. "bescom", so paying it marks the bill paid. */
    val matchWords: List<String> = emptyList(),
    /** A one-time reminder that was paid: nothing more is due. */
    val done: Boolean = false,
    /** A repeating payment's last due date (an EMI's last instalment, a subscription cancelled): nothing is due after it. */
    val endDate: LocalDate? = null,
) {
    val everyDays: Int get() = if (everyMonths < 0) -everyMonths else 0
    val oneTime: Boolean get() = everyMonths == 0
    /** Nothing more is due: a paid one-time reminder, or a repeating one whose next date is past its end date. */
    val finished: Boolean get() = done || (!oneTime && endDate != null && nextDue.isAfter(endDate))
}

/** The repeat choices offered when adding a reminder: stored value to label. */
val reminderRepeats: List<Pair<Int, String>> = listOf(
    0 to "Only once", -1 to "Every day", -7 to "Every week", -14 to "Every 2 weeks", 1 to "Every month",
    2 to "Every 2 months", 3 to "Every quarter", 6 to "Every 6 months", 12 to "Every year",
)

fun repeatLabel(everyMonths: Int): String = reminderRepeats.firstOrNull { it.first == everyMonths }?.second
    ?: if (everyMonths < 0) "Every ${-everyMonths} days" else "Every $everyMonths months"

enum class BillState { PAID_AHEAD, UPCOMING, DUE_SOON, DUE_TODAY, OVERDUE }

data class BillStatus(val bill: BillReminder, val daysLeft: Long, val state: BillState)

object BillReminders {
    fun status(bill: BillReminder, today: LocalDate): BillStatus {
        val days = ChronoUnit.DAYS.between(today, bill.nextDue)
        val state = when {
            days < 0 -> BillState.OVERDUE
            days == 0L -> BillState.DUE_TODAY
            days <= bill.remindDaysBefore -> BillState.DUE_SOON
            bill.finished -> BillState.PAID_AHEAD
            bill.everyMonths > 0 && days > 31L * bill.everyMonths -> BillState.PAID_AHEAD
            bill.everyDays > 0 && days > bill.everyDays -> BillState.PAID_AHEAD
            else -> BillState.UPCOMING
        }
        return BillStatus(bill, days, state)
    }

    /** Bills to alert about today, most urgent first. */
    fun needingAttention(bills: List<BillReminder>, today: LocalDate): List<BillStatus> =
        bills.filterNot { it.finished }.map { status(it, today) }
            .filter { it.state == BillState.OVERDUE || it.state == BillState.DUE_TODAY || it.state == BillState.DUE_SOON }
            .sortedBy { it.daysLeft }

    /**
     * Moves the bill to its next cycle after a payment. Keeps the original day of month where possible. Paying
     * the last one before the end date leaves the bill [BillReminder.finished].
     */
    fun markPaid(bill: BillReminder): BillReminder = when {
        bill.oneTime -> bill.copy(done = true)
        bill.everyDays > 0 -> bill.copy(nextDue = next(bill, bill.nextDue))
        else -> bill.copy(nextDue = bill.nextDue.plusMonths(bill.everyMonths.toLong()))
    }

    /** How many payments are left from the next one up to the end date; null without an end date. */
    fun remaining(bill: BillReminder): Int? {
        val end = bill.endDate ?: return null
        if (bill.finished) return 0
        return dueDates(bill, bill.nextDue, end).size
    }

    /** The due date after [from]; [from] itself for a one-time reminder. */
    fun next(bill: BillReminder, from: LocalDate): LocalDate = when {
        bill.everyDays > 0 -> from.plusDays(bill.everyDays.toLong())
        bill.everyMonths > 0 -> from.plusMonths(bill.everyMonths.toLong())
        else -> from
    }

    /** Every due date of [bill] from [from] to [to] (inclusive), from its next due date on. Empty once a one-time reminder is paid. */
    fun dueDates(bill: BillReminder, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (bill.finished) return emptyList()
        if (bill.oneTime) return listOf(bill.nextDue).filter { !it.isBefore(from) && !it.isAfter(to) }
        val until = bill.endDate?.let { minOf(it, to) } ?: to
        val out = ArrayList<LocalDate>()
        var n = 0L
        while (n < 5000) {
            val d = if (bill.everyDays > 0) bill.nextDue.plusDays(n * bill.everyDays) else bill.nextDue.plusMonths(n * bill.everyMonths)
            if (d.isAfter(until)) break
            if (!d.isBefore(from)) out += d
            n++
        }
        return out
    }

    /**
     * True when a debit looks like the payment of [bill]: a matching word in the merchant/SMS, within 10 days of the
     * due date, and (if the bill has a fixed amount) within 15% of it.
     */
    fun matchesPayment(bill: BillReminder, merchant: String?, smsBody: String?, amountMinor: Long, paidOn: LocalDate): Boolean {
        if (bill.finished) return false
        // No words set: the name's own words ("School fees" finds "SCHOOL"), with the amount to confirm it.
        val words = bill.matchWords.ifEmpty { nameWords(bill.name) }
        if (words.isEmpty() || (bill.matchWords.isEmpty() && bill.amountMinor == null)) return false
        val hay = listOfNotNull(merchant, smsBody).joinToString(" ").lowercase()
        if (words.none { it.isNotBlank() && hay.contains(it.lowercase().trim()) }) return false
        if (kotlin.math.abs(ChronoUnit.DAYS.between(paidOn, bill.nextDue)) > 10) return false
        val expected = bill.amountMinor ?: return true
        return kotlin.math.abs(amountMinor - expected) * 100 <= expected * 15
    }

    private val skipWords = setOf("bill", "bills", "payment", "fees", "fee", "monthly", "yearly", "annual", "renewal", "premium", "the", "for", "and", "due")

    fun nameWords(name: String): List<String> =
        name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in skipWords }

    /** Yearly-or-rarer dues between a month and their usual reminder away: they get an early heads-up. */
    fun headsUp(bills: List<BillReminder>, today: LocalDate, daysAhead: Int = 30): List<BillReminder> =
        bills.filter { b ->
            val days = ChronoUnit.DAYS.between(today, b.nextDue)
            !b.finished && (b.everyMonths >= 12 || (b.oneTime && b.kind.usuallyYearly)) && days in (b.remindDaysBefore + 1).toLong()..daysAhead.toLong()
        }

    /** Suggested match words for a new bill of this kind. */
    fun defaultWords(kind: ReminderKind): List<String> = when (kind) {
        ReminderKind.ELECTRICITY -> listOf("electricity", "bescom", "tneb", "tangedco", "msedcl", "tata power", "adani electricity", "kseb", "bses")
        ReminderKind.MOBILE -> listOf("airtel", "jio", "vodafone", "vi ", "bsnl", "recharge", "postpaid")
        ReminderKind.INTERNET -> listOf("act fibernet", "broadband", "hathway", "airtel xstream", "jiofiber")
        ReminderKind.INSURANCE -> listOf("insurance", "lic", "premium", "policy")
        ReminderKind.SCHOOL_FEES -> listOf("school", "fees", "tuition", "college")
        ReminderKind.RENT -> listOf("rent", "nobroker")
        ReminderKind.CREDIT_CARD -> listOf("credit card", "cc payment", "card payment")
        ReminderKind.LOAN_EMI -> listOf("emi", "loan")
        ReminderKind.SUBSCRIPTION -> listOf("netflix", "hotstar", "spotify", "prime", "youtube")
        ReminderKind.OTHER -> emptyList()
        ReminderKind.WATER -> listOf("water", "bwssb", "jal board", "metrowater")
        ReminderKind.GAS -> listOf("indane", "hp gas", "bharat gas", "mahanagar gas", "igl", "gail gas", "piped gas", "lpg")
        ReminderKind.PROPERTY_TAX -> listOf("property tax", "bbmp", "corporation", "municipal")
        ReminderKind.VEHICLE -> listOf("vehicle", "motor insurance", "car insurance", "bike insurance", "rto", "fastag", "puc")
        ReminderKind.MEMBERSHIP -> listOf("membership", "club", "society")
    }
}
