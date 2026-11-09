package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemindersTest {
    private val zone = ZoneOffset.UTC
    private fun at(d: LocalDate): Long = d.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun weeklyAndOneTimeReminders() {
        val weekly = BillReminder("w", "Maid", ReminderKind.OTHER, 50_000, LocalDate.of(2026, 11, 10), everyMonths = -7)
        assertEquals(
            listOf(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 24)),
            BillReminders.dueDates(weekly, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 30)),
        )
        assertEquals(LocalDate.of(2026, 11, 17), BillReminders.markPaid(weekly).nextDue)
        val once = BillReminder("o", "School trip", ReminderKind.SCHOOL_FEES, 200_000, LocalDate.of(2026, 11, 20), everyMonths = 0)
        val paid = BillReminders.markPaid(once)
        assertTrue(paid.done)
        assertTrue(BillReminders.dueDates(paid, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)).isEmpty())
        assertTrue(BillReminders.needingAttention(listOf(paid), LocalDate.of(2026, 11, 25)).isEmpty())
        assertEquals("Every week", repeatLabel(-7))
    }

    @Test fun calendarShowsRepeatsAndMissed() {
        val weekly = BillReminder("w", "Maid", ReminderKind.OTHER, 50_000, LocalDate.of(2026, 11, 2), everyMonths = -7)
        val m = CashFlowCalendar.build(java.time.YearMonth.of(2026, 11), LocalDate.of(2026, 11, 9), emptyList(), bills = listOf(weekly), zone = zone)
        val events = m.days.flatMap { it.events }
        assertTrue(events.any { it.late && it.ref == "bill:w" })
        assertEquals(4, events.count { !it.late && it.ref == "bill:w" }) // 9, 16, 23, 30
    }

    @Test fun matchesPaymentByNameWhenNoWords() {
        val b = BillReminder("s", "Ryan School fees", ReminderKind.SCHOOL_FEES, 3_000_000, LocalDate.of(2026, 11, 10))
        assertTrue(BillReminders.matchesPayment(b, "RYAN INTERNATIONAL", null, 3_000_000, LocalDate.of(2026, 11, 9)))
    }

    @Test fun utilityBillSms() {
        val u = UtilityBills.parse("AD-BESCOM", "Dear Customer, your BESCOM electricity bill of Rs.1,240.00 for A/c 1234 is due on 25-11-2026. Pay online.", at(LocalDate.of(2026, 11, 9)), zone)
        assertNotNull(u)
        assertEquals(ReminderKind.ELECTRICITY, u.kind)
        assertEquals(124_000, u.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 25), u.dueDate)
        assertNull(UtilityBills.parse("AD-BESCOM", "Thank you for your payment of Rs 1,240 to BESCOM", at(LocalDate.of(2026, 11, 9)), zone))
        val r = BillReminder("e", "Electricity", ReminderKind.ELECTRICITY, null, LocalDate.of(2026, 10, 25))
        assertEquals("e", UtilityBills.matchReminder(u, listOf(r))?.id)
    }

    @Test fun forecastAndPace() {
        val today = LocalDate.of(2026, 11, 9)
        val f = BalanceForecasts.build(
            100_000_00, today, 30,
            listOf(ExpectedFlow(today.plusDays(2), "EMI", -60_000_00), ExpectedFlow(today.plusDays(20), "Salary", 150_000_00)),
            1_000_00,
        )
        assertEquals(today.plusDays(19), f.low?.date)
        assertEquals(100_000_00 - 60_000_00 + 150_000_00 - 30 * 1_000_00, f.endMinor)
        val p = SpendPaces.of(70_000, 100_000, LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        assertEquals(PaceState.AHEAD, p.state)
        assertEquals(PaceState.ON_TRACK, SpendPaces.of(40_000, 100_000, LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).state)
    }

    @Test fun annualDuesAndHeadsUp() {
        val today = LocalDate.of(2026, 11, 9)
        val tax = BillReminder("t", "Property tax", ReminderKind.PROPERTY_TAX, 1_200_000, LocalDate.of(2026, 12, 1), everyMonths = 12)
        val dues = AnnualDues.list(listOf(tax), emptyList(), today)
        assertEquals(1, dues.size)
        assertEquals(1_200_000, dues[0].monthlySetAside(today))
        assertEquals(listOf("t"), BillReminders.headsUp(listOf(tax), today).map { it.id })
    }
}
