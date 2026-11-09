package com.myfinance.core

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
private val today: LocalDate = LocalDate.of(2026, 11, 9)
private var nextId = 1L

private fun txn(date: String, rupees: Long, merchant: String?, category: String, type: TxnType = TxnType.DEBIT, account: String? = null) =
    TxnLite(
        rupees * 100, type, category, merchant, account,
        LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), id = nextId++,
    )

class AskTest {
    private val txns = listOf(
        txn("2025-03-10", 500, "Swiggy", DefaultCategories.FOOD),
        txn("2025-08-01", 300, "Swiggy", DefaultCategories.FOOD),
        txn("2025-08-02", 100, "Swiggy refund", DefaultCategories.FOOD, TxnType.CREDIT),
        txn("2026-01-05", 200, "Swiggy", DefaultCategories.FOOD),
        txn("2026-11-03", 400, "Zomato", DefaultCategories.FOOD),
        txn("2026-10-01", 250, "Uber", DefaultCategories.TRANSPORT),
        txn("2026-09-15", 150, "Uber", DefaultCategories.TRANSPORT),
        txn("2026-05-01", 100, "Uber", DefaultCategories.TRANSPORT),
        txn("2025-04-30", 100_000, "ACME CORP", DefaultCategories.SALARY, TxnType.CREDIT),
        txn("2025-12-31", 100_000, "ACME CORP", DefaultCategories.SALARY, TxnType.CREDIT),
        txn("2026-10-31", 110_000, "ACME CORP", DefaultCategories.SALARY, TxnType.CREDIT),
    )

    private fun ask(q: String) = Ask.ask(q, today, txns, zone = zone)

    @Test fun merchantLastYearWithRefund() {
        val a = ask("How much on Swiggy last year?")
        assertEquals(AskSubjectKind.MERCHANT, a.query.subject?.kind)
        assertEquals("swiggy", a.query.subject?.term)
        assertEquals(LocalDate.of(2025, 1, 1), a.query.period?.from)
        assertEquals(LocalDate.of(2025, 12, 31), a.query.period?.to)
        assertEquals(800_00, a.totalMinor)
        assertEquals(2, a.count)
        assertTrue(a.headline.startsWith("₹800 on Swiggy last year"), a.headline)
        assertNotNull(a.note)
    }

    @Test fun countInLastThreeMonths() {
        val a = ask("How many times Uber in the last 3 months?")
        assertEquals(AskMetric.COUNT, a.query.metric)
        assertEquals(2, a.count)
    }

    @Test fun salaryInYear() {
        val a = ask("Salary in 2025")
        assertEquals(AskMetric.INCOME, a.query.metric)
        assertEquals(TxnType.CREDIT, a.query.type)
        assertEquals(2_00_000_00, a.totalMinor)
    }

    @Test fun categoryWordThisMonth() {
        val a = ask("Food spend this month")
        assertEquals(AskSubjectKind.CATEGORY, a.query.subject?.kind)
        assertEquals(DefaultCategories.FOOD, a.query.subject?.term)
        assertEquals(400_00, a.totalMinor)
    }

    @Test fun biggestInNamedMonth() {
        val a = ask("Biggest payment in March 2025")
        assertEquals(AskMetric.BIGGEST, a.query.metric)
        assertTrue(a.headline.startsWith("Biggest: ₹500 at Swiggy"), a.headline)
    }

    @Test fun spellingSlip() {
        val a = ask("swigy last year")
        assertEquals("swiggy", a.query.subject?.term)
        assertEquals(800_00, a.totalMinor)
    }

    @Test fun topLastMonth() {
        val a = ask("Where did I spend most last month?")
        assertEquals(AskMetric.TOP, a.query.metric)
        assertEquals(DefaultCategories.TRANSPORT, a.top.first().name)
    }

    @Test fun periods() {
        val fy = Ask.parsePeriod("income this financial year", today)!!
        assertEquals(LocalDate.of(2026, 4, 1), fy.from)
        assertEquals(today, fy.to)
        val named = Ask.parsePeriod("fy 2024-25", today)!!
        assertEquals(LocalDate.of(2024, 4, 1), named.from)
        assertEquals(LocalDate.of(2025, 3, 31), named.to)
        val week = Ask.parsePeriod("last week", today)!!
        assertEquals(LocalDate.of(2026, 11, 2), week.from)
        assertEquals(LocalDate.of(2026, 11, 8), week.to)
        assertNull(Ask.parsePeriod("how much on swiggy", today))
    }

