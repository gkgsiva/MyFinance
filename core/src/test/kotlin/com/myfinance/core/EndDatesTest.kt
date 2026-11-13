package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End dates on repeating payments, and EMI end dates read from statements. Made-up data. */
class EndDatesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ts(d: LocalDate) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun debit(d: LocalDate, amount: Long, narration: String, account: String = "••5010") =
        DebitRecord(TxnLite(amount, TxnType.DEBIT, DefaultCategories.OTHER, Narration.merchant(narration), account, ts(d)), narration)

    @Test fun reminderStopsAfterItsEndDate() {
        val emi = BillReminder("b", "Bike loan", ReminderKind.LOAN_EMI, 4_500_00, LocalDate.of(2026, 11, 5), endDate = LocalDate.of(2027, 1, 5))
        val from = LocalDate.of(2026, 11, 1)
        assertEquals(
            listOf(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 5), LocalDate.of(2027, 1, 5)),
            BillReminders.dueDates(emi, from, LocalDate.of(2027, 6, 30)),
        )
        assertEquals(3, BillReminders.remaining(emi))
        // Paying the last one finishes it: no more reminders, calendar entries or alerts.
        val last = BillReminders.markPaid(BillReminders.markPaid(BillReminders.markPaid(emi)))
        assertTrue(last.finished)
        assertEquals(0, BillReminders.remaining(last))
        assertTrue(BillReminders.dueDates(last, from, LocalDate.of(2027, 12, 31)).isEmpty())
        assertTrue(BillReminders.needingAttention(listOf(last), LocalDate.of(2027, 2, 6)).isEmpty())
        val cal = CashFlowCalendar.build(YearMonth.of(2027, 2), LocalDate.of(2027, 2, 1), emptyList(), bills = listOf(last), zone = zone)
        assertTrue(cal.days.none { d -> d.events.any { it.title == "Bike loan" } })
    }

    @Test fun yearlyDueAfterEndIsNotListed() {
        val sub = BillReminder("s", "Gym membership", ReminderKind.MEMBERSHIP, 12_000_00, LocalDate.of(2027, 3, 1), everyMonths = 12, endDate = LocalDate.of(2027, 2, 1))
        assertTrue(sub.finished)
        assertTrue(AnnualDues.list(listOf(sub), emptyList(), LocalDate.of(2026, 11, 13)).isEmpty())
        assertTrue(BillReminders.headsUp(listOf(sub.copy(nextDue = LocalDate.of(2026, 12, 1))), LocalDate.of(2026, 11, 13)).isNotEmpty())
    }

    @Test fun planWithoutEndTakesItFromStatementInstalments() {
        val start = LocalDate.of(2026, 7, 20)
        val debits = (1..4).map { k ->
            debit(start.plusMonths(k - 1L), 5_000_00, "Principal Amount Amortization - <$k/6>PHONE STORE")
        }
        val today = LocalDate.of(2026, 11, 13)
        val plan = AutoDebitPlan("p", "Phone EMI", AutoDebitKind.EMI, 5_000_00, LocalDate.of(2026, 11, 20), account = "••5010")
        val items = AutoDebits.build(listOf(plan), debits, emptyList(), emptyList(), emptySet(), today, zone)
        val item = items.single { it.key == "plan:p" }
        assertEquals(LocalDate.of(2026, 12, 20), item.endDate)
        assertTrue(item.endInferred)
        assertEquals(2, item.remaining)
        assertEquals(6, item.instalmentsTotal)
        assertEquals(mapOf("p" to LocalDate.of(2026, 12, 20)), RepeatEnds.forPlans(listOf(plan), AutoDebits.instalmentEmis(debits, today, zone)))
        // The statement EMI itself is not listed twice.
        assertEquals(1, items.count { it.kind == AutoDebitKind.EMI })
        // Once the last date is well past, nothing more is due.
        val later = AutoDebits.build(listOf(plan.copy(endDate = LocalDate.of(2027, 1, 20))), emptyList(), emptyList(), emptyList(), emptySet(), LocalDate.of(2027, 3, 1), zone)
        assertTrue(later.none { it.key == "plan:p" })
    }

    @Test fun loanReminderEndAlignsToItsOwnDueDay() {
        val start = LocalDate.of(2026, 9, 14)
        val debits = (1..3).map { k -> debit(start.plusMonths(k - 1L), 2_000_00, "Principal Amount Amortization - <$k/5>LAPTOP") }
        val emis = AutoDebits.instalmentEmis(debits, LocalDate.of(2026, 11, 20), zone)
        // Paid with the card bill on the 2nd: the last instalment (14 Jan) is paid on 2 Feb.
        val bill = BillReminder("r", "Laptop EMI", ReminderKind.LOAN_EMI, 2_000_00, LocalDate.of(2026, 12, 2))
        assertEquals(mapOf("r" to LocalDate.of(2027, 2, 2)), RepeatEnds.forBills(listOf(bill), emis))
        // A different amount and name: no guess.
        assertTrue(RepeatEnds.forBills(listOf(bill.copy(name = "Car loan", amountMinor = 15_000_00)), emis).isEmpty())
        // Has its own end date already: left alone.
        assertTrue(RepeatEnds.forBills(listOf(bill.copy(endDate = LocalDate.of(2027, 5, 2))), emis).isEmpty())
        assertNull(RepeatEnds.emiFor("Laptop EMI", null, null, emptyList(), emptyList()))
    }
}
