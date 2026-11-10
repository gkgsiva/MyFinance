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
