package com.myfinance.core

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReversalsTest {
    private var n = 0L
    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun row(
        type: TxnType, amount: Long, ts: Long, merchant: String?, text: String? = null,
        account: String? = "XX144", category: String = DefaultCategories.OTHER, manual: Boolean = false, owner: String? = null,
    ) = CheckRow(++n, "u$n", type, amount, account, ts, merchant, text, category, owner, manual)

    /** Siva's 4 Nov: the Jio broadband payment went out at 2:58 pm, came back at 2:58 pm, and was paid again at 7:38 pm. */
    @Test fun jioBroadbandFailedPaymentIsNettedAndTheRetryCounts() {
        val first = row(TxnType.DEBIT, 2_576_71, at(4, 14, 58), "yblec123879a52a")
        val back = row(TxnType.CREDIT, 2_576_71, at(4, 14, 58), null, category = DefaultCategories.BILLS)
        val retry = row(TxnType.DEBIT, 2_576_71, at(4, 19, 38), "yblec123879a52a", category = DefaultCategories.BILLS)
        val swiggy = row(TxnType.DEBIT, 998_00, at(4, 19, 15), "SWIGGY INSTAMART")
        val pairs = Reversals.find(listOf(first, back, retry, swiggy))
        assertEquals(1, pairs.size)
        assertEquals(first.uid, pairs[0].debit.uid)
        assertEquals(back.uid, pairs[0].credit.uid)
        assertEquals(ReversalKind.FAILED, pairs[0].kind)
    }

    @Test fun creditBeforeTheDebitPairsWithTheEarlierDebitNotTheRetry() {
        // The credit's SMS arrived a minute before the debit's.
        val back = row(TxnType.CREDIT, 2_576_71, at(4, 14, 57), null)
        val first = row(TxnType.DEBIT, 2_576_71, at(4, 14, 58), "yblec123879a52a")
        val retry = row(TxnType.DEBIT, 2_576_71, at(4, 19, 38), "yblec123879a52a")
        val p = Reversals.find(listOf(back, first, retry)).single()
        assertEquals(first.uid, p.debit.uid)
    }

    @Test fun reversalSmsDaysLaterIsAFailedPayment() {
        val paid = row(TxnType.DEBIT, 500_00, at(1, 10, 0), "irctc")
        val back = row(TxnType.CREDIT, 500_00, at(5, 9, 0), null, "Your UPI transaction of 500.00 has been reversed to a/c XX144")
        assertEquals(ReversalKind.FAILED, Reversals.find(listOf(paid, back)).single().kind)
    }

    @Test fun sameSellerRefundWithinAWeek() {
        val paid = row(TxnType.DEBIT, 1_299_00, at(1, 10, 0), "AMAZON")
        val back = row(TxnType.CREDIT, 1_299_00, at(6, 9, 0), "AMAZON")
        assertEquals(ReversalKind.REFUND, Reversals.find(listOf(paid, back)).single().kind)
    }

    @Test fun autopayHoldAndReleaseIsAHold() {
        val held = row(TxnType.DEBIT, 649_00, at(2, 8, 0), null, "Rs 649 debited from a/c XX144 for UPI Mandate Netflix")
        val released = row(TxnType.CREDIT, 649_00, at(3, 20, 0), null, "Rs 649 credited to a/c XX144")
        assertEquals(ReversalKind.HOLD, Reversals.find(listOf(held, released)).single().kind)
    }

    @Test fun friendPayingBackIsNotAReversal() {
        val paid = row(TxnType.DEBIT, 1_200_00, at(4, 13, 0), "Swiggy")
        val friend = row(TxnType.CREDIT, 1_200_00, at(4, 13, 20), "RAVI KUMAR")
        assertTrue(Reversals.find(listOf(paid, friend)).isEmpty())
    }

    @Test fun roundAmountHoursLaterFromNoOneIsNotPaired() {
        val paid = row(TxnType.DEBIT, 2_000_00, at(4, 9, 0), "Rent")
        val credit = row(TxnType.CREDIT, 2_000_00, at(4, 18, 0), null)
        assertTrue(Reversals.find(listOf(paid, credit)).isEmpty())
    }

    @Test fun neverPairsSalaryTransfersOtherAccountsOrOwnEntries() {
        val paid = row(TxnType.DEBIT, 2_576_71, at(4, 14, 58), "yblec123879a52a")
        assertTrue(Reversals.find(listOf(paid, row(TxnType.CREDIT, 2_576_71, at(4, 14, 59), null, category = DefaultCategories.SALARY))).isEmpty())
        assertTrue(Reversals.find(listOf(paid, row(TxnType.CREDIT, 2_576_71, at(4, 14, 59), null, account = "XX848"))).isEmpty())
        assertTrue(Reversals.find(listOf(paid, row(TxnType.CREDIT, 2_576_71, at(4, 14, 59), null, manual = true))).isEmpty())
        assertTrue(Reversals.find(listOf(paid, row(TxnType.CREDIT, 2_576_71, at(4, 14, 59), null, owner = "Wife"))).isEmpty())
        // Different amount.
        assertTrue(Reversals.find(listOf(paid, row(TxnType.CREDIT, 2_576_70, at(4, 14, 59), null))).isEmpty())
    }

    @Test fun smsAccountAndStatementAccountAreTheSame() {
        assertTrue(Reversals.sameAccount("XX144", "4144"))
        assertTrue(Reversals.sameAccount(null, "4144"))
        assertFalse(Reversals.sameAccount("XX144", "4848"))
    }

    @Test fun dismissedPairIsNotPairedAgain() {
        val paid = row(TxnType.DEBIT, 2_576_71, at(4, 14, 58), "yblec123879a52a")
        val back = row(TxnType.CREDIT, 2_576_71, at(4, 14, 58), null)
        val key = ReversalPair.keyOf(paid.uid, back.uid)
        assertTrue(Reversals.find(listOf(paid, back), dismissed = setOf(key)).isEmpty())
        assertEquals(paid.uid to back.uid, ReversalPair.uidsOf(key))
        assertNull(ReversalPair.uidsOf("xfer:a+b"))
    }

    @Test fun payeeCodesGiveWayToTheBrand() {
        assertTrue(PayeeNames.isCode("yblec123879a52a"))
        assertTrue(PayeeNames.isCode("paytmqr2810050501abc"))
        assertFalse(PayeeNames.isCode("swiggy8"))
        assertFalse(PayeeNames.isCode("9876543210@ybl"))
        assertFalse(PayeeNames.isCode("SWIGGY INSTAMART"))
        assertEquals("Jio broadband", PayeeNames.better("yblec123879a52a", "Rs 2576.71 debited towards JioFiber bill"))
        assertEquals("Jio", PayeeNames.better("yblec123879a52a", "paid to Reliance Jio Infocomm"))
        assertEquals("yblec123879a52a", PayeeNames.better("yblec123879a52a", "Rs 2576.71 debited"))
        assertEquals("Swiggy", PayeeNames.better("Swiggy", "Jio"))
    }

    @Test fun smsParserNamesTheBrandAndSkipsMandateNotices() {
        val t = SmsParser.parse(
            "JD-ICICIT", "ICICI Bank Acct XX144 debited for Rs 2,576.71 on 04-Nov-26; yblec123879a52a@ybl credited. UPI:530912345678. JioFiber autopay", 1_700_000_000_000,
        )
        assertEquals("Jio broadband", t?.merchant)
        assertNull(SmsParser.parse("JD-ICICIT", "UPI AutoPay mandate for Rs 2,576.71 towards Jio created successfully on a/c XX144", 1_700_000_000_000))
        assertNull(SmsParser.parse("JD-ICICIT", "Pre-debit notification: Rs 2,576.71 for Jio AutoPay on 05-Nov from a/c XX144", 1_700_000_000_000))
        assertNull(SmsParser.parse("JD-ICICIT", "Rs 2,576.71 blocked in a/c XX144 for UPI mandate to Jio", 1_700_000_000_000))
    }

    private val jioReceipt = "Dear Customer, \nPayment of Rs. 2576.71 for your JioHome connection with JioFixedVoice Number +914435673238 through UPI Payments has been received on 04-Nov-26. Thank You! \nNow setup JioAutoPay and enjoy the convenience of automatic bill payments.\nTo register for JioAutoPay, click http://tiny.jio.com/JioPay \nTeam JioHome"

    /** Siva's 4 Nov "Money received" +2,576.71 was JioHome's receipt for the payment, not money in. */
    @Test fun billerReceiptIsNotIncomeButNamesThePayee() {
        assertNull(SmsParser.parse("JM-JIOHOM", jioReceipt, 1_700_000_000_000))
        assertEquals(2_576_71L to "Jio broadband", SmsParser.billerReceipt("JM-JIOHOM", jioReceipt))
        // Money that did reach an account or card is still read.
        assertEquals(TxnType.CREDIT, SmsParser.parse("JD-ICICIT", "Payment of Rs 5,000.00 received on your ICICI Bank Credit Card XX5010. Thank you", 1L)?.type)
        assertEquals(TxnType.CREDIT, SmsParser.parse("JD-ICICIT", "ICICI Bank Acct XX144 is credited with Rs 2,576.71 on 04-Nov-26 from JIO. UPI:530912345678", 1L)?.type)
        assertNull(SmsParser.billerReceipt("JD-ICICIT", "Payment of Rs 5,000.00 received on your ICICI Bank Credit Card XX5010. Thank you"))
    }

    @Test fun twoJioDebitsWithoutTheReceiptAreNotPaired() {
        val first = row(TxnType.DEBIT, 2_576_71, at(4, 14, 58), "yblec123879a52a")
        val second = row(TxnType.DEBIT, 2_576_71, at(4, 19, 38), "yblec123879a52a")
        assertTrue(Reversals.find(listOf(first, second)).isEmpty())
    }

    /** Siva's 4 Nov: the 2:58 pm SMS (bank a/c XX144) and the statement line filed under card 5010 at 7:38 pm are one payment. */
    @Test fun smsAndStatementLineWithTheSameUpiPayeeAreOnePaymentAcrossAccounts() {
        val zone = ZoneOffset.UTC
        val sms = ExistingTxn(
            2_576_71, TxnType.DEBIT, at(4, 14, 58), "XX144", null, "ICICIT|abc", 7,
            text = "ICICI Bank Acct XX144 debited for Rs 2576.71 on 04-Nov-26; yblec123879a52a4806a6fc@ybl credited. UPI:530912345678.",
        )
        val line = ExistingTxn(
            2_576_71, TxnType.DEBIT, at(4, 19, 38), "5010", null, "STMT|x", 9, true,
            "Statement: UPI/530900000000/Yblec123879a52a4806a6fc Cb/Payment/YES BANK",
        )
        assertEquals(listOf(0 to 0), StatementImport.storedSmsDoubles(listOf(sms), listOf(line), zone = zone))
        // A different payee code on another account stays two payments.
        val other = line.copy(text = "Statement: UPI/530900000000/paytmqr2810050501xyz/Payment/YES BANK")
        assertTrue(StatementImport.storedSmsDoubles(listOf(sms), listOf(other), zone = zone).isEmpty())
        // Days apart, the same payee is a new month's bill, not the same payment.
        val later = line.copy(timestamp = at(9, 19, 38))
        assertTrue(StatementImport.storedSmsDoubles(listOf(sms), listOf(later), zone = zone).isEmpty())
    }
}
