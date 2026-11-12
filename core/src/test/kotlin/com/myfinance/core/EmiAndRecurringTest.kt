package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** EMIs read from card statement instalment lines, and recurring payments by amount and day. Made-up data. */
class EmiAndRecurringTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ts(d: LocalDate) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun debit(d: LocalDate, amount: Long, narration: String, account: String = "••9002") =
        DebitRecord(TxnLite(amount, TxnType.DEBIT, DefaultCategories.OTHER, Narration.merchant(narration), account, ts(d)), narration)

    @Test
    fun instalmentText() {
        assertEquals(4 to 6, AutoDebits.instalmentOf("Principal Amount Amortization - <4/6>"))
        assertEquals(3 to 12, AutoDebits.instalmentOf("BAJAJ FIN EMI 3 of 12"))
        assertEquals(null, AutoDebits.instalmentOf("UPI/123456/Payment 1/2 done"))
    }

    @Test
    fun cardEmiShowsPaidLeftAndEnd() {
        val start = LocalDate.of(2024, 2, 22)
        val debits = listOf(
            debit(start, 1_807_06, "Principal Amount Amortization - <1/6>CHART TOOLS"),
            debit(start, 168_86, "Interest Amount Amortization - <1/6>CHART TOOLS"),
            debit(start.plusMonths(1), 1_834_17, "Principal Amount Amortization - <2/6>CHART TOOLS"),
            debit(start.plusMonths(1), 141_75, "Interest Amount Amortization - <2/6>CHART TOOLS"),
            // A finished EMI on the same card is not listed.
            debit(LocalDate.of(2024, 1, 12), 49_257_44, "Principal Amount Amortization - <1/3>COACHING CLASS"),
            debit(LocalDate.of(2024, 2, 12), 49_996_30, "Principal Amount Amortization - <2/3>COACHING CLASS"),
            debit(LocalDate.of(2024, 3, 12), 50_746_26, "Principal Amount Amortization - <3/3>COACHING CLASS"),
        )
        val today = LocalDate.of(2024, 3, 25)
        val emis = AutoDebits.instalmentEmis(debits, today, zone)
        val e = emis.single()
        assertEquals("Chart Tools EMI", e.name)
        assertEquals(AutoDebitKind.EMI, e.kind)
        assertEquals(1_834_17L + 141_75L, e.amountMinor)
        assertEquals(2, e.instalmentsPaid)
        assertEquals(6, e.instalmentsTotal)
        assertEquals(4, e.remaining)
        assertEquals(LocalDate.of(2024, 4, 22), e.nextDate)
        assertEquals(LocalDate.of(2024, 7, 22), e.endDate)
        assertEquals((1_834_17L + 141_75L) * 4, e.remainingMinor)

        // In the full list it shows once, not again as a recurring "EMI" payment.
        val rec = RecurringDetector.detect(debits.map { it.txn }, today, zone)
        val list = AutoDebits.build(emptyList(), debits, rec, emptyList(), emptySet(), today, zone)
        assertEquals(1, list.count { it.kind == AutoDebitKind.EMI })
    }

    @Test
    fun sameAmountSameDayEachMonthIsRecurring() {
        val today = LocalDate.of(2019, 3, 20)
        // Two EMIs to one lender on the same day, and one-off payments to it in between.
        val txns = (0 until 6).flatMap { i ->
            val d = LocalDate.of(2018, 10, 15).plusMonths(i.toLong())
            listOf(
                debit(d, 30_986_00, "DECS/HDFC LTD EMI DEBITS/3331383860255", "••7788").txn,
                debit(d, 13_160_00, "DECS/HDFC LTD EMI DEBITS/3331387996129", "••7788").txn,
            )
        } + debit(LocalDate.of(2018, 11, 2), 5_000_00, "DECS/HDFC LTD EMI DEBITS/3331383860255", "••7788").txn
        val rec = RecurringDetector.detect(txns, today, zone)
        assertEquals(setOf(30_986_00L, 13_160_00L), rec.map { it.amountMinor }.toSet())
        assertTrue(rec.all { it.cadence == Cadence.MONTHLY && it.nextDate == LocalDate.of(2019, 4, 15) })
        assertEquals(2, rec.map { it.key }.distinct().size)
        val list = AutoDebits.build(emptyList(), txns.map { DebitRecord(it, "DECS/HDFC LTD EMI DEBITS") }, rec, emptyList(), emptySet(), today, zone)
        assertEquals(2, list.count { it.kind == AutoDebitKind.EMI })
    }

    @Test
    fun differentDaysAreNotTheSameAmountSeries() {
        val today = LocalDate.of(2024, 6, 1)
        val days = listOf(LocalDate.of(2024, 1, 3), LocalDate.of(2024, 2, 19), LocalDate.of(2024, 3, 8), LocalDate.of(2024, 4, 27), LocalDate.of(2024, 5, 14))
        val txns = days.map { debit(it, 250_00, "UPI-079108625437-TEA STALL IN").txn }
        assertTrue(RecurringDetector.detect(txns, today, zone).isEmpty())
        assertNotNull(Narration.merchant("UPI-079108625437-TEA STALL IN"))
    }
}