    @Test fun nothingFound() {
        val a = ask("How much on Swiggy yesterday?")
        assertEquals(0, a.count)
        assertTrue(a.headline.startsWith("Nothing found"))
    }
}

class TaxPackTest {
    @Test fun interestKinds() {
        val c = txn("2025-06-30", 1234, null, DefaultCategories.INCOME, TxnType.CREDIT)
        assertEquals(InterestKind.SAVINGS, TaxPack.interestKind(c, "Rs 1,234 credited to a/c XX12 towards INT.PD for the quarter"))
        assertEquals(InterestKind.DEPOSIT, TaxPack.interestKind(c, "Interest of Rs 5,000 on your FD no 1234 credited"))
        assertNull(TaxPack.interestKind(c, "Rs 500 credited by UPI from Ravi"))
        assertNull(TaxPack.interestKind(c, "Interest reversal on credit card"))
    }

    @Test fun gainTerms() {
        fun e(asset: GainAsset, buy: String, sell: String) =
            CapitalGainEntry("1", "X", asset, LocalDate.parse(buy), 1_00_000_00, LocalDate.parse(sell), 1_20_000_00)
        assertTrue(TaxPack.gain(e(GainAsset.EQUITY, "2024-01-10", "2025-01-11")).longTerm)
        assertFalse(TaxPack.gain(e(GainAsset.EQUITY, "2024-01-10", "2025-01-10")).longTerm)
        assertFalse(TaxPack.gain(e(GainAsset.EQUITY, "2024-06-01", "2025-05-01")).longTerm)
        assertFalse(TaxPack.gain(e(GainAsset.DEBT_FUND, "2020-01-01", "2025-05-01")).longTerm)
        assertEquals(20_000_00, TaxPack.gain(e(GainAsset.GOLD, "2020-01-01", "2025-05-01")).gainMinor)
    }

    @Test fun buildsYear() {
        val s1 = txn("2025-04-30", 100_000, "ACME", DefaultCategories.SALARY, TxnType.CREDIT)
        val s2 = txn("2025-12-31", 100_000, "ACME", DefaultCategories.SALARY, TxnType.CREDIT)
        val outside = txn("2026-04-02", 100_000, "ACME", DefaultCategories.SALARY, TxnType.CREDIT)
        val int = txn("2025-09-30", 12_000, null, DefaultCategories.INCOME, TxnType.CREDIT)
        val items = listOf(
            TaxItem(TaxSection.S80C, 2_00_000_00, s1.timestamp, "PPF"),
        )
        val sale = CapitalGainEntry("g", "Nifty fund", GainAsset.EQUITY, LocalDate.of(2023, 1, 1), 50_000_00, LocalDate.of(2025, 6, 1), 80_000_00)
        val p = TaxPack.build(2025, listOf(s1, s2, outside, int), mapOf(int.id to "SB INT PD Rs 12000"), items, listOf(sale), zone)
        assertEquals(2_00_000_00, p.salaryMinor)
        assertEquals(12_000_00, p.savingsInterestMinor)
        assertEquals(10_000_00, p.tta80Minor)
        assertEquals(1_50_000_00, p.deductionTotalMinor)
        assertEquals(30_000_00, p.gainTotals.equityLongMinor)

        val out = ByteArrayOutputStream()
        XlsxBook.write(out, TaxPack.sheets(p, zone))
        val names = ArrayList<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { names += (z.nextEntry ?: break).name } }
        assertTrue("xl/worksheets/sheet4.xml" in names)
        assertTrue(TaxPack.summaryText(p).contains("FY 2025-26"))
    }
}

class VaultAndLendingTest {
    @Test fun vaultStates() {
        val p = Policy("p", "Family floater", PolicyKind.HEALTH, "Star Health", "P1", 10_00_000_00, 25_000_00, today.plusDays(7))
        assertEquals(VaultState.URGENT, Vault.policyStatus(p, today).state)
        assertEquals(today.plusDays(7).plusYears(1), Vault.renewed(p).renewalDate)
        val w = Warranty("w", "Fridge", "Croma", LocalDate.of(2025, 11, 10), 40_000_00, 12)
        assertEquals(today, w.endDate)
        assertEquals(VaultState.DUE_TODAY, Vault.warrantyStatus(w, today).state)
        assertEquals(2, Vault.reminders(listOf(p), listOf(w), today).size)
        assertEquals(25_000_00, Vault.yearlyPremium(listOf(p)))
    }

