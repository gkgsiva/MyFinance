package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Duplicates by transaction ID, and copies of one payment shown as one group. Made-up data. */
class DuplicatesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var n = 0L
    private fun at(d: LocalDate, h: Int = 12, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun line(
        d: LocalDate, amount: Long, text: String, account: String? = "ICICI ••5010", ref: String? = null,
        merchant: String? = "Amazon India Mumbai", edited: Boolean = false, category: String = DefaultCategories.OTHER,
    ): CheckRow { n++; return CheckRow(n, "u$n", TxnType.DEBIT, amount, account, at(d), merchant, text, category, "Me", statement = true, reference = ref, edited = edited) }

    private fun sms(
        d: LocalDate, amount: Long, text: String, account: String? = "ICICI ••144", ref: String? = null, h: Int = 10, m: Int = 0,
        type: TxnType = TxnType.DEBIT, merchant: String? = null,
    ): CheckRow { n++; return CheckRow(n, "u$n", type, amount, account, at(d, h, m), merchant, text, DefaultCategories.OTHER, "Me", reference = ref) }

    private val apr9 = LocalDate.of(2026, 4, 9)

    @Test fun copiesOfOneLineAreOneGroupNotEveryPair() {
        // The same statement line stored 5 times (no IDs): one card for 5 entries, not 10 pair cards.
        val rows = List(5) { line(apr9, 59_900, "AMAZON INDIA MUMBAI IN") }
        val g = Duplicates.find(rows, zone = zone)
        assertEquals(1, g.size)
        assertEquals(5, g[0].rows.size)
        assertEquals(4, Duplicates.extraCount(g))
        assertFalse(g[0].sameId)
        // A health check reports the same groups.
        assertEquals(1, DataHealth.check(rows, apr9.plusDays(30), zone = zone).duplicates.size)
    }

    @Test fun differentReferenceNumbersAreRealPayments() {
        // Two ₹599 Amazon orders on 9 Apr: the card statement gives each its own reference number.
        val a = line(apr9, 59_900, "AMAZON INDIA MUMBAI IN", ref = "74332746099123456789012")
        val b = line(apr9, 59_900, "AMAZON INDIA MUMBAI IN", ref = "74332746099123456789555")
        assertTrue(Duplicates.find(listOf(a, b), zone = zone).isEmpty())
        // Without the numbers they would look like one line imported twice.
        assertEquals(1, Duplicates.find(listOf(a.copy(reference = null), b.copy(reference = null)), zone = zone).size)
    }

    @Test fun sameReferenceIsCertainAndKeepsTheStatementLine() {
        val s = sms(apr9, 25_000, "Rs 250.00 debited from Acct XX144 UPI Ref 412345678901", ref = "412345678901", h = 20)
        val l = line(apr9.plusDays(1), 25_000, "UPI/412345678901/SWIGGY", account = "ICICI ••4144", ref = "412345678901", merchant = "Swiggy")
        val g = Duplicates.find(listOf(s, l), zone = zone).single()
        assertTrue(g.sameId)
        assertEquals(l.uid, g.keep.uid)
        assertEquals(listOf(s.uid), g.extras.map { it.uid })
        assertTrue(ReviewInbox.duplicateDetail(g).contains("412345678901"))
    }

    @Test fun sameReferenceTooFarApartIsNotOnePayment() {
        // A monthly mandate quoting the same number every month.
        val a = sms(apr9, 99_900, "NACH debit UMRN ref ICIC7021234567890", ref = "ICIC7021234567890")
        val b = sms(apr9.plusMonths(1), 99_900, "NACH debit UMRN ref ICIC7021234567890", ref = "ICIC7021234567890")
        assertTrue(Duplicates.find(listOf(a, b), zone = zone).isEmpty())
    }

    @Test fun differentShopsSameAmountSameDayAreNotDuplicates() {
        val a = line(apr9, 50_000, "SWIGGY BANGALORE IN", merchant = "Swiggy")
        val b = line(apr9, 50_000, "IRCTC NEW DELHI IN", merchant = "IRCTC")
        assertTrue(Duplicates.find(listOf(a, b), zone = zone).isEmpty())
    }

    @Test fun groupNeverMixesTwoIds() {
        // Three alerts minutes apart: two carry different references, so they are two payments.
        val x = sms(apr9, 10_000, "Rs 100 paid UPI Ref 411111111111", ref = "411111111111", m = 0)
        val y = sms(apr9, 10_000, "Rs 100 paid", m = 2)
        val z = sms(apr9, 10_000, "Rs 100 paid UPI Ref 422222222222", ref = "422222222222", m = 4)
        val groups = Duplicates.find(listOf(x, y, z), zone = zone)
        assertEquals(1, groups.size)
        val ids = groups[0].rows.mapNotNull { it.reference }.toSet()
        assertEquals(1, ids.size)
    }

    @Test fun answeredPairsStayApart() {
        val a = line(apr9, 59_900, "AMAZON INDIA MUMBAI IN")
        val b = line(apr9, 59_900, "AMAZON INDIA MUMBAI IN")
        val c = line(apr9, 59_900, "AMAZON INDIA MUMBAI IN")
        val all = Duplicates.find(listOf(a, b, c), zone = zone).single()
        assertEquals(3, all.rows.size)
        // "All are real" on the group writes the group key and every pair key.
        val keys = setOf(all.key, Duplicates.pairKey(a, b), Duplicates.pairKey(a, c), Duplicates.pairKey(b, c))
        assertTrue(Duplicates.find(listOf(a, b, c), keys, zone).isEmpty())
        // An older "Both are real" answer for one pair keeps those two apart.
        val g = Duplicates.find(listOf(a, b, c), setOf(Duplicates.pairKey(a, b)), zone)
        assertTrue(g.none { r -> r.rows.any { it.uid == a.uid } && r.rows.any { it.uid == b.uid } })
    }

    @Test fun keepsTheEditedEntry() {
        val a = line(apr9, 59_900, "AMAZON INDIA MUMBAI IN")
        val b = line(apr9, 59_900, "AMAZON INDIA MUMBAI IN", edited = true, category = DefaultCategories.SHOPPING)
        assertEquals(b.uid, Duplicates.find(listOf(a, b), zone = zone).single().keep.uid)
    }

    @Test fun differentKindsOfIdsDecideNothing() {
        // The SMS quotes the UPI reference; the card statement its own 23-digit number. Same payment, typed in by hand.
        val s = sms(apr9, 30_000, "Rs 300 spent on card XX5010 UPI Ref 433333333333", account = "ICICI ••5010", ref = "433333333333", h = 12, m = 3)
        val l = line(apr9, 30_000, "JIO PREPAID", ref = "74332746099123456700000", merchant = "Jio")
        assertEquals(1, Duplicates.find(listOf(s, l), zone = zone).size)
    }

    @Test fun weakIdsAreIgnored() {
        assertEquals(null, Duplicates.id("Ref1"))
        assertEquals(null, Duplicates.id("000000000000"))
        assertEquals("412345678901", Duplicates.id(" 412345678901 "))
        assertTrue(Duplicates.sameId("412345678901", "0412345678901"))
        assertFalse(Duplicates.sameId("412345678901", "412345678902"))
    }

    @Test fun cardStatementReferenceNumbersBecomeIds() {
        val text = """
            Customer Name Card Account No
            MR. TEST 4629 XXXX XXXX 3004
            Statement Period
            01/04/2026 TO 13/04/2026
            TRANSACTION DETAILS
            Card Number : 4375 XXXX XXXX 5010
            Date Ref. Number Transaction Details Currency Amount(in ` )
            09-APR-26 74332746099123456789012 AMAZON INDIA MUMBAI IN 0.00 599.00
            09-APR-26 74332746099123456789555 AMAZON INDIA MUMBAI IN 0.00 599.00
        """.trimIndent()
        val st = StatementParser.fromText(text)!!
        assertEquals(2, st.rows.size)
        val refs = st.rows.map { it.reference }
        assertEquals(listOf("74332746099123456789012", "74332746099123456789555"), refs)
        assertNotEquals(refs[0], refs[1])
        val rows = StatementImport.candidates(st, zone = zone).mapIndexed { i, c ->
            CheckRow(i.toLong(), c.uid, c.row.type, c.row.amountMinor, c.accountTail, c.timestamp, c.merchant, c.row.description, statement = true, reference = c.row.reference)
        }
        // The real pair stays: two reference numbers.
        assertTrue(Duplicates.find(rows, zone = zone).isEmpty())
    }
}
