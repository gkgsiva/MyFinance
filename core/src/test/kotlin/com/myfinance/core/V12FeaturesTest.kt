package com.myfinance.core

import java.time.LocalDate
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalsTest {
    private val start = LocalDate.of(2026, 1, 1)

    @Test fun onTrackAndPerMonth() {
        val g = SavingsGoal("1", "Vacation", 1_00_000_00, LocalDate.of(2026, 6, 30), 50_000_00, start)
        val p = Goals.progress(g, LocalDate.of(2026, 3, 15))
        assertEquals(50, p.percent)
        assertEquals(GoalStatus.ON_TRACK, p.status)
        // March..June = 4 months for the remaining ₹50,000.
        assertEquals(12_500_00, p.perMonthNeededMinor)
    }

    @Test fun behindOverdueAndDone() {
        val g = SavingsGoal("1", "Bike", 1_00_000_00, LocalDate.of(2026, 6, 30), 10_000_00, start)
        assertEquals(GoalStatus.BEHIND, Goals.progress(g, LocalDate.of(2026, 5, 1)).status)
        assertEquals(GoalStatus.OVERDUE, Goals.progress(g, LocalDate.of(2026, 7, 1)).status)
        assertEquals(GoalStatus.DONE, Goals.progress(g.copy(savedMinor = 1_20_000_00), LocalDate.of(2026, 7, 1)).status)
        assertEquals(GoalStatus.NO_DEADLINE, Goals.progress(g.copy(deadline = null), start).status)
    }
}

class NetWorthTest {
    @Test fun parsesSip() {
        val e = InvestmentSmsParser.parse("Your SIP of Rs.5,000.00 in Axis Bluechip Fund - Growth has been processed. Units allotted: 98.123", 0)
        assertNotNull(e)
        assertEquals(AssetKind.SIP, e.kind)
        assertEquals(5_000_00, e.amountMinor)
        assertEquals("Axis Bluechip Fund - Growth", e.name)
        assertEquals("98.123", e.units)
        assertFalse(e.redemption)
    }

    @Test fun parsesMfPurchaseFdStockAndRedemption() {
        val mf = InvestmentSmsParser.parse("Dear Investor, purchase of Rs 10000 in HDFC Flexi Cap Fund allotted 25.5 units at NAV 392.1", 0)!!
        assertEquals(AssetKind.MUTUAL_FUND, mf.kind); assertEquals("HDFC Flexi Cap Fund", mf.name)

        val fd = InvestmentSmsParser.parse("Your FD No. XX4521 for Rs 1,00,000.00 has been booked @ 7.10% p.a.", 0)!!
        assertEquals(AssetKind.FD, fd.kind); assertEquals(1_00_000_00, fd.amountMinor); assertEquals("Fixed deposit ••4521", fd.name)

        val st = InvestmentSmsParser.parse("Bought 10 INFY @ 1,520.50 on NSE. Trade value Rs 15,205.00", 0)!!
        assertEquals(AssetKind.STOCK, st.kind); assertEquals("INFY", st.name); assertEquals(15_205_00, st.amountMinor)

        val rd = InvestmentSmsParser.parse("Redemption of Rs 20,000 from SBI Small Cap Fund processed", 0)!!
        assertTrue(rd.redemption); assertEquals("SBI Small Cap Fund", rd.name)
    }

    @Test fun ignoresRemindersAndNonInvestments() {
        assertNull(InvestmentSmsParser.parse("Your SIP of Rs 5000 in Axis Bluechip Fund will be debited on 05-12", 0))
        assertNull(InvestmentSmsParser.parse("Rs 500 debited from a/c XX1234 to swiggy", 0))
    }

    @Test fun totals() {
        val events = listOf(
            InvestmentEvent(AssetKind.SIP, "Axis Bluechip", 5_000_00, false, null, null, 0),
            InvestmentEvent(AssetKind.SIP, "axis bluechip", 5_000_00, false, null, 11_000_00, 1),
            InvestmentEvent(AssetKind.FD, "FD", 1_00_000_00, false, null, null, 2),
        )
        val holdings = NetWorth.applyEvents(listOf(Holding("HDFC ••1234", AssetKind.BANK, 50_000_00)), events)
        val s = NetWorth.of(holdings, listOf(Liability("Car loan", LiabilityKind.CAR_LOAN, 2_00_000_00)))
        assertEquals(1_61_000_00, s.assetsMinor)
        assertEquals(-39_000_00, s.netWorthMinor)
        assertEquals(1_000_00, s.gainMinor)
    }
}

