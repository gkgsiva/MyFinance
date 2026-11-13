package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardPaymentsTest {
    private val zone = ZoneOffset.UTC
    private fun at(d: LocalDate): Long = d.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private var n = 0L
    private fun line(d: LocalDate, amount: Long, type: TxnType, tail: String, merchant: String?, text: String? = null) =
        PayLine(++n, type, amount, at(d), tail, merchant, text)
    private fun bill(tail: String, amount: Long, due: LocalDate) = BillDue(BillKind.CREDIT_CARD, amount, null, due, tail, "ICICI")
    private val cards = setOf("5010", "5002")

    @Test fun bankDebitMatchedWithCardCreditEachMonth() {
        val lines = listOf(
            line(LocalDate.of(2026, 9, 27), 54_121_00, TxnType.DEBIT, "XX144", "ICICI Credit Card", "BIL/ONL/000123/ICICI Bank Credit Card XX5010"),
            line(LocalDate.of(2026, 9, 28), 54_121_00, TxnType.CREDIT, "5010", "Payment received - thank you"),
            line(LocalDate.of(2026, 10, 26), 40_000_00, TxnType.DEBIT, "XX144", "CC payment 5010", "CC payment to card 5010"),
            line(LocalDate.of(2026, 10, 27), 40_000_00, TxnType.CREDIT, "5010", "BBPS payment received"),
            // A purchase on the card is not a payment.
            line(LocalDate.of(2026, 10, 5), 1_200_00, TxnType.DEBIT, "5010", "Swiggy"),
        )
        val bills = listOf(bill("5010", 54_121_00, LocalDate.of(2026, 10, 2)), bill("5010", 45_000_00, LocalDate.of(2026, 11, 1)))
        val months = CardPayments.monthly(lines, cards, bills, LocalDate.of(2026, 11, 12), zone)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), months.map { it.month })
        val sep = months[1]
        assertEquals(CardMonthStatus.PAID, sep.status)
        assertEquals(CardPayMatch.MATCHED, sep.payments.single().match)
        val oct = months[0]
        assertEquals(CardMonthStatus.PART_PAID, oct.status)
        assertEquals(5_000_00, oct.shortMinor)
        assertEquals(CardPayMatch.MATCHED, oct.payments.single().match)
    }

    @Test fun unmatchedSidesAndUnknownCardAreListed() {
        val lines = listOf(
            // Card named in the bank text, but the card statement isn't imported.
            line(LocalDate.of(2026, 8, 25), 10_000_00, TxnType.DEBIT, "XX144", "Credit card payment XX5002"),
            // Card side only.
            line(LocalDate.of(2026, 8, 26), 7_000_00, TxnType.CREDIT, "5010", "Payment received"),
            // Bank debit that doesn't say which card.
            line(LocalDate.of(2026, 8, 20), 3_000_00, TxnType.DEBIT, "XX144", "CRED club"),
            // Cashback on the card is not a payment.
            line(LocalDate.of(2026, 8, 21), 500_00, TxnType.CREDIT, "5010", "Cashback credit"),
        )
        val months = CardPayments.monthly(lines, cards, emptyList(), LocalDate.of(2026, 11, 12), zone)
        assertEquals(3, months.size)
        val byTail = months.associateBy { it.cardTail }
        assertEquals(CardPayMatch.BANK_ONLY, byTail["5002"]!!.payments.single().match)
        assertEquals(CardPayMatch.CARD_ONLY, byTail["5010"]!!.payments.single().match)
        assertEquals(7_000_00, byTail["5010"]!!.paidMinor)
        assertEquals(3_000_00, byTail[null]!!.paidMinor)
        assertEquals(CardMonthStatus.NO_BILL, byTail[null]!!.status)
    }

    @Test fun billWithNoPaymentIsNotPaidOrDue() {
        val bills = listOf(bill("5002", 9_000_00, LocalDate.of(2026, 10, 2)), bill("5002", 8_000_00, LocalDate.of(2026, 12, 2)))
        val months = CardPayments.monthly(emptyList(), cards, bills, LocalDate.of(2026, 11, 12), zone)
        assertEquals(CardMonthStatus.DUE, months.first { it.month == YearMonth.of(2026, 11) }.status)
        assertEquals(CardMonthStatus.NOT_PAID, months.first { it.month == YearMonth.of(2026, 9) }.status)
    }

    @Test fun cardNamedNeedsWholeNumber() {
        val l = PayLine(1, TxnType.DEBIT, 1, 0, "XX144", "Card payment 450100", null)
        assertNull(CardPayments.cardNamed(l, setOf("5010")))
        assertEquals("5010", CardPayments.cardNamed(l.copy(merchant = "Card payment XX5010"), setOf("5010")))
    }
}

