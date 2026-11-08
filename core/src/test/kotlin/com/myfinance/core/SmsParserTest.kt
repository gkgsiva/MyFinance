package com.myfinance.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SmsParserTest {

    private fun parse(sender: String, body: String) = SmsParser.parse(sender, body, 1_700_000_000_000)

    @Test
    fun hdfcUpiDebit() {
        val t = assertNotNull(
            parse(
                "VM-HDFCBK",
                "Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 05/11/24 Ref 431012345678 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808",
            ),
        )
        assertEquals(25000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("1234", t.accountTail)
        assertEquals("SWIGGY", t.merchant)
        assertEquals("HDFC Bank", t.bank)
        assertEquals("431012345678", t.reference)
    }

    @Test
    fun sbiUpiDebitWithVpa() {
        val t = assertNotNull(
            parse(
                "AD-SBIINB-S",
                "Dear UPI user A/C X5678 debited by 1,499.00 on date 04Nov24 trf to ZEPTO Refno 430912345678. If not u? call 1800111109. -SBI",
            ),
        )
        assertEquals(149900, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("5678", t.accountTail)
        assertEquals("ZEPTO", t.merchant)
        assertEquals("SBI", t.bank)
    }

    @Test
    fun creditCardSpend() {
        val t = assertNotNull(
            parse(
                "JD-HDFCBK",
                "Spent Rs 1,299.00 on HDFC Bank Card x4321 at AMAZON PAY INDIA on 2024-11-05:10:22:31. Avl Lmt: Rs 85,000.00. Not you? Call 18002586161",
            ),
        )
        assertEquals(129900, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("4321", t.accountTail)
        assertEquals("AMAZON PAY INDIA", t.merchant)
        assertEquals(8500000, t.balanceMinor)
    }

    @Test
    fun salaryCredit() {
        val t = assertNotNull(
            parse(
                "VK-ICICIB",
                "Dear Customer, your Acct XX9012 is credited with INR 85,000.00 on 01-Nov-24 by NEFT from ACME TECHNOLOGIES PVT LTD. Avl Bal INR 1,23,456.78",
            ),
        )
        assertEquals(8500000, t.amountMinor)
        assertEquals(TxnType.CREDIT, t.type)
        assertEquals("9012", t.accountTail)
        assertEquals("ACME TECHNOLOGIES PVT LTD", t.merchant)
        assertEquals("ICICI Bank", t.bank)
        assertEquals(12345678, t.balanceMinor)
    }

    @Test
    fun axisDebitToVpa() {
        val t = assertNotNull(
            parse(
                "AX-AXISBK",
                "INR 120.00 debited from A/c no. XX3456 on 06-11-24 for UPI/P2M/431112223334/VPA blinkit.payu@hdfcbank. Not you? SMS BLOCKUPI",
            ),
        )
        assertEquals(12000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("3456", t.accountTail)
        assertEquals("blinkit.payu", t.merchant)
        assertEquals("Axis Bank", t.bank)
    }

    @Test
    fun receivedFromPerson() {
        val t = assertNotNull(
            parse("BZ-KOTAKB", "Received Rs.2000.00 in your Kotak Bank a/c XX7788 from RAHUL KUMAR on 07-11-24.UPI Ref:431234560000."),
        )
        assertEquals(200000, t.amountMinor)
        assertEquals(TxnType.CREDIT, t.type)
        assertEquals("7788", t.accountTail)
        assertEquals("RAHUL KUMAR", t.merchant)
        assertEquals("Kotak Bank", t.bank)
    }

    @Test
    fun atmWithdrawal() {
        val t = assertNotNull(
            parse("VM-SBIINB", "Rs.5000 withdrawn at SBI ATM S1BW000123 from A/cX1111 on 03Nov24 Txn# 1234. Avl Bal Rs.20000.00"),
        )
        assertEquals(500000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals(2000000, t.balanceMinor)
    }

    @Test
    fun ignoresOtp() {
        assertNull(parse("VM-HDFCBK", "OTP for txn of Rs 2,000.00 at AMAZON on card x4321 is 123456. Valid for 5 mins. Do not share."))
    }

    @Test
    fun ignoresFutureDebitReminder() {
        assertNull(parse("VM-HDFCBK", "Rs 999.00 will be debited from your a/c XX1234 on 10-11-24 towards Netflix autopay mandate."))
    }

    @Test
    fun ignoresPromo() {
        assertNull(parse("VM-HDFCBK", "Get a pre-approved personal loan of Rs 5,00,000 at 10.5% p.a. Click here"))
    }

    @Test
    fun ignoresDeclined() {
        assertNull(parse("VM-ICICIB", "Txn of Rs 500.00 on ICICI Card XX1111 at FLIPKART was declined due to insufficient limit."))
    }

    @Test
    fun ignoresNonTransactional() {
        assertNull(parse("VM-HDFCBK", "Your HDFC Bank statement for October is ready. Login to netbanking to view."))
    }

    @Test
    fun bankFromSenderHandlesDltFormats() {
        assertEquals("HDFC Bank", SmsParser.bankFromSender("VM-HDFCBK"))
        assertEquals("SBI", SmsParser.bankFromSender("AD-SBIINB-S"))
        assertEquals("ICICI Bank", SmsParser.bankFromSender("JX-ICICIT-T"))
        assertNull(SmsParser.bankFromSender("+919876543210"))
    }
}
