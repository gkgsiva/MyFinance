package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Statement lines and the SMS for the same payments must be one entry, not two. Layouts follow ICICI's savings
 * account and card statements as PDFBox reads them; names, numbers and amounts are made up.
 */
class SmsStatementDoublesTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private val bankText = """
        Statement of Transactions in Savings Account XXXXXXXX7788 in INR for the period April 01, 2018 - March 31, 2019
        DATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE
        01-04-2018  B/F  1,00,000.00
        04-04-2018 DEBIT CARD VPS/CORNER SHOP /93753030719777/0 4,800.00 95,200.00
        24-04-2018  NEFT-AXIR757707152511-A FRIEND--47837330406 420.00  95,620.00
         065-UTIB0000285  0.00
        30-04-2018  SAL/Salary - Apr 2018 1,55,803.00  2,51,423.00
        11-05-2018 OTHER ATMS NFS/CASH WDL/11-05-18 10,000.00 2,41,423.00
        11-05-2018 OTHER ATMS NFS/CASH WDL/11-05-18 10,000.00 2,31,423.00
        14-05-2018 NET BANKING BIL/337089926085/ChgRs10 GSTRs1.80 IRCTC/10000126 3,811.80 2,27,611.20
        Total: 1,56,223.00 28,611.80 2,27,611.20
        Page 1 of 1
    """.trimIndent()

    private val cardText = """
        Customer Name Card Account No
        MR. A CUSTOMER 4000 XXXX XXXX 9003
        Statement Period
        01/04/2023 TO 31/03/2024
        TRANSACTION DETAILS
        Card Number : 4000 XXXX XXXX 9001
        International
        Date Ref. Number Transaction Details Currency Amount(in ` )
        amount
        29-MAY-23 20119201783170449111742 JEWELLER MUMBAI IN 0.00 12,500.00
        29-MAY-23 20119201783170449111094 JEWELLER MUMBAI IN 0.00 12,500.00
        02-MAR-24 90049760369333383050069 AI CHAT SUBSCRIPTION HTTPSEXAMPLE.C USD 23.60 2,038.32
        US*
        04-MAR-24 20119200368036005672631 TUITION CENTRE CHENNAI IN 0.00 2,500.00
        05-MAR-24 20119200368036005672444 BOOK SHOP CHENNAI IN 0.00 0.00
    """.trimIndent()

    private fun at(y: Int, m: Int, d: Int, h: Int = 10) = LocalDate.of(y, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** A stored SMS entry, read by the app's own SMS parser. */
    private fun sms(body: String, ts: Long, id: Long, sender: String = "AX-ICICIB"): ExistingTxn {
        val p = assertNotNull(SmsParser.parse(sender, body, ts), body)
        return ExistingTxn(p.amountMinor, p.type, p.timestamp, p.accountTail, p.reference, "SMS|$id", id, text = body)
    }

    private fun bank() = StatementImport.candidates(assertNotNull(StatementParser.fromText(bankText)), zone = zone)
    private fun card() = StatementImport.candidates(assertNotNull(StatementParser.fromText(cardText)), zone = zone)

    @Test
    fun bankStatementReadsEveryLineOnce() {
        val st = assertNotNull(StatementParser.fromText(bankText))
        assertEquals(StatementKind.BANK, st.kind)
        assertEquals("7788", st.accountTail)
        // Two cash withdrawals of the same amount on one day are two transactions.
        assertEquals(6, st.rows.size)
        assertEquals(2, st.rows.count { it.amountMinor == 10_000_00L })
        assertEquals(st.check?.expectedMinor, st.check?.computedMinor)
        assertEquals(6, bank().map { it.key }.distinct().size)
    }

    @Test
    fun icici3DigitAccountSmsMatchesItsStatementLine() {
        val atm = sms("ICICI Bank Acct XX788 debited with Rs 10,000.00 on 11-May-18. Info: NFS*CASH WDL*11-05-18. Avl Bal Rs 2,41,423.00.", at(2018, 5, 11), 1)
        val salary = sms("ICICI Bank Acct XX788 is credited with Rs 1,55,803.00 on 30-Apr-18. Info: SAL*Salary - Apr 2018. Avl Bal Rs 2,51,423.00.", at(2018, 4, 30), 2)
        assertEquals("788", atm.accountTail)
        assertTrue(StatementImport.sameAccount("788", "7788"))
        assertTrue(!StatementImport.sameAccount("44", "7788"))
        assertTrue(!StatementImport.sameAccount("5144", "7788"))

        val marked = StatementImport.markDuplicates(bank(), listOf(atm, salary), zone = zone)
        // One ATM line takes the SMS; the other withdrawal of the same amount stays a transaction of its own.
        val atms = marked.filter { it.row.amountMinor == 10_000_00L }
        assertEquals(listOf(1L), atms.mapNotNull { it.matchedId })
        assertEquals(1, atms.count { it.duplicateOf == null })
        assertEquals(2L, marked.single { it.row.amountMinor == 1_55_803_00L }.matchedId)
        assertEquals(2, StatementImport.summary(marked).updateCount)
    }

    @Test
    fun anotherAccountsSmsIsNotTheStatementLine() {
        val other = sms("ICICI Bank Acct XX555 debited with Rs 3,811.80 on 14-May-18. Info: BIL*IRCTC.", at(2018, 5, 14), 3)
        val marked = StatementImport.markDuplicates(bank(), listOf(other), zone = zone)
        assertTrue(marked.all { it.duplicateOf == null })
    }

    @Test
    fun debitCardSmsMatchesTheAccountsCardPayment() {
        val dc = ExistingTxn(4_800_00L, TxnType.DEBIT, at(2018, 4, 4, 13), "9876", null, "SMS|4", 4,
            text = "Your ICICI Bank Debit Card XX9876 has been used for a transaction of INR 4,800.00 at CORNER SHOP on 04-Apr-18.")
        val marked = StatementImport.markDuplicates(bank(), listOf(dc), zone = zone)
        assertEquals(4L, marked.single { it.row.amountMinor == 4_800_00L }.matchedId)
        // A credit card's SMS for the same amount is a different card.
        val cc = dc.copy(text = "INR 4,800.00 spent using ICICI Bank Credit Card XX9876 on 04-Apr-18 at CORNER SHOP.")
        assertTrue(StatementImport.markDuplicates(bank(), listOf(cc), zone = zone).all { it.duplicateOf == null })
    }

    @Test
    fun foreignSpendSmsMatchesTheCardLineInRupees() {
        val st = assertNotNull(StatementParser.fromText(cardText))
        assertEquals("USD 23.60", st.rows.single { it.amountMinor == 2_038_32L }.foreign)
        // Zero-amount lines are not transactions; the two same-day ₹12,500 purchases are two.
        assertEquals(4, st.rows.size)

        val usd = sms("USD 23.60 spent using ICICI Bank Card XX9001 on 02-Mar-24 on AI CHAT. Avl Limit: INR 1,00,000.00.", at(2024, 3, 2), 5)
        assertTrue(usd.amountMinor != 2_038_32L)
        val marked = StatementImport.markDuplicates(card(), listOf(usd), zone = zone)
        assertEquals(5L, marked.single { it.row.amountMinor == 2_038_32L }.matchedId)

        // On another card it is another purchase.
        val elsewhere = usd.copy(accountTail = "9002")
        assertTrue(StatementImport.markDuplicates(card(), listOf(elsewhere), zone = zone).all { it.duplicateOf == null })
    }

    @Test
    fun reimportingAddsNothing() {
        val first = StatementImport.markDuplicates(bank() + card(), emptyList(), zone = zone)
        assertTrue(first.all { it.duplicateOf == null })
        val stored = first.map {
            ExistingTxn(it.row.amountMinor, it.row.type, it.timestamp, it.accountTail, it.row.reference, it.key, 10, true, it.row.description)
        }
        val again = StatementImport.markDuplicates(bank() + card(), stored, zone = zone)
        assertTrue(again.all { it.duplicateOf == DuplicateReason.SAME_STATEMENT })
    }

    @Test
    fun smsArrivingAfterTheStatementFindsItsLine() {
        val lines = bank().mapIndexed { i, c ->
            ExistingTxn(c.row.amountMinor, c.row.type, c.timestamp, c.accountTail, c.row.reference, c.key, 100L + i, true, c.row.description)
        }
        val neft = sms("ICICI Bank Acct XX788 is credited with Rs 420.00 on 24-Apr-18 by NEFT from A FRIEND.", at(2018, 4, 24), 6)
        val i = assertNotNull(StatementImport.lineForSms(neft, lines, zone = zone))
        assertEquals(420_00L, lines[i].amountMinor)

        val fx = sms("USD 23.60 spent using ICICI Bank Card XX9001 on 02-Mar-24 on AI CHAT.", at(2024, 3, 2), 7)
        val cardLines = card().map { c -> ExistingTxn(c.row.amountMinor, c.row.type, c.timestamp, c.accountTail, null, c.key, 1, true, c.row.description) }
        assertEquals(2_038_32L, cardLines[assertNotNull(StatementImport.lineForSms(fx, cardLines, zone = zone))].amountMinor)
    }

    @Test
    fun storedDoublesArePairedOnceAndOnlyOnKnownAccounts() {
        val lines = bank().mapIndexed { i, c ->
            ExistingTxn(c.row.amountMinor, c.row.type, c.timestamp, c.accountTail, c.row.reference, c.key, 100L + i, true, c.row.description)
        }
        val atm1 = sms("ICICI Bank Acct XX788 debited with Rs 10,000.00 on 11-May-18. Info: NFS*CASH WDL.", at(2018, 5, 11, 9), 1)
        val atm2 = sms("ICICI Bank Acct XX788 debited with Rs 10,000.00 on 11-May-18. Info: NFS*CASH WDL.", at(2018, 5, 11, 11), 2)
        val atm3 = sms("ICICI Bank Acct XX788 debited with Rs 10,000.00 on 11-May-18. Info: NFS*CASH WDL.", at(2018, 5, 11, 12), 3)
        // No account named: could be any bank's, so not removed by amount alone.
        val unknown = ExistingTxn(3_811_80L, TxnType.DEBIT, at(2018, 5, 14), null, null, "SMS|9", 9, text = "Rs 3811.80 debited")
        val pairs = StatementImport.storedSmsDoubles(listOf(atm1, atm2, atm3, unknown), lines, zone = zone)
        assertEquals(2, pairs.size)
        assertEquals(setOf(0, 1), pairs.map { it.first }.toSet())
        assertEquals(2, pairs.map { it.second }.distinct().size)
        assertNull(pairs.firstOrNull { it.first == 3 })
    }
}
