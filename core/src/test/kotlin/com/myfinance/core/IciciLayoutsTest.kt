package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ICICI layouts found in real statements (2022 onward savings, website card statement, monthly card bill). Made-up data. */
class IciciLayoutsTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private val numbered = """
        1
        Statement of Transactions in Saving Account no. 602809994144 in INR for the period April 1, 2022 - March 31, 2023
        TEST USER Your Base Branch:  ICICI BANK LIMITED,
        S No. Transaction
        Date Cheque Number Transaction Remarks Withdrawal Deposit Balance
        Amount (INR) Amount (INR) (INR)
        1 02.04.2022 NACH trxn
        ACH/SOME FINANCE LTD/P403PSP0000001 12888.00 548055.49
        2 03.04.2022 209307800001
        UPI/209307800001/Payment for 538.00 547517.49
        JEn/RAZORPAYONLINE@/Yes Bank
        Ltd/YBLfcc2a4cd6e12497a9a59031c463352ef/
        www.icici.bank.in Dial your Bank 1800-1080
        Never share your OTP, CVV or passwords with anyone, even if the person claims to be a Bank employee.
        2
        S No. Transaction
        Date Cheque Number Transaction Remarks Withdrawal Deposit Balance
        Amount (INR) Amount (INR) (INR)
        3 04.04.2022 Debit trxn
        ATM/SFCNM363/CASH WDL/04-04-22 20000.00 527517.49
        4 05.04.2022 210059400002
        NEFT-SALARY ACME CORP 150000.00 677517.49
        5 05.04.2022 210059400003
        UPI/210059400003/R02 PhonePe Rev/phonepemerchant/Yes Bank 100.00 677617.49
    """.trimIndent()

    @Test fun numberedSavingsStatement() {
        val st = StatementParser.fromText(numbered)
        assertNotNull(st)
        assertEquals(StatementKind.BANK, st.kind)
        assertEquals("4144", st.accountTail)
        assertEquals("ICICI Bank", st.bank)
        assertEquals(5, st.rows.size)
        assertEquals(listOf(TxnType.DEBIT, TxnType.DEBIT, TxnType.DEBIT, TxnType.CREDIT, TxnType.CREDIT), st.rows.map { it.type })
        assertEquals(LocalDate.of(2022, 4, 3), st.rows[1].date)
        assertEquals("209307800001", st.rows[1].reference)
        // The remarks run over three lines; page footers and repeated headings are not part of them.
        assertTrue(st.rows[1].description.contains("YBLfcc2a4cd6e12497a9a59031c463352ef"))
        assertTrue(st.rows.none { it.description.contains("icici.bank.in") || it.description.contains("S No.") })
        assertEquals(listOf(1_288_800L, 53_800, 2_000_000, 15_000_000, 10_000), st.rows.map { it.amountMinor })
        assertEquals(0L, st.check!!.differenceMinor)
    }

    @Test fun oldAndNumberedLayoutsInOneFile() {
        val old = """
            Statement of Transactions in Savings Account XXXXXXXX4144 in INR for the period April 01, 2021 - March 31, 2022
            DATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE
            01-04-2021 B/F 609,211.96
            02-04-2021 MOBILE BANKING MMT/IMPS/109111600001/To friend 3,500.00 605,711.96
            03-04-2021 UPI/109310000002/grocer/shop@ybl 999.00 604,712.96
        """.trimIndent()
        val st = StatementParser.fromText(old + "\n" + numbered)
        assertNotNull(st)
        assertEquals(7, st.rows.size)
        assertEquals(setOf("4144"), st.rows.mapNotNull { it.cardTail }.toSet())
        assertEquals(LocalDate.of(2021, 4, 2), st.rows.minOf { it.date })
        assertEquals(LocalDate.of(2022, 4, 5), st.rows.maxOf { it.date })
    }

    private val webView = """
        VIEW LAST STATEMENT
        Card Holder Name TEST USER Card Type secondary
        Previous Balance INR 1000.50 Statement Date 15-10-2026
        Purchases and Other Charges INR 1517.30 Payment Due Date 01-11-2026
        Payments and Other Credits INR 1100.50 Total Credit Limit INR 100000
        Total Amount Due INR 1417.30 Available Credit Limit INR 90000
        Minimum Amount Due INR 200 Total Cash Limit INR 7
        Transaction Details
        Transaction Date Details Amount (INR) Reward Points Reference Number
        11-10-2026 SHOP ONE                    - 1397 Dr. 27 14333100001
        Bangalore
        09-10-2026 UPI-904130800002- 59.3 Dr. 1 14323600002
        YBLafda4032501547898f96
        4e24bea938c2_UPI-904130
        800002-VASANTHA
        07-10-2026 IGST-CI@18% 61 Dr. 0 14310700003
        25-09-2026 INFINITY PAYMENT 1000.50 Cr. 0 14231700004
        RECEIVED, THANK YOU
        12-09-2026 Amazon Pay Groceries 100 Cr. -1 14178900005
    """.trimIndent()

    @Test fun websiteCardStatement() {
        val st = StatementParser.fromText(webView)
        assertNotNull(st)
        assertEquals(StatementKind.CARD, st.kind)
        assertEquals(5, st.rows.size)
        assertEquals("INFINITY PAYMENT RECEIVED, THANK YOU", st.rows[3].description)
        val shop = st.rows.first()
        assertEquals(139_700L, shop.amountMinor)
        assertEquals("14333100001", shop.reference)
        assertEquals("SHOP ONE - Bangalore", shop.description)
        // A UPI line keeps the UPI reference (the SMS quotes it); the bank's number is kept as the serial.
        assertEquals("904130800002", st.rows[1].reference)
        assertEquals("14323600002", st.rows[1].serial)
        assertEquals(5_930L, st.rows[1].amountMinor)
        assertEquals(listOf(TxnType.DEBIT, TxnType.DEBIT, TxnType.DEBIT, TxnType.CREDIT, TxnType.CREDIT), st.rows.map { it.type })
        assertEquals(141_730L, st.billTotalMinor)
        assertEquals(0L, st.check!!.differenceMinor)
    }

    private val monthlyCard = """
        CREDIT CARD STATEMENT
        STATEMENT SUMMARY
        Total Amount due              Previous Balance Purchases / Charges Cash Advances Payments / Credits
        = + + -
        `2,670.00
        `1,000.00 `2,670.00 `0.00 `1,000.00
        Minimum Amount due CREDIT SUMMARY
        SPENDS OVERVIEW Date SerNo. Transaction Details Reward Intl.# Amount (in`)
        4375XXXXXXXX5010
        21/07/2026 13829986363 SHOP TWO CHENNAI IN 2 1,500.00
        02/08/2026 13829986364 INFINITY PAYMENT RECEIVED, THANK YOU 0 1,000.00 CR
        03/08/2026 13903978936 Refund Amt Transfer to Bank Acc XX4144 0 1,170.00
    """.trimIndent()

    @Test fun monthlyCardBillMarksCreditsSoTheRestAreCharges() {
        val st = StatementParser.fromText(monthlyCard)
        assertNotNull(st)
        assertEquals(3, st.rows.size)
        val refundOut = st.rows.single { it.description.startsWith("Refund Amt") }
        // A card refund paid out to the bank account takes money off the card: a charge, not a credit.
        assertEquals(TxnType.DEBIT, refundOut.type)
        // ICICI's 11-digit serial number is the line's transaction ID.
        assertEquals("13829986363", st.rows[0].reference)
        // The previous balance sits two lines under its label.
        assertEquals(100_000L, StatementParser.previousBalanceIn(monthlyCard))
    }

    @Test fun repeatedReversalsWithSameBalanceStay() {
        val text = """
            DATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE
            24-11-2017 B/F 214,017.02
            24-11-2017 MOBILE BANKING MMT/Ref732823864644/1626001010039 900.00 213,117.02
            24-11-2017 MOBILE BANKING MMT/Ref732823864644/1626001010039 REV 900.00 214,017.02
            24-11-2017 MOBILE BANKING MMT/Ref732823864158/1626001010039 900.00 213,117.02
            24-11-2017 MOBILE BANKING MMT/Ref732823864158/1626001010039 REV 900.00 214,017.02
        """.trimIndent()
        val st = StatementParser.fromText(text)
        assertNotNull(st)
        assertEquals(4, st.rows.size)
    }

    @Test fun feeLinesRepeatForReal() {
        fun line(n: Long, text: String) = CheckRow(
            n, "f$n", TxnType.DEBIT, 15_500, "ICICI ••5010", LocalDate.of(2025, 8, 7).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            null, text, statement = true,
        )
        val rows = listOf(line(1, "IGST-CI@18%"), line(2, "IGST-CI@18%"), line(3, "IGST-CI@18%"))
        assertTrue(Duplicates.find(rows, zone = zone).isEmpty())
    }
}
