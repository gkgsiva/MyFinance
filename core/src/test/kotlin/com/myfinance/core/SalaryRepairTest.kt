package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class SalaryRepairTest {
    private fun at(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun salarySmsWinsOverAPlainStatementLine() {
        assertEquals("Salary", SalaryRepair.mergedCategory("Transfers", "Salary", lineEdited = false, smsEdited = false))
        assertEquals("Salary", SalaryRepair.mergedCategory(DefaultCategories.INCOME, "Salary", lineEdited = false, smsEdited = false))
        // A generic line category gives way to a specific SMS one.
        assertEquals("Food", SalaryRepair.mergedCategory(DefaultCategories.OTHER, "Food", lineEdited = false, smsEdited = false))
        assertEquals("Shopping", SalaryRepair.mergedCategory("Shopping", "Food", lineEdited = false, smsEdited = false))
        // The user's choice always wins.
        assertEquals("Transfers", SalaryRepair.mergedCategory("Transfers", "Salary", lineEdited = true, smsEdited = false))
        assertEquals("Rent", SalaryRepair.mergedCategory("Transfers", "Rent", lineEdited = false, smsEdited = true))
    }

    @Test
    fun linesThatLostTheirSalarySmsGoBackToSalary() {
        val smsText = "Acct XX144 is credited with Rs 1,50,000.00 on 24-Jul-26 from RNTBCI. UPI/NEFT ref 1234"
        val line = SalaryRepair.Row(10, TxnType.CREDIT, 150_000_00, at(2026, 7, 24), "Transfers",
            "Statement: NEFT-CITIN26123-RENAULT NISSAN TECH\n\nSMS: $smsText", userEdited = false)
        val sameAmountOtherDay = SalaryRepair.Row(11, TxnType.CREDIT, 150_000_00, at(2026, 6, 1), "Transfers", "Statement: NEFT-X", false)
        val edited = SalaryRepair.Row(12, TxnType.CREDIT, 90_000_00, at(2026, 6, 26), "Income", "Statement: NEFT SALARY JUN", userEdited = true)
        val saysSalary = SalaryRepair.Row(13, TxnType.CREDIT, 90_000_00, at(2026, 5, 26), "Transfers", "Statement: NEFT SALARY MAY 2026", false)
        val alreadySalary = SalaryRepair.Row(14, TxnType.CREDIT, 90_000_00, at(2026, 4, 26), "Salary", "Statement: SALARY APR", false)
        val shopRefund = SalaryRepair.Row(15, TxnType.CREDIT, 500_00, at(2026, 4, 26), "Shopping", "Refund from salary-store", false)
        val removedSms = SalaryRepair.Row(99, TxnType.CREDIT, 150_000_00, at(2026, 7, 24), "Salary", smsText, false)
        val ids = SalaryRepair.toRestore(listOf(line, sameAmountOtherDay, edited, saysSalary, alreadySalary, shopRefund), listOf(removedSms))
        assertEquals(setOf(10L, 13L), ids.toSet())
    }

    @Test
    fun restoredSalaryShowsInTheSalaryMonth() {
        // After the repair the 24 July salary counts as August's income, as the note on the month says.
        val c = MonthCycle(salaryFromDay = 25)
        val t = TxnLite(150_000_00, TxnType.CREDIT, "Salary", "RNTBCI", null, at(2026, 7, 24))
        val aug = YearMonth.of(2026, 8)
        assertEquals(aug, c.periodOf(t, ZoneOffset.UTC))
        val ledger = MonthLedger.build(listOf(t, TxnLite(1_170_00, TxnType.CREDIT, "Income", "Friend", null, at(2026, 8, 5))), aug, c, ZoneOffset.UTC, emptySet())
        assertEquals(151_170_00, ledger.inMinor)
    }
}
