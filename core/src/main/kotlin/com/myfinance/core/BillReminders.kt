package com.myfinance.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class BillKind { ELECTRICITY, MOBILE, INTERNET, INSURANCE, SCHOOL_FEES, RENT, CREDIT_CARD, LOAN_EMI, SUBSCRIPTION, OTHER }

/** A bill that repeats every [everyMonths] months (1 = monthly, 3 = quarterly, 12 = yearly). */
data class Bill(
    val id: String,
    val name: String,
    val kind: BillKind,
    val amountMinor: Long?,
    val nextDue: LocalDate,
    val everyMonths: Int = 1,
    /** Remind this many days before the due date. */
    val remindDaysBefore: Int = 3,
    /** Merchant words that identify the payment, e.g. "bescom", so paying it marks the bill paid. */
    val matchWords: List<String> = emptyList(),
)

enum class BillState { PAID_AHEAD, UPCOMING, DUE_SOON, DUE_TODAY, OVERDUE }

data class BillStatus(val bill: Bill, val daysLeft: Long, val state: BillState)

object BillReminders {
    fun status(bill: Bill, today: LocalDate): BillStatus {
        val days = ChronoUnit.DAYS.between(today, bill.nextDue)
        val state = when {
            days < 0 -> BillState.OVERDUE
            days == 0L -> BillState.DUE_TODAY
            days <= bill.remindDaysBefore -> BillState.DUE_SOON
            days > 31L * bill.everyMonths -> BillState.PAID_AHEAD
            else -> BillState.UPCOMING
        }
        return BillStatus(bill, days, state)
    }

    /** Bills to alert about today, most urgent first. */
    fun needingAttention(bills: List<Bill>, today: LocalDate): List<BillStatus> =
        bills.map { status(it, today) }
            .filter { it.state == BillState.OVERDUE || it.state == BillState.DUE_TODAY || it.state == BillState.DUE_SOON }
            .sortedBy { it.daysLeft }

    /** Moves the bill to its next cycle after a payment. Keeps the original day of month where possible. */
    fun markPaid(bill: Bill): Bill = bill.copy(nextDue = bill.nextDue.plusMonths(bill.everyMonths.toLong().coerceAtLeast(1)))

    /**
     * True when a debit looks like the payment of [bill]: a matching word in the merchant/SMS, within 10 days of the
     * due date, and (if the bill has a fixed amount) within 15% of it.
     */
    fun matchesPayment(bill: Bill, merchant: String?, smsBody: String?, amountMinor: Long, paidOn: LocalDate): Boolean {
        if (bill.matchWords.isEmpty()) return false
        val hay = listOfNotNull(merchant, smsBody).joinToString(" ").lowercase()
        if (bill.matchWords.none { it.isNotBlank() && hay.contains(it.lowercase().trim()) }) return false
        if (kotlin.math.abs(ChronoUnit.DAYS.between(paidOn, bill.nextDue)) > 10) return false
        val expected = bill.amountMinor ?: return true
        return kotlin.math.abs(amountMinor - expected) * 100 <= expected * 15
    }

    /** Suggested match words for a new bill of this kind. */
    fun defaultWords(kind: BillKind): List<String> = when (kind) {
        BillKind.ELECTRICITY -> listOf("electricity", "bescom", "tneb", "tangedco", "msedcl", "tata power", "adani electricity", "kseb", "bses")
        BillKind.MOBILE -> listOf("airtel", "jio", "vodafone", "vi ", "bsnl", "recharge", "postpaid")
        BillKind.INTERNET -> listOf("act fibernet", "broadband", "hathway", "airtel xstream", "jiofiber")
        BillKind.INSURANCE -> listOf("insurance", "lic", "premium", "policy")
        BillKind.SCHOOL_FEES -> listOf("school", "fees", "tuition", "college")
        BillKind.RENT -> listOf("rent", "nobroker")
        BillKind.CREDIT_CARD -> listOf("credit card", "cc payment", "card payment")
        BillKind.LOAN_EMI -> listOf("emi", "loan")
        BillKind.SUBSCRIPTION -> listOf("netflix", "hotstar", "spotify", "prime", "youtube")
        BillKind.OTHER -> emptyList()
    }
}