    @Test fun lending() {
        val lent = LendEntry("a", "Anil Kumar", LendDirection.LENT, 5_000_00, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), "rent")
        val borrowed = LendEntry("b", "Ravi", LendDirection.BORROWED, 1_000_00, LocalDate.of(2026, 10, 5), null, null)
        val reps = listOf(LendRepayment("r", "a", 2_000_00, LocalDate.of(2026, 10, 20)))
        val s1 = Lending.status(lent, reps, today)
        assertEquals(3_000_00, s1.outstandingMinor)
        assertEquals(LendState.OVERDUE, s1.state)
        val sum = Lending.summary(listOf(s1, Lending.status(borrowed, reps, today)))
        assertEquals(3_000_00, sum.owedToYouMinor)
        assertEquals(1_000_00, sum.youOweMinor)
        assertEquals(-1_000_00, sum.people.first { it.person == "Ravi" }.netMinor)
        val msg = Lending.reminderMessage(s1, "Siva")
        assertTrue(msg.startsWith("Hi Anil,"), msg)
        assertTrue(msg.contains("₹3,000") && msg.contains("1 Nov 2026"), msg)
        assertFalse(Lending.remindToday(s1))
    }
}

class GoalLinksTest {
    @Test fun monthsToReach() {
        assertEquals(12, GoalLinks.monthsToReach(0, 12_000, 1_000, 0.0))
        assertEquals(6, GoalLinks.monthsToReach(6_000, 12_000, 1_000, 0.0))
        assertNull(GoalLinks.monthsToReach(0, 12_000, 0, 0.0))
        assertEquals(10, GoalLinks.monthsToReach(0, 12_000, 1_000, 60.0))
    }

    @Test fun linkedSipForecast() {
        val events = (8..11).map { m -> GoalMoney("Axis Bluechip Fund - Growth", 5_000_00, LocalDate.of(2026, m, 5)) }
        val link = GoalLink("l", "g", GoalLinkKind.INVESTMENT, "Axis Bluechip", LocalDate.of(2026, 8, 1))
        val amt = GoalLinks.linkedAmount(link, events, emptyMap(), emptyMap(), today)
        assertEquals(20_000_00, amt.savedMinor)
        assertEquals(5_000_00, amt.monthlyMinor)
        val goal = SavingsGoal("g", "Car", 1_00_000_00, LocalDate.of(2027, 12, 31), 0, LocalDate.of(2026, 8, 1))
        val f = GoalLinks.forecast(goal, listOf(amt), today)
        assertEquals(YearMonth.of(2028, 3), f.reachBy)
        assertEquals(3, f.monthsVsDeadline)
        val holding = GoalLink("h", "g", GoalLinkKind.HOLDING, "Gold", LocalDate.of(2026, 1, 1))
        assertEquals(40_000_00, GoalLinks.linkedAmount(holding, events, mapOf("Gold coins" to 40_000_00), emptyMap(), today).savedMinor)
    }
}

class SubscriptionAndCalendarTest {
    private val netflix = listOf(
        txn("2026-08-05", 499, "NETFLIX.COM", DefaultCategories.ENTERTAINMENT, account = "HDFC ••1234"),
        txn("2026-09-05", 499, "NETFLIX.COM", DefaultCategories.ENTERTAINMENT, account = "HDFC ••1234"),
        txn("2026-10-05", 649, "NETFLIX.COM", DefaultCategories.ENTERTAINMENT, account = "HDFC ••1234"),
    )
    private val hotstar = txn("2026-03-01", 1499, "JioHotstar", DefaultCategories.ENTERTAINMENT)
    private val grocery = txn("2026-10-07", 900, "BigBasket", DefaultCategories.GROCERIES)

