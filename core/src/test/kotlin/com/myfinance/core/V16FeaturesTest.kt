package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V16FeaturesTest {
    private val zone = ZoneOffset.UTC
    private fun at(d: LocalDate, h: Int = 10, m: Int = 0): Long = d.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private var n = 0L
    private fun row(
        d: LocalDate, amount: Long, type: TxnType, account: String?, merchant: String? = null, text: String? = null,
        h: Int = 10, m: Int = 0, manual: Boolean = false, ref: String? = null, category: String = DefaultCategories.OTHER,
    ): CheckRow { n++; return CheckRow(n, "u$n", type, amount, account, at(d, h, m), merchant, text, category, null, manual, false, ref) }

    @Test fun transferBetweenOwnAccounts() {
        val d = LocalDate.of(2026, 10, 5)
        val out = row(d, 5_000_000, TxnType.DEBIT, "HDFC ••1234", text = "Rs 50000 debited from a/c XX1234 by IMPS to self")
        val into = row(d.plusDays(1), 5_000_000, TxnType.CREDIT, "SBI ••9876", text = "a/c XX9876 credited Rs 50000 by IMPS")
        val shop = row(d, 5_000_000, TxnType.DEBIT, "HDFC ••1234", merchant = "TANISHQ", text = "spent at TANISHQ")
        val found = SelfTransfers.find(listOf(out, into, shop))
        val m = found.single()
        assertEquals(TransferKind.OWN_ACCOUNTS, m.kind)
        assertEquals(out.uid, m.out?.uid)
        assertEquals(into.uid, m.into?.uid)
        assertTrue(SelfTransfers.find(listOf(out, into), setOf(m.key)).isEmpty())
    }

    @Test fun cardBillPayment() {
        val d = LocalDate.of(2026, 10, 20)
        val pay = row(d, 2_500_000, TxnType.DEBIT, "HDFC ••1234", merchant = "CC PAYMENT 4321", text = "Rs 25000 debited towards credit card XX4321")
        val found = SelfTransfers.find(listOf(pay))
        assertEquals(TransferKind.CARD_BILL, found.single().kind)
    }

    @Test fun splitParts() {
        val parts = listOf(SplitPart("Household", 50_000))
        assertNull(PaymentSplit.problem(200_000, parts))
        assertEquals(150_000, PaymentSplit.remaining(200_000, parts))
        assertNotNull(PaymentSplit.problem(200_000, listOf(SplitPart("Household", 200_000))))
    }

    @Test fun familyUpiPeopleAndLimits() {
        assertEquals("Priya", FamilyUpi.personFromSms("Rs 250 debited from A/c XX1234 by PRIYA via UPI Circle"))
        assertEquals("Anu", FamilyUpi.personFromLabels(listOf("Food", "Family UPI - Anu")))
        val oct = LocalDate.of(2026, 10, 3)
        val pays = listOf(
            FamilyPayment("a", 1, "Priya", 300_000, at(oct), "CANTEEN"),
            FamilyPayment("b", 2, "Priya", 300_000, at(oct.plusDays(4)), "BOOKS"),
            FamilyPayment("c", 3, "Anu", 50_000, at(oct), "CAFE"),
        )
        val rows = FamilyUpi.month(pays, mapOf("Priya" to 500_000), YearMonth.of(2026, 10), zone)
        val priya = rows.first { it.person == "Priya" }
        assertTrue(priya.over)
        assertEquals(120, priya.percent)
        assertNull(rows.first { it.person == "Anu" }.limitMinor)
    }

    @Test fun healthFindsGapsQuietAccountsAndDuplicates() {
        val rows = ArrayList<CheckRow>()
        // Regular account Jan-Mar, then nothing; April has no entries at all.
        for (mo in 1..3) for (dd in listOf(5, 15, 25)) rows += row(LocalDate.of(2026, mo, dd), 10_000, TxnType.DEBIT, "ICICI ••1111")
        rows += row(LocalDate.of(2026, 5, 2), 20_000, TxnType.DEBIT, "HDFC ••1234", merchant = "SWIGGY", h = 12, m = 0)
        rows += row(LocalDate.of(2026, 5, 2), 20_000, TxnType.DEBIT, "HDFC ••1234", merchant = "SWIGGY", h = 12, m = 3)
        val r = DataHealth.check(rows, LocalDate.of(2026, 6, 10), zone = zone)
        assertEquals(listOf(YearMonth.of(2026, 4)), r.missingMonths)
        assertEquals("ICICI ••1111", r.quietAccounts.single().account)
        assertEquals(1, r.duplicates.size)
        assertTrue(DataHealth.check(rows, LocalDate.of(2026, 6, 10), setOf(r.duplicates[0].key), zone).duplicates.isEmpty())
    }

    @Test fun ruleTesterShowsChanges() {
        val rule = CustomRule("r1", "swiggy", setCategory = DefaultCategories.FOOD)
        val rows = listOf(
            RuleTestRow(1, "paid to SWIGGY", "SWIGGY", TxnType.DEBIT, 30_000, DefaultCategories.OTHER, 1, false),
            RuleTestRow(2, "paid to SWIGGY", "SWIGGY", TxnType.DEBIT, 30_000, DefaultCategories.OTHER, 2, true),
            RuleTestRow(3, "paid to ZOMATO", "ZOMATO", TxnType.DEBIT, 30_000, DefaultCategories.OTHER, 3, false),
            RuleTestRow(4, "SWIGGY", "SWIGGY", TxnType.DEBIT, 30_000, DefaultCategories.FOOD, 4, false),
        )
        val res = RuleTester.test(rule, emptyList(), rows)
        assertEquals(3, res.matched)
        assertEquals(1, res.locked)
        assertEquals(listOf(1L), res.changes.map { it.row.id })
    }

    @Test fun foreignCurrencySms() {
        val p = SmsParser.parse("HDFCBK", "Spent USD 12.99 on HDFC Bank Card x4321 at NETFLIX.COM on 2026-11-05. Avl Lmt Rs 1,20,000", 0, mapOf("USD" to 88.0))
        assertNotNull(p)
        assertEquals("USD", p.foreign?.code)
        assertEquals(114_312, p.amountMinor)
        val note = Fx.note(p.foreign!!, 88.0)
        assertEquals("USD 12.99 at ₹88.00", note)
        assertEquals(1299, Fx.parseNote("Trip. $note")?.amount?.amountMinor)
        // Rupee amount first: stays in rupees.
        val inr = SmsParser.parse("HDFCBK", "Rs 1,099.00 spent on card x4321 at NETFLIX (USD 12.99)", 0)
        assertNull(inr?.foreign)
        assertEquals(109_900, inr?.amountMinor)
        assertEquals(mapOf("USD" to 90.5), Fx.parseRates(Fx.formatRates(mapOf("USD" to 90.5))))
    }

    @Test fun heavySpends() {
        val base = LocalDate.of(2026, 10, 1)
        val txns = listOf(
            TxnLite(1_500_000, TxnType.DEBIT, "Shopping", "TV", null, at(base)),
            TxnLite(200_000, TxnType.DEBIT, "Food", "Cafe", null, at(base.plusDays(1))),
            TxnLite(2_000_000, TxnType.DEBIT, "Travel", "Air", null, at(base.plusDays(2))),
            TxnLite(9_000_000, TxnType.CREDIT, "Salary", "ACME", null, at(base)),
        )
        val r = HeavySpends.find(txns, 1_000_000, at(base, 0), at(base.plusDays(30)))
        assertEquals(listOf("Air", "TV"), r.rows.map { it.merchant })
        assertEquals(94, r.shareOfSpendPercent)
    }

    @Test fun filterByPhotoAndNote() {
        val t = TxnLite(10_000, TxnType.DEBIT, "Food", "Cafe", null, 0)
        assertTrue(!TxnFilter(withPhoto = true).matches(t, null, hasPhoto = false))
        assertTrue(TxnFilter(withPhoto = true).matches(t, null, hasPhoto = true))
        assertTrue(TxnFilter(noteText = "birthday").matches(t, "Anu's birthday cake"))
        assertTrue(!TxnFilter(noteText = "*").matches(t, null))
    }

    @Test fun monthCloseAndExport() {
        val oct = YearMonth.of(2026, 10)
        val txns = listOf(
            TxnLite(100_000, TxnType.DEBIT, "Food", "Cafe", null, at(LocalDate.of(2026, 10, 2))),
            TxnLite(500_000, TxnType.CREDIT, "Salary", "ACME", null, at(LocalDate.of(2026, 10, 1))),
        )
        val s = MonthClose.summary(oct, txns)
        assertEquals(100_000, s.spentMinor)
        assertEquals(listOf(oct, YearMonth.of(2026, 9)), MonthClose.openPastMonths(LocalDate.of(2026, 11, 9), emptySet(), YearMonth.of(2026, 9)))
        val sheets = FullExport.sheets(txns.map { ExportRow(it, listOf("Trip"), null, null, "SMS", false, false, false) }, zone)
        assertEquals(2, sheets.first().rows.size)
        assertTrue(FullExport.csv(txns.map { ExportRow(it, emptyList(), "a, b", null, "SMS", false, false, false) }, zone).contains("\"a, b\""))
    }
}
