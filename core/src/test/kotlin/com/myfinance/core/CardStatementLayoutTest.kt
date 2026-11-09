package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Card statement layouts with a serial number and reward points column, side panels and several cards. Made-up data. */
class CardStatementLayoutTest {

    private val monthly = """
        ABC BANK CREDIT CARD STATEMENT
        STATEMENT DATE PAYMENT DUE DATE
        Total Amount due Minimum Amount due
        SPENDS OVERVIEW Date SerNo. Transaction Details Reward Intl.# Amount (in`)
        Points amount
        1111XXXXXXXX2222
        55% 03/05/2026 10000000001 CORNER BAKERY CHENNAI IN 2 120.00
        03/05/2026 10000000002 ONLINE STORE CYBS SI MUMBAI IN 1 99.00
        06/05/2026 10000000003 CARD PAYMENT RECEIVED, THANK YOU 0 5,000.00 CR
        12%
        07/05/2026 10000000004 Interest Amount Amortization - <2/6>GADGET SHOP 0 81.20
        30% GADGET RETAIL
         Food-12%  Others-55% 07/05/2026 10000000005 IGST-CI@18% 0 14.62
        08/05/2026 10000000006 STREAMING APP Bangalore IN 3 199.00
        ABC Bank Rewards
        3333XXXXXXXX4444
        09/05/2026 10000000007 UPI-123456789012-FRUIT ST ALL IN 0 60.00
        10/05/2026 10000000008 Online Store Refund -2 45.50 CR
    """.trimIndent()

    @Test
    fun readsSerialPointsAndSidePanels() {
        val st = assertNotNull(StatementParser.fromText(monthly))
        assertEquals(StatementKind.CARD, st.kind)
        assertEquals(8, st.rows.size)
        val r = st.rows
        assertEquals("CORNER BAKERY CHENNAI IN", r[0].description)
        assertEquals(12_000, r[0].amountMinor)
        assertEquals("ONLINE STORE CYBS SI MUMBAI IN", r[1].description)
        assertEquals(TxnType.CREDIT, r[2].type)
        assertEquals(500_000, r[2].amountMinor)
        // The "12%" panel line is dropped, the real second line of the EMI kept.
        assertEquals("Interest Amount Amortization - <2/6>GADGET SHOP GADGET RETAIL", r[3].description)
        // Date after the side panel text.
        assertEquals(LocalDate.of(2026, 5, 7), r[4].date)
        assertEquals(1_462, r[4].amountMinor)
        assertEquals("STREAMING APP Bangalore IN", r[5].description)
        assertEquals("UPI-123456789012-FRUIT ST ALL IN", r[6].description)
        assertEquals("123456789012", r[6].reference)
        assertEquals(TxnType.CREDIT, r[7].type)
        assertEquals("Online Store Refund", r[7].description)
    }

    @Test
    fun eachLineKeepsItsCard() {
        val st = assertNotNull(StatementParser.fromText(monthly))
        assertEquals("2222", st.rows[0].cardTail)
        assertEquals("2222", st.rows[5].cardTail)
        assertEquals("4444", st.rows[6].cardTail)
        val c = StatementImport.candidates(st, zone = ZoneId.of("Asia/Kolkata"))
        assertEquals("4444", c[6].accountTail)
        // An SMS for the UPI card matches its line, not a new entry.
        val sms = ExistingTxn(6_000, TxnType.DEBIT, c[6].timestamp, "4444", null, "SMS|x", id = 9)
        val marked = StatementImport.markDuplicates(c, listOf(sms), zone = ZoneId.of("Asia/Kolkata"))
        assertEquals(9L, marked[6].matchedId)
    }

    @Test
    fun merchantNamesFromCardLines() {
        assertEquals("Online Store Mumbai", Narration.merchant("ONLINE STORE CYBS SI MUMBAI IN"))
        assertEquals("Fruit St All", Narration.merchant("UPI-123456789012-FRUIT ST ALL IN"))
        assertEquals("Card payment received", Narration.merchant("CARD PAYMENT RECEIVED, THANK YOU"))
    }

    private val yearly = """
        Customer Name Card Account No
        MR. TEST USER 1111 XXXX XXXX 9999
        Statement Period
        01/04/2025 TO 31/03/2026
        TRANSACTION DETAILS
        Card Number : 1111 XXXX XXXX 5555
        Date Ref. Number Transaction Details Currency Amount(in ` )
        02-APR-25 12345678901234567890123 SOFTWARE SUBSCRIPTION EXAMPLE.CO USD 20.00 1,750.00
        US*
        03-APR-25 12345678901234567890124 LOCAL CAFE CHENNAI IN 0.00 240.00
        05-APR-25 Annual Fees 0.00 500.00
        09-APR-25 210987654321 CARD PAYMENT RECEIVED, THANK YOU 0.00 -2,490.00
        The category of service:Credit & Debit Card,Charge Card or Other payment Card service.
    """.trimIndent()

    @Test
    fun readsYearlyCardStatement() {
        val st = assertNotNull(StatementParser.fromText(yearly))
        assertEquals(4, st.rows.size)
        assertEquals("SOFTWARE SUBSCRIPTION EXAMPLE.CO US*", st.rows[0].description)
        assertEquals(175_000, st.rows[0].amountMinor)
        assertEquals("5555", st.rows[0].cardTail)
        assertEquals("LOCAL CAFE CHENNAI IN", st.rows[1].description)
        assertEquals(TxnType.CREDIT, st.rows[3].type)
        assertEquals(249_000, st.rows[3].amountMinor)
        assertEquals("CARD PAYMENT RECEIVED, THANK YOU", st.rows[3].description)
        assertNull(st.billDueDate)
    }
}