    @Test fun audit() {
        val debits = (netflix + hotstar + grocery).map { DebitRecord(it, null) }
        val rec = listOf(
            RecurringPayment("NETFLIX.COM", DefaultCategories.ENTERTAINMENT, 649_00, Cadence.MONTHLY, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 12, 5), 3),
        )
        val r = SubscriptionAudit.audit(rec, debits, notUsed = setOf("sub:jiohotstar"), ignored = emptySet(), today = today, zone = zone)
        assertEquals(2, r.items.size)
        val n = r.items.first { it.name == "Netflix" }
        assertTrue(SubFlag.PRICE_HIKE in n.flags)
        assertEquals(499_00, n.previousMinor)
        assertTrue(SubFlag.SAME_KIND in n.flags)
        val h = r.items.first { it.name == "JioHotstar" }
        assertEquals(Cadence.YEARLY, h.cadence)
        assertTrue(h.notUsed)
        assertEquals(1499_00, r.yearlySavingMinor)
        assertEquals(649_00 * 12 + 1499_00, r.yearlyTotalMinor)
    }

    @Test fun calendar() {
        val salary = listOf("2026-08-31", "2026-09-30", "2026-10-31").map { txn(it, 100_000, "ACME", DefaultCategories.SALARY, TxnType.CREDIT) }
        val spend = txn("2026-11-03", 400, "Zomato", DefaultCategories.FOOD)
        val emi = AutoDebitItem(
            key = "plan:1", name = "Car loan EMI", kind = AutoDebitKind.EMI, amountMinor = 12_000_00, cadenceLabel = "Monthly",
            nextDate = LocalDate.of(2026, 11, 15), daysLeft = 6, state = DueState.UPCOMING, source = AutoDebitSource.MANUAL,
        )
        val bill = BillReminder("b", "Car loan EMI", ReminderKind.LOAN_EMI, 12_000_00, LocalDate.of(2026, 11, 15))
        val m = CashFlowCalendar.build(YearMonth.of(2026, 11), today, salary + spend, listOf(emi), listOf(bill), zone = zone)
        assertEquals(400_00, m.actualOutMinor)
        val d15 = m.days[14]
        assertEquals(1, d15.events.size)
        assertEquals(12_000_00, d15.expectedOutMinor)
        val d30 = m.days[29]
        assertEquals(FlowKind.SALARY, d30.events.single().kind)
        assertEquals(1_00_000_00, m.expectedInMinor)
        // Next month repeats the EMI.
        val dec = CashFlowCalendar.build(YearMonth.of(2026, 12), today, salary, listOf(emi), zone = zone)
        assertEquals(12_000_00, dec.days[14].expectedOutMinor)
    }

    @Test fun backupSchedule() {
        val now = 1_000_000_000_000L
        val h = 60 * 60 * 1000L
        assertTrue(AutoBackupSchedule.isDue(null, BackupFrequency.DAILY, now))
        assertTrue(AutoBackupSchedule.isDue(now - 23 * h, BackupFrequency.DAILY, now))
        assertFalse(AutoBackupSchedule.isDue(now - 10 * h, BackupFrequency.DAILY, now))
        assertFalse(AutoBackupSchedule.isDue(now - 3 * 24 * h, BackupFrequency.WEEKLY, now))
        assertTrue(AutoBackupSchedule.isStale(now - 4 * 24 * h, BackupFrequency.DAILY, now))
    }
}

class EpfAndOnceBillTest {
    @Test fun epfoSms() {
        val e = InvestmentSmsParser.parse(
            "Dear Member, your passbook balance against TNMAS00123450000012345 is Rs. 4,56,789/-. Contribution of Rs. 3,600/- for due month 102026 has been received. - EPFO",
            0,
        )
        assertNotNull(e)
        assertEquals(AssetKind.EPF, e.kind)
        assertEquals(3_600_00, e.amountMinor)
        assertEquals(4_56_789_00, e.currentValueMinor)
    }

    @Test fun onlyOnceBillIsNotRepeated() {
        val bill = BillReminder("b", "School fees", ReminderKind.SCHOOL_FEES, 20_000_00, LocalDate.of(2026, 11, 20), everyMonths = 0)
        val nov = CashFlowCalendar.build(YearMonth.of(2026, 11), today, emptyList(), bills = listOf(bill), zone = zone)
        assertEquals(20_000_00, nov.expectedOutMinor)
        val dec = CashFlowCalendar.build(YearMonth.of(2026, 12), today, emptyList(), bills = listOf(bill), zone = zone)
        assertEquals(0, dec.expectedOutMinor)
    }
}
