package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountPeriodsTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun t(id: Long, date: String, amount: Long, account: String?, type: TxnType = TxnType.DEBIT) = TxnLite(
        amount, type, "Food & Dining", "Shop", account,
        LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), id = id,
    )

    private val txns = listOf(
        t(1, "2024-03-30", 1_000, "HDFC Bank ••1234"),
        t(2, "2024-04-02", 5_000, "ICICI Bank ••9876"),
        t(3, "2024-04-05", 2_000, "HDFC Bank ••1234"),
        t(4, "2024-04-20", 9_000, "HDFC Bank ••1234", TxnType.CREDIT),
        t(5, "2024-04-21", 300, null),
    )

    @Test
    fun monthsByAccount() {
        val g = AccountPeriods.group(txns, Period.MONTH, zone)
        assertEquals(listOf("April 2024", "March 2024"), g.map { it.label })
        val april = g.first()
        assertEquals(listOf("ICICI Bank ••9876", "HDFC Bank ••1234", AccountPeriods.NO_ACCOUNT), april.accounts.map { it.account })
        val hdfc = april.accounts[1]
        assertEquals(2_000, hdfc.spentMinor)
        assertEquals(9_000, hdfc.receivedMinor)
        assertEquals(listOf(4L, 3L), hdfc.ids)
        assertEquals(7_300, april.spentMinor)
    }

    @Test
    fun financialYearSplitsAtApril() {
        val g = AccountPeriods.group(txns, Period.FINANCIAL_YEAR, zone)
        assertEquals(listOf("FY 2024-25", "FY 2023-24"), g.map { it.label })
        assertEquals(4, g[0].count)
        assertEquals(listOf("Q2 2024 (Apr–Jun)", "Q1 2024 (Jan–Mar)"), AccountPeriods.group(txns, Period.QUARTER, zone).map { it.label })
        assertEquals(listOf("2024"), AccountPeriods.group(txns, Period.YEAR, zone).map { it.label })
    }

    @Test
    fun accountChipsBusiestFirst() {
        assertEquals(listOf("HDFC Bank ••1234", AccountPeriods.NO_ACCOUNT, "ICICI Bank ••9876"), AccountPeriods.accounts(txns))
    }
}
