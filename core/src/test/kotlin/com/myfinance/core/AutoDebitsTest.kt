package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoDebitsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(d: LocalDate): Long = d.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun debit(d: LocalDate, amount: Long, merchant: String?, sms: String? = null, category: String = "Other") =
        DebitRecord(TxnLite(amount, TxnType.DEBIT, category, merchant, "HDFC ••1234", at(d)), sms)

    private val plan = AutoDebitPlan(
        id = "p1", name = "Bajaj home loan", kind = AutoDebitKind.EMI, amountMinor = 2_500_000,
        firstDue = LocalDate.of(2026, 6, 5), everyMonths = 1, endDate = LocalDate.of(2027, 5, 5),
    )

    @Test
    fun classifiesKinds() {
        assertEquals(AutoDebitKind.EMI, AutoDebits.classify("BAJAJ FINANCE", "NACH debit for loan EMI", null))
        assertEquals(AutoDebitKind.SIP, AutoDebits.classify("ZERODHA", "SIP of Rs 5000 debited", null))
        assertEquals(AutoDebitKind.INSURANCE, AutoDebits.classify("LIC OF INDIA", "premium paid", null))
        assertEquals(AutoDebitKind.SUBSCRIPTION, AutoDebits.classify("NETFLIX", null, null))
        assertEquals(AutoDebitKind.AUTO_DEBIT, AutoDebits.classify("ACME", "Rs 500 debited via e-mandate UMRN 123", null))
        assertTrue(AutoDebits.isMandate("Your a/c debited by NACH-DR for ACME"))
    }

    @Test
    fun planShowsNextUnpaidCycleAndRemaining() {
        val debits = listOf(
            debit(LocalDate.of(2026, 10, 5), 2_500_000, "BAJAJ FINANCE", "NACH debit Bajaj Finance"),
            debit(LocalDate.of(2026, 11, 4), 2_500_000, "BAJAJ FINANCE", "NACH debit Bajaj Finance"),
        )
        val item = assertNotNull(AutoDebits.planItem(plan, debits, LocalDate.of(2026, 11, 9), zone))
        assertEquals(LocalDate.of(2026, 12, 5), item.nextDate)
        assertEquals(LocalDate.of(2026, 11, 4), item.lastPaid)
        assertEquals(6, item.remaining) // Dec..May
        assertEquals(DueState.UPCOMING, item.state)
    }

    @Test
    fun unpaidRecentCycleIsOverdue() {
        val item = assertNotNull(AutoDebits.planItem(plan, emptyList(), LocalDate.of(2026, 11, 8), zone))
        assertEquals(LocalDate.of(2026, 11, 5), item.nextDate)
        assertEquals(DueState.OVERDUE, item.state)
    }

    @Test
    fun endedPlanDisappears() {
        assertNull(AutoDebits.planItem(plan, emptyList(), LocalDate.of(2027, 6, 20), zone))
    }

    @Test
    fun singleMandateDebitIsNotRecurring() {
        val today = LocalDate.of(2026, 11, 9)
        val items = AutoDebits.mandateSeries(listOf(debit(LocalDate.of(2026, 10, 28), 150_000, "ACME LOANS", "Rs 1500 debited via ECS")), today, zone)
        assertTrue(items.isEmpty())
    }

    @Test
    fun repeatedMandateDebitIsMonthly() {
        val today = LocalDate.of(2026, 11, 9)
        val sms = "Rs 1500 debited via ECS"
        val items = AutoDebits.mandateSeries(
            listOf(debit(LocalDate.of(2026, 9, 28), 150_000, "ACME LOANS", sms), debit(LocalDate.of(2026, 10, 28), 150_000, "ACME LOANS", sms)),
            today, zone,
        )
        val i = items.single()
        assertEquals(LocalDate.of(2026, 11, 28), i.nextDate)
        assertEquals(2, i.occurrences)
    }

    @Test
    fun mandateDebitsWithDifferentAmountsOrOddGapsAreNotRecurring() {
        val today = LocalDate.of(2026, 11, 9)
        val sms = "Rs debited via UPI autopay mandate"
        val amounts = AutoDebits.mandateSeries(
            listOf(debit(LocalDate.of(2026, 9, 28), 40_000, "SWIGGY", sms), debit(LocalDate.of(2026, 10, 28), 150_000, "SWIGGY", sms)),
            today, zone,
        )
        assertTrue(amounts.isEmpty())
        val gaps = AutoDebits.mandateSeries(
            listOf(debit(LocalDate.of(2026, 10, 20), 50_000, "ZOMATO", sms), debit(LocalDate.of(2026, 10, 28), 50_000, "ZOMATO", sms)),
            today, zone,
        )
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun familyUpiPaymentsAreNotAutoDebits() {
        val today = LocalDate.of(2026, 11, 9)
        val circle = "Rs 500 debited from A/c XX1234 via UPI Circle autopay mandate by Priya"
        val debits = listOf(debit(LocalDate.of(2026, 9, 28), 50_000, "CANTEEN", circle), debit(LocalDate.of(2026, 10, 28), 50_000, "CANTEEN", circle))
        assertTrue(AutoDebits.mandateSeries(debits, today, zone).isEmpty())
        assertTrue(AutoDebits.isFamilyUpi(debits[0]))
        // A label with "family" in it marks it too, and keeps it out of the recurring rows.
        val labelled = listOf(
            DebitRecord(TxnLite(50_000, TxnType.DEBIT, "Other", "BOOK STORE", null, at(LocalDate.of(2026, 10, 28))), "autopay mandate", listOf("Family UPI")),
        )
        val rec = RecurringPayment("BOOK STORE", "Other", 50_000, Cadence.MONTHLY, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 28), 3)
        assertTrue(AutoDebits.build(emptyList(), labelled, listOf(rec), emptyList(), emptySet(), today, zone).isEmpty())
    }

    @Test
    fun notRecurringPayeeStaysOff() {
        val today = LocalDate.of(2026, 11, 9)
        val rec = RecurringPayment("Gym Club", "Other", 100_000, Cadence.MONTHLY, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 28), 3)
        val shown = AutoDebits.build(emptyList(), emptyList(), listOf(rec), emptyList(), emptySet(), today, zone)
        val key = AutoDebits.payeeKey(shown.single())!!
        assertTrue(AutoDebits.build(emptyList(), emptyList(), listOf(rec), emptyList(), emptySet(), today, zone, notRecurring = setOf(key)).isEmpty())
    }

    @Test
    fun buildDropsDetectedDuplicatesAndHidden() {
        val today = LocalDate.of(2026, 11, 9)
        val debits = listOf(
            debit(LocalDate.of(2026, 10, 5), 2_500_000, "BAJAJ FINANCE", "NACH debit Bajaj Finance"),
            debit(LocalDate.of(2026, 11, 4), 2_500_000, "BAJAJ FINANCE", "NACH debit Bajaj Finance"),
            debit(LocalDate.of(2026, 10, 1), 64_900, "NETFLIX", "autopay Netflix"),
            debit(LocalDate.of(2026, 11, 1), 64_900, "NETFLIX", "autopay Netflix"),
        )
        val all = AutoDebits.build(listOf(plan), debits, emptyList(), emptyList(), emptySet(), today, zone)
        // The Bajaj NACH debits belong to the plan, so only Netflix is added as detected.
        assertEquals(setOf("plan:p1", "nach:netflix"), all.map { it.key }.toSet())
        val hidden = AutoDebits.build(listOf(plan), debits, emptyList(), emptyList(), setOf("nach:netflix"), today, zone)
        assertEquals(listOf("plan:p1"), hidden.map { it.key })
    }

    @Test
    fun emiBillFromBankIsListed() {
        val today = LocalDate.of(2026, 11, 9)
        val bill = BillDue(BillKind.EMI, 525_000, null, LocalDate.of(2026, 12, 5), "7788", "HDFC Bank")
        val items = AutoDebits.build(emptyList(), emptyList(), emptyList(), listOf(bill), emptySet(), today, zone)
        assertEquals(AutoDebitSource.BANK_ALERT, items.single().source)
        assertEquals(26, items.single().daysLeft)
    }
}