class CardReconcileTest {
    private val zone = ZoneOffset.UTC
    private fun at(d: LocalDate): Long = d.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private var n = 0L
    private fun line(d: LocalDate, amount: Long, type: TxnType, tail: String, merchant: String?, balance: Long? = null) =
        PayLine(++n, type, amount, at(d), tail, merchant, null, balance = balance)

    @Test fun cycleFrom15thTo14thMatchesStatementTotal() {
        val lines = listOf(
            line(LocalDate.of(2026, 9, 20), 10_000_00, TxnType.DEBIT, "5010", "Amazon"),
            line(LocalDate.of(2026, 10, 14), 2_000_00, TxnType.DEBIT, "5010", "Swiggy"),
            line(LocalDate.of(2026, 10, 1), 500_00, TxnType.CREDIT, "5010", "Amazon refund"),
            // Next cycle.
            line(LocalDate.of(2026, 10, 15), 3_000_00, TxnType.DEBIT, "5010", "Zepto"),
            line(LocalDate.of(2026, 10, 28), 11_500_00, TxnType.CREDIT, "5010", "Payment received - thank you"),
        )
        val bills = listOf(
            BillDue(BillKind.CREDIT_CARD, 11_500_00, null, LocalDate.of(2026, 11, 1), "5010", "ICICI"),
            BillDue(BillKind.CREDIT_CARD, 3_100_00, null, LocalDate.of(2026, 12, 1), "5010", "ICICI"),
        )
        val c = CardReconcile.cycles(lines, setOf("5010"), bills, emptyMap(), LocalDate.of(2026, 11, 20), zone)
        val oct = c.first { it.to == LocalDate.of(2026, 10, 14) }
        assertEquals(LocalDate.of(2026, 9, 15), oct.from)
        assertEquals(12_000_00, oct.spendsMinor)
        assertEquals(500_00, oct.refundsMinor)
        assertEquals(true, oct.matches)
        val nov = c.first { it.to == LocalDate.of(2026, 11, 14) }
        assertEquals(11_500_00, nov.paymentsMinor)
        // 11,500 − 11,500 + 3,000 = 3,000 against 3,100 on the statement: ₹100 not in the app.
        assertEquals(-100_00, nov.differenceMinor)
        assertEquals(false, nov.matches)
    }

    @Test fun bankBalanceGapShowsMissingEntry() {
        val lines = listOf(
            line(LocalDate.of(2026, 10, 1), 1_000_00, TxnType.CREDIT, "XX144", "x", balance = 10_000_00),
            line(LocalDate.of(2026, 10, 2), 500_00, TxnType.DEBIT, "4144", "y", balance = 9_500_00),
            // 2,000 went out on 3 Oct but isn't stored.
            line(LocalDate.of(2026, 10, 4), 500_00, TxnType.DEBIT, "XX144", "z", balance = 7_000_00),
            line(LocalDate.of(2026, 10, 5), 100_00, TxnType.DEBIT, "XX144", "no balance"),
            line(LocalDate.of(2026, 10, 6), 100_00, TxnType.DEBIT, "XX144", "w", balance = 6_800_00),
        )
        val m = CardReconcile.bankMonths(lines, setOf("5010"), setOf("4144"), zone).single()
        assertEquals("4144", m.accountTail)
        assertEquals(5, m.entries)
        assertEquals(-2_000_00, m.gaps.single().missingMinor)
        assertEquals(LocalDate.of(2026, 10, 4), m.gaps.single().date)
    }
}