class BillRemindersTest {
    private val bill = BillReminder("1", "Electricity", ReminderKind.ELECTRICITY, 2_000_00, LocalDate.of(2026, 11, 10), 1, 3, listOf("bescom"))

    @Test fun states() {
        assertEquals(BillState.UPCOMING, BillReminders.status(bill, LocalDate.of(2026, 11, 1)).state)
        assertEquals(BillState.DUE_SOON, BillReminders.status(bill, LocalDate.of(2026, 11, 8)).state)
        assertEquals(BillState.DUE_TODAY, BillReminders.status(bill, LocalDate.of(2026, 11, 10)).state)
        assertEquals(BillState.OVERDUE, BillReminders.status(bill, LocalDate.of(2026, 11, 12)).state)
        assertEquals(1, BillReminders.needingAttention(listOf(bill), LocalDate.of(2026, 11, 9)).size)
    }

    @Test fun markPaidAndMatch() {
        assertEquals(LocalDate.of(2026, 12, 10), BillReminders.markPaid(bill).nextDue)
        assertEquals(LocalDate.of(2027, 11, 10), BillReminders.markPaid(bill.copy(everyMonths = 12)).nextDue)
        assertTrue(BillReminders.matchesPayment(bill, "BESCOM", null, 2_100_00, LocalDate.of(2026, 11, 7)))
        assertFalse(BillReminders.matchesPayment(bill, "BESCOM", null, 5_000_00, LocalDate.of(2026, 11, 7)))
        assertFalse(BillReminders.matchesPayment(bill, "swiggy", null, 2_000_00, LocalDate.of(2026, 11, 7)))
    }
}

class SplitsTest {
    @Test fun equalSharesKeepPaise() {
        val s = Splits.equalShares(100_00 + 1, listOf("Siva", "Priya", "Anu"))
        assertEquals(10001, s.values.sum())
        assertEquals(3334, s["Siva"])
    }

    @Test fun balancesAndSettleUp() {
        val dinner = SplitExpense("1", "Dinner", "Siva", 3_000_00, Splits.equalShares(3_000_00, listOf("Siva", "Priya", "Ravi")), 0)
        val cab = SplitExpense("2", "Cab", "Priya", 600_00, mapOf("Priya" to 300_00, "Ravi" to 300_00), 0)
        var bal = Splits.balances(listOf(dinner, cab), emptyList())
        assertEquals(2_000_00, bal["Siva"])
        assertEquals(-1_300_00, bal["Ravi"])
        assertEquals(-700_00, bal["Priya"])
        val plan = Splits.settleUp(bal)
        assertEquals(listOf(Transfer("Ravi", "Siva", 1_300_00), Transfer("Priya", "Siva", 700_00)), plan)

        bal = Splits.balances(listOf(dinner, cab), listOf(Settlement("Ravi", "Siva", 1_300_00, 1), Settlement("Priya", "Siva", 700_00, 1)))
        assertTrue(bal.isEmpty())
    }
}

class CashWalletTest {
    @Test fun summary() {
        val s = CashWallet.summary(
            listOf(
                CashEntry(5_000_00, true, DefaultCategories.CASH, null, 0),
                CashEntry(1_200_00, false, DefaultCategories.GROCERIES, "vegetables", 1),
                CashEntry(300_00, false, DefaultCategories.TRANSPORT, "auto", 2),
            ),
        )
        assertEquals(3_500_00, s.inHandMinor)
        assertEquals(DefaultCategories.GROCERIES, s.byCategory.first().key)
        assertTrue(CashWallet.isAtmWithdrawal(DefaultCategories.OTHER, "Rs 2000 withdrawn at ATM HDFC0001"))
    }
}

class TaxHelperTest {
    @Test fun suggestsSections() {
        assertEquals(TaxSection.S80C, TaxHelper.suggest("PPF deposit", DefaultCategories.INVESTMENTS))
        assertEquals(TaxSection.S80C, TaxHelper.suggest("LIC", DefaultCategories.BILLS))
        assertEquals(TaxSection.S80D, TaxHelper.suggest("Star Health", DefaultCategories.BILLS))
        assertEquals(TaxSection.S80D_PARENTS, TaxHelper.suggest("Star Health", DefaultCategories.BILLS, "parents policy"))
        assertNull(TaxHelper.suggest("swiggy", DefaultCategories.FOOD))
    }

