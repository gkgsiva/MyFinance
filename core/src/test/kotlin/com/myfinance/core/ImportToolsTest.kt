package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportToolsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private fun t(date: LocalDate, amt: Long = 100_00, type: TxnType = TxnType.DEBIT, account: String? = "ICICI Bank ••4144", merchant: String? = "Shop", cat: String = DefaultCategories.OTHER, id: Long = 0) =
        TxnLite(amt, type, cat, merchant, account, date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), id = id)

    @Test fun missingMonthsPerAccount() {
        val rows = listOf(
            t(LocalDate.of(2018, 4, 3)), t(LocalDate.of(2018, 5, 3)), t(LocalDate.of(2018, 8, 3)),
            t(LocalDate.of(2026, 9, 3), account = "ICICI Bank ••7002"), t(LocalDate.of(2026, 7, 3), account = "ICICI Bank ••7002"),
        )
        val c = MissingStatements.find(rows, LocalDate.of(2026, 11, 10), zone)
        val old = c.first { it.account.endsWith("4144") }
        assertEquals(listOf(YearMonth.of(2018, 6), YearMonth.of(2018, 7)), old.missing)
        val card = c.first { it.account.endsWith("7002") }
        // Used recently: checked up to last month (October).
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 10)), card.missing)
        assertEquals(YearMonth.of(2026, 10), card.last)
    }

    @Test fun yearsAreFinancialYears() {
        val rows = listOf(t(LocalDate.of(2018, 4, 3), 500_00), t(LocalDate.of(2019, 3, 30), 100_00, TxnType.CREDIT), t(LocalDate.of(2019, 4, 1), 50_00))
        val y = ImportYears.of(rows, zone)
        assertEquals(listOf(2018, 2019), y.map { it.fyStartYear })
        assertEquals(500_00, y[0].spentMinor)
        assertEquals(100_00, y[0].receivedMinor)
    }

    @Test fun uncategorisedGroupedByPayee() {
        val rows = listOf(
            t(LocalDate.of(2026, 1, 1), merchant = "SOUTHERN DE", id = 1), t(LocalDate.of(2026, 1, 2), merchant = "Southern De", id = 2),
            t(LocalDate.of(2026, 1, 3), merchant = "Other", id = 3), t(LocalDate.of(2026, 1, 3), merchant = "Swiggy", cat = DefaultCategories.FOOD, id = 4),
        )
        val g = Uncategorised.groups(rows)
        assertEquals(2, g.size)
        assertEquals(listOf(1L, 2L), g.first().ids)
    }

    @Test fun backupNudge() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        assertTrue(BackupNudge.isDue(null, 60 * day, null, true, now))
        assertFalse(BackupNudge.isDue(80 * day, 0, null, true, now))
        assertFalse(BackupNudge.isDue(null, 0, 90 * day, true, now))
        assertFalse(BackupNudge.isDue(null, 0, null, false, now))
    }
}
