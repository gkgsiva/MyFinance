package com.myfinance.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MultiAccountStatementTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private val cards = """
        Customer Name Card Account No
        MR. SIVA G 4629 XXXX XXXX 3004
        Statement Period
        01/04/2023 TO 31/03/2024
        TRANSACTION DETAILS
        Card Number : 4375 XXXX XXXX 4007
        Date Ref. Number Transaction Details Currency Amount(in ` )
        01-APR-23 309173795621 UPI Payment Received 0.00 -10,036.16
        01-MAR-24 74332744063406163599285 GOOGLE PLAY MUMBAI IN 0.00 149.00
        TRANSACTION DETAILS
        Card Number : 4375 XXXX XXXX 5002
        Date Ref. Number Transaction Details Currency Amount(in ` )
        29-MAY-23 74332743150314992333197 AVENUES GRT JEWELLER MUMBAI IN 0.00 12,500.00
        29-MAY-23 74332743150314992333429 AVENUES GRT JEWELLER MUMBAI IN 0.00 12,500.00
        14-APR-23 Annual Fees 0.00 500.00
        TRANSACTION DETAILS
        Card Number : 4375 47XX XXXX 5010
        Date Ref. Number Transaction Details Currency Amount(in ` )
        12-MAR-24 74332744072407231234567 MAKEMYTRIP INDIA GURGAON IN 0.00 3,000.00
        TRANSACTION DETAILS
        Card Number : 4375 XXXX XXXX 5002
        Date Ref. Number Transaction Details Currency Amount(in ` )
        29-MAY-23 74332743150314992333197 AVENUES GRT JEWELLER MUMBAI IN 0.00 12,500.00
        29-MAY-23 74332743150314992333429 AVENUES GRT JEWELLER MUMBAI IN 0.00 12,500.00
        14-APR-23 Annual Fees 0.00 500.00
    """.trimIndent()

    @Test fun eachCardLineKeepsItsCard() {
        val st = StatementParser.fromText(cards)!!
        assertEquals(StatementKind.CARD, st.kind)
        val byCard = st.rows.groupBy { it.cardTail }.mapValues { it.value.size }
        // The repeated 5002 table (same serial numbers) is read once; the two real ₹12,500 lines stay.
        assertEquals(mapOf("4007" to 2, "5002" to 3, "5010" to 1), byCard)
        val c = StatementImport.candidates(st, zone = zone)
        assertEquals(setOf("4007", "5002", "5010"), c.mapNotNull { it.accountTail }.toSet())
        assertEquals(listOf("4007" to 2, "5002" to 3, "5010" to 1), StatementImport.accountsIn(st))
    }

    @Test fun renameOneCard() {
        val st = StatementParser.fromText(cards)!!
        val c = StatementImport.candidates(st, zone = zone, renames = mapOf("5010" to "9999"))
        assertEquals(1, c.count { it.accountTail == "9999" })
        assertEquals(3, c.count { it.accountTail == "5002" })
    }

    @Test fun billPaymentIsNotCountedInPreview() {
        val st = StatementParser.fromText(cards)!!
        val s = StatementImport.summary(StatementImport.candidates(st, zone = zone))
        assertEquals(0L, s.receivedMinor)
        assertEquals(1_003_616L, s.notCountedMinor)
        assertEquals(149_00L + 2 * 12_500_00L + 500_00L + 3_000_00L, s.spentMinor)
    }

    private val bank = """
        ICICI Bank
        Statement of Transactions in Savings Account XXXXXXXX4144 in INR for the period April 01, 2018 - March 31, 2019
        Date Mode Particulars Deposits Withdrawals Balance
        01-04-2018 B/F 1,00,000.00
        03-04-2018 NET BANKING BIL/001422771321/IRCTC 616.80 99,383.20
        05-04-2018 INFT/TO CURRENT A/C XXXXXXXX7788 20,000.00 79,383.20
        09-04-2018 SALARY APR 50,000.00 1,29,383.20
        Statement of Transactions in Current Account XXXXXXXX7788 in INR for the period April 01, 2018 - March 31, 2019
        01-04-2018 B/F 5,000.00
        05-04-2018 INFT/FROM SB A/C XXXXXXXX4144 20,000.00 25,000.00
        07-04-2018 UPI/SWIGGY/1234 450.00 24,550.00
    """.trimIndent()

    @Test fun bankLinesKeepTheirAccountAndTransfersAreFound() {
        val st = StatementParser.fromText(bank)!!
        assertEquals(StatementKind.BANK, st.kind)
        assertEquals(mapOf("4144" to 3, "7788" to 2), st.rows.groupBy { it.cardTail }.mapValues { it.value.size })
        val dirs = st.rows.map { it.description.take(12) to it.type }
        assertTrue(dirs.toString(), st.rows.single { it.description.startsWith("SALARY") }.type == TxnType.CREDIT)
        assertTrue(st.rows.single { it.description.startsWith("UPI/SWIGGY") }.type == TxnType.DEBIT)
        assertTrue(st.check?.ok ?: true)
        val c = StatementImport.markOwnTransfers(StatementImport.candidates(st, zone = zone), setOf("4144", "7788"))
        val xfer = c.filter { it.ownTransfer }
        assertEquals(2, xfer.size)
        assertEquals(setOf(TxnType.DEBIT, TxnType.CREDIT), xfer.map { it.row.type }.toSet())
        val s = StatementImport.summary(c)
        assertEquals(616_80L + 450_00L, s.spentMinor)
        assertEquals(50_000_00L, s.receivedMinor)
    }

    @Test fun narrationNamingAnAccountIsNotAHeading() {
        assertEquals(null, StatementParser.accountTailOfHeading("NEFT TO A/C NO 123456789012"))
        assertEquals("4144", StatementParser.accountTailOfHeading("Savings Account XXXXXXXX4144"))
        assertEquals("4567", StatementParser.accountTailOfHeading("Account Number : 001234567"))
        assertEquals(null, StatementParser.accountTailOfHeading("Card Account No 4629 XXXX XXXX 3004"))
    }

    @Test fun sameAmountWithoutTransferWordsIsNotPaired() {
        val st = StatementParser.fromText(bank.replace("INFT/TO CURRENT A/C XXXXXXXX7788", "AMAZON PAY").replace("INFT/FROM SB A/C XXXXXXXX4144", "CASH DEPOSIT"))!!
        val c = StatementImport.markOwnTransfers(StatementImport.candidates(st, zone = zone), setOf("4144", "7788"))
        assertFalse(c.any { it.ownTransfer })
    }
}

class SalaryDetectTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(d: java.time.LocalDate) = d.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    private fun credit(d: String, amt: Long, cat: String, who: String = "ACME LTD", id: Long = 0) =
        TxnLite(amt, TxnType.CREDIT, cat, who, "••4144", at(java.time.LocalDate.parse(d)), id = id)

    @Test fun lateSalaryCountsForNextMonth() {
        val t = listOf(credit("2026-07-28", 150_000_00, "Salary"), credit("2026-08-27", 150_000_00, "Salary"), credit("2026-09-29", 150_000_00, "Salary"), credit("2026-10-28", 150_000_00, "Salary"), credit("2026-10-05", 500_00, "Other", "FRIEND"))
        val g = SalaryDetect.guess(t, java.time.LocalDate.parse("2026-11-10"), zone)!!
        assertEquals(24, g.fromDay)
        assertTrue(g.alreadySalary)
        val c = MonthCycle(salaryFromDay = g.fromDay)
        assertEquals(java.time.YearMonth.of(2026, 11), c.periodOf(t[3], zone))
        // 24 Oct 2026 is a Saturday, so a salary on Friday the 23rd counts too.
        assertEquals("salary from 23 Oct counts here", c.note(java.time.YearMonth.of(2026, 11)))
    }

    @Test fun steadyPayerWithoutSalaryCategoryIsFound() {
        val t = listOf(credit("2026-08-30", 90_000_00, "Other", "NEFT INFOSYS", 1), credit("2026-09-30", 92_000_00, "Other", "NEFT INFOSYS", 2), credit("2026-10-31", 91_000_00, "Other", "NEFT INFOSYS", 3))
        val g = SalaryDetect.guess(t, java.time.LocalDate.parse("2026-11-10"), zone)!!
        assertFalse(g.alreadySalary)
        assertEquals(listOf(1L, 2L, 3L), g.ids)
        assertEquals(25, g.fromDay)
    }

    @Test fun octoberSalaryWithoutTheWordCountsForNovember() {
        // Older months' SMS said "salary"; October's statement line didn't, and came early (the 25th was a Sunday).
        val t = listOf(
            credit("2026-07-28", 150_000_00, "Salary", id = 1), credit("2026-08-28", 150_000_00, "Salary", id = 2),
            credit("2026-09-29", 152_000_00, "Salary", id = 3), credit("2026-10-23", 151_000_00, "Other", id = 4),
        )
        val g = SalaryDetect.guess(t, java.time.LocalDate.parse("2026-11-10"), zone)!!
        assertTrue(g.alreadySalary)
        assertEquals(listOf(4L), g.toCategorise)
        assertEquals(20, g.fromDay)
        assertEquals(4L, g.latest?.id)
        val c = MonthCycle(salaryFromDay = g.fromDay)
        assertEquals(java.time.YearMonth.of(2026, 11), c.periodOf(t[3].copy(category = "Salary"), zone))
    }

    @Test fun statementSalaryWithAnotherNameFillsItsMonth() {
        // SMS months say RNTBCI and are in Salary; September and October came only from the statement, named
        // differently, so they stayed in Transfers and in their own month (October had two salaries, November none).
        val t = listOf(
            credit("2026-06-26", 2_10_000_00, "Salary", "RNTBCI", 1), credit("2026-07-24", 2_10_000_00, "Salary", "RNTBCI", 2),
            credit("2026-08-26", 2_12_000_00, "Salary", "RNTBCI", 3),
            credit("2026-09-25", 2_12_000_00, "Transfers", "Renault Nissan Technology", 4),
            credit("2026-10-26", 2_12_500_00, "Transfers", "Salary", 5),
            // Not salary: a friend paying back, a fixed deposit, money from own account, a credit into another account.
            credit("2026-09-27", 50_000_00, "Transfers", "Ravi", 6), credit("2026-10-02", 3_00_000_00, "Income", "Fd Closure", 7),
            credit("2026-07-26", 2_10_000_00, "Transfers", "Own account transfer", 8),
            TxnLite(2_12_000_00, TxnType.CREDIT, "Transfers", "Neft", "••9988", at(java.time.LocalDate.parse("2026-08-25")), id = 9),
        )
        val g = SalaryDetect.guess(t, java.time.LocalDate.parse("2026-11-10"), zone)!!
        assertEquals(listOf(4L, 5L), g.toCategorise.sorted())
        val c = MonthCycle(salaryFromDay = g.fromDay)
        val fixed = t.map { if (it.id in g.toCategorise) it.copy(category = "Salary") else it }.filter { it.category == "Salary" }
        val byMonth = fixed.groupBy { c.periodOf(it, zone) }.mapValues { it.value.size }
        assertEquals((7..11).associate { java.time.YearMonth.of(2026, it) to 1 }, byMonth)
    }

    @Test fun oldYearsSalaryGoesInSalaryToo() {
        // Statements from 2023 have the same payer as this year's salary SMS: they count in the next month too.
        val t = listOf(
            credit("2023-03-27", 1_50_000_00, "Transfers", "RNTBCI", 1), credit("2023-04-26", 1_50_000_00, "Transfers", "RNTBCI", 2),
            credit("2023-12-26", 3_40_000_00, "Transfers", "RNTBCI", 3),
            credit("2026-09-25", 2_12_000_00, "Salary", "RNTBCI", 4), credit("2026-10-26", 2_12_000_00, "Salary", "RNTBCI", 5),
        )
        val g = SalaryDetect.guess(t, java.time.LocalDate.parse("2026-11-10"), zone)!!
        assertEquals(listOf(1L, 2L, 3L), g.toCategorise.sorted())
        assertEquals(2, g.months)
    }

    @Test fun aMonthWithItsSalaryGetsNoSecond() {
        val t = listOf(
            credit("2026-08-26", 2_00_000_00, "Salary", "RNTBCI", 1), credit("2026-09-25", 2_00_000_00, "Salary", "RNTBCI", 2),
            credit("2026-09-28", 2_00_000_00, "Transfers", "Neft Somebody", 3),
        )
        val g = SalaryDetect.guess(t, java.time.LocalDate.parse("2026-11-10"), zone)!!
        assertEquals(emptyList<Long>(), g.toCategorise)
    }

    @Test fun earlySalaryNeedsNothing() {
        val t = listOf(credit("2026-08-01", 150_000_00, "Salary"), credit("2026-09-01", 150_000_00, "Salary"), credit("2026-10-01", 150_000_00, "Salary"))
        assertEquals(null, SalaryDetect.guess(t, java.time.LocalDate.parse("2026-11-10"), zone))
    }
}

class CardCyclesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(d: String) = java.time.LocalDate.parse(d).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun billOnThe14thPaidInTheLastWeek() {
        val card = MyAccount(tail = "5002", billDay = 14, payDay = 25)
        val lines = listOf(
            CardLine("5002", TxnType.DEBIT, 1_000_00, at("2026-10-20"), false),
            CardLine("5002", TxnType.DEBIT, 2_000_00, at("2026-11-14"), false),
            CardLine("5002", TxnType.DEBIT, 500_00, at("2026-11-15"), false),
            CardLine("4007", TxnType.DEBIT, 9_000_00, at("2026-11-01"), false),
        )
        val v = CardCycles.view(card, lines, java.time.LocalDate.parse("2026-11-23"), zone)!!
        assertEquals(java.time.LocalDate.parse("2026-11-14"), v.billDate)
        assertEquals(3_000_00L, v.billedMinor)
        assertEquals(500_00L, v.unbilledMinor)
        assertEquals(java.time.LocalDate.parse("2026-11-25"), v.payBy)
        assertTrue(CardCycles.reminderDue(v, java.time.LocalDate.parse("2026-11-23")))
        assertFalse(CardCycles.reminderDue(v, java.time.LocalDate.parse("2026-11-20")))
        val paid = CardCycles.view(card, lines + CardLine("5002", TxnType.CREDIT, 3_000_00, at("2026-11-22"), true), java.time.LocalDate.parse("2026-11-23"), zone)!!
        assertFalse(CardCycles.reminderDue(paid, java.time.LocalDate.parse("2026-11-23")))
    }

    @Test fun beforeTheBillDayTheLastBillIsLastMonths() {
        assertEquals(java.time.LocalDate.parse("2026-10-14"), CardCycles.lastBillDate(14, java.time.LocalDate.parse("2026-11-10")))
        assertEquals(java.time.LocalDate.parse("2026-02-28"), CardCycles.lastBillDate(31, java.time.LocalDate.parse("2026-03-10")))
        assertEquals(java.time.LocalDate.parse("2026-12-05"), CardCycles.payBy(java.time.LocalDate.parse("2026-11-14"), 5))
    }
}

class CardRepeatTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun sameTableUnderAnotherCardIsReadOnce() {
        val text = """
            Card Number : 6528 XXXX XXXX 7002
            Date Ref. Number Transaction Details Currency Amount(in ` )
            12-NOV-26 74332746316000000001234 MAX HYPERMARKET INDIA CHENNAI IN 0.00 4,601.62
            12-NOV-26 Interest Amount Amortization - <1/6> 0.00 12.91
            Card Number : 4375 47XX XXXX 5010
            Date Ref. Number Transaction Details Currency Amount(in ` )
            12-NOV-26 74332746316000000001234 MAX HYPERMARKET INDIA CHENNAI IN 0.00 4,601.62
            12-NOV-26 Interest Amount Amortization - <1/6> 0.00 12.91
            13-NOV-26 74332746317000000009999 SWIGGY BANGALORE IN 0.00 300.00
        """.trimIndent()
        val st = StatementParser.fromText(text)!!
        assertEquals(3, st.rows.size)
    }

    @Test fun lineOnTwoCardsInOneFileIsImportedOnce() {
        val text = """
            Card Number : 6528 XXXX XXXX 7002
            12-NOV-26 Igst-ci@18% 0.00 2.32
            Card Number : 4375 XXXX XXXX 5010
            12-NOV-26 Igst-ci@18% 0.00 2.32
            12-NOV-26 Igst-ci@18% 0.00 2.32
        """.trimIndent()
        val st = StatementParser.fromText(text)!!
        val c = StatementImport.markDuplicates(StatementImport.candidates(st, zone = zone), emptyList())
        // The first card keeps its line; the other card's copy is a repeat. Two on one card stay two.
        assertEquals(listOf(null, DuplicateReason.SAME_STATEMENT, DuplicateReason.SAME_STATEMENT), c.map { it.duplicateOf })
    }

    @Test fun storedTwiceUnderTwoCardsKeepsTheLatest() {
        fun l(id: Long, tail: String, text: String = "Max Hypermarket India", edited: Boolean = false) =
            StatementImport.StoredLine(id, 100, 4_601_62, TxnType.DEBIT, text, tail, edited)
        val gone = StatementImport.storedRepeats(listOf(l(1, "7002"), l(9, "5010"), l(3, "7002", "Swiggy"), l(4, "5010", "Swiggy", edited = true)))
        assertEquals(listOf(1L, 3L), gone)
    }
}

class CardPaymentPairTest {
    @Test fun bankPaymentAndCardCreditPair() {
        val day = 24L * 60 * 60 * 1000
        val rows = listOf(
            SelfTransfers.PayRow(1, TxnType.DEBIT, 54_121_00, 10 * day, "XX144", "Payment", false),
            SelfTransfers.PayRow(2, TxnType.CREDIT, 54_121_00, 11 * day, "XX5010", "Money received", false),
            SelfTransfers.PayRow(3, TxnType.DEBIT, 2_000_00, 10 * day, "XX144", "Uzhavarbumi", false),
            SelfTransfers.PayRow(4, TxnType.CREDIT, 2_000_00, 10 * day, "XX7002", "Refund", false),
        )
        assertEquals(listOf(1L to 2L), SelfTransfers.cardPaymentPairs(rows, setOf("5010", "7002")))
        assertTrue(SelfTransfers.isCardBillPayment(TxnType.CREDIT, "Money received", "Payment of INR 54,121.00 has been received on your ICICI Bank Credit Card Account XX5010"))
    }
}