    @Test fun financialYearAndCaps() {
        assertEquals(2025, TaxHelper.fyStartYear(LocalDate.of(2026, 3, 31)))
        assertEquals(2026, TaxHelper.fyStartYear(LocalDate.of(2026, 4, 1)))
        assertEquals("FY 2026-27", TaxHelper.fyLabel(2026))
        val r = TaxHelper.fyRange(2026)
        val items = listOf(
            TaxItem(TaxSection.S80C, 1_00_000_00, r.first + 1, "PPF"),
            TaxItem(TaxSection.S80C, 80_000_00, r.first + 2, "ELSS"),
            TaxItem(TaxSection.S80D, 20_000_00, r.first + 3, null),
            TaxItem(TaxSection.S80C, 50_000_00, r.first - 1, "last year"),
        )
        val t = TaxHelper.yearTotals(items, 2026)
        val c = t.first { it.section == TaxSection.S80C }
        assertEquals(1_80_000_00, c.claimedMinor)
        assertEquals(1_50_000_00, c.countedMinor)
        assertEquals(0, c.remainingMinor)
        assertEquals(1_70_000_00, TaxHelper.totalDeduction(t))
    }
}

class CustomRulesTest {
    @Test fun firstMatchingRuleWins() {
        val rules = listOf(
            CustomRule("a", "SWIGGY", setCategory = DefaultCategories.FOOD, priority = 2),
            CustomRule("b", "swiggy instamart", setCategory = DefaultCategories.GROCERIES, priority = 1),
            CustomRule("c", "^VM-PAYTM", field = RuleField.SENDER, isRegex = true, ignore = true),
            CustomRule("d", "self", field = RuleField.MERCHANT, exclude = true, onlyType = TxnType.DEBIT),
        )
        fun run(sender: String, body: String, merchant: String?, type: TxnType = TxnType.DEBIT) =
            CustomRules.apply(rules, RuleInput(sender, body, merchant, type, 100_00))
        assertEquals(DefaultCategories.FOOD, run("VM-HDFCBK", "Rs 100 paid to SWIGGY", "swiggy")?.category)
        assertEquals(DefaultCategories.GROCERIES, run("VM-HDFCBK", "Rs 100 paid to Swiggy Instamart", "swiggy instamart")?.category)
        assertEquals(true, run("VM-PAYTM", "Rs 100 paid", null)?.ignore)
        assertEquals(true, run("VM-HDFCBK", "Rs 100 sent", "Self")?.exclude)
        assertNull(run("VM-HDFCBK", "Rs 100 received", "Self", TxnType.CREDIT))
        assertNull(run("VM-HDFCBK", "Rs 100 paid to zomato", "zomato"))
    }

    @Test fun validation() {
        assertFalse(CustomRules.isValid(CustomRule("x", "(", isRegex = true, setCategory = "Food")))
        assertFalse(CustomRules.isValid(CustomRule("x", "swiggy")))
        assertTrue(CustomRules.isValid(CustomRule("x", "swiggy", setCategory = "Food")))
    }
}

class SecurityTest {
    @Test fun pinHashVerifies() {
        val h = PinHasher.hash("4821")
        assertTrue(PinHasher.verify("4821", h))
        assertFalse(PinHasher.verify("4822", h))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertEquals(0, PinHasher.lockoutMillis(4))
        assertEquals(30_000, PinHasher.lockoutMillis(5))
        assertEquals(3_600_000, PinHasher.lockoutMillis(50))
    }

    @Test fun backupRoundTripAndWrongPassword() {
        val files = mapOf("transactions.csv" to "uid,amount\n1,100".toByteArray(), "receipts/a.jpg" to ByteArray(3000) { (it % 7).toByte() })
        val enc = BackupCrypto.encrypt(files, "correct horse".toCharArray(), SecureRandom(), iterations = 10_000)
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("uid,amount"))
        val dec = BackupCrypto.decrypt(enc, "correct horse".toCharArray())
        assertEquals(files.keys, dec.keys)
        assertTrue(files.all { (k, v) -> v.contentEquals(dec[k]) })
        assertFailsWith<WrongPasswordException> { BackupCrypto.decrypt(enc, "wrong password".toCharArray()) }
        val tampered = enc.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<WrongPasswordException> { BackupCrypto.decrypt(tampered, "correct horse".toCharArray()) }
        assertFailsWith<NotABackupException> { BackupCrypto.decrypt("hello world, not a backup at all......................".toByteArray(), "x".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { BackupCrypto.encrypt(files, "short".toCharArray()) }
    }
}
