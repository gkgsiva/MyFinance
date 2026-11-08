package com.myfinance.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CategorizerAndSummaryTest {

    private val c = Categorizer()

    @Test
    fun keywordCategories() {
        assertEquals(DefaultCategories.FOOD, c.categorize("SWIGGY", TxnType.DEBIT))
        assertEquals(DefaultCategories.GROCERIES, c.categorize("blinkit.payu", TxnType.DEBIT))
        assertEquals(DefaultCategories.SHOPPING, c.categorize("AMAZON PAY INDIA", TxnType.DEBIT))
        assertEquals(DefaultCategories.TRANSPORT, c.categorize("Uber India", TxnType.DEBIT))
        assertEquals(DefaultCategories.SALARY, c.categorize("ACME", TxnType.CREDIT, "credited ... SALARY for Oct"))
        assertEquals(DefaultCategories.OTHER, c.categorize("RANDOM SHOP", TxnType.DEBIT))
        assertEquals(DefaultCategories.INCOME, c.categorize("RAHUL KUMAR", TxnType.CREDIT))
    }

    @Test
    fun shortKeywordsNeedWholeWord() {
        // "ola" must not match "Coca Cola" and "emi" must not match "Emirates".
        assertEquals(DefaultCategories.OTHER, c.categorize("Nolan Stores", TxnType.DEBIT))
        assertEquals(DefaultCategories.TRANSPORT, c.categorize("OLA CABS", TxnType.DEBIT))
    }

    @Test
    fun userRulesWin() {
        val learned = Categorizer(mapOf(Categorizer.merchantKey("SWIGGY") to "Office Lunch"))
        assertEquals("Office Lunch", learned.categorize("Swiggy", TxnType.DEBIT))
    }

    @Test
    fun atmIsCash() {
        assertEquals(DefaultCategories.CASH, c.categorize(null, TxnType.DEBIT, "Rs.5000 withdrawn at SBI ATM"))
    }

    @Test
    fun summaryGroupsExpenses() {
        val txns = listOf(
            TxnLite(10000, TxnType.DEBIT, "Food", "Swiggy", "1234", 0),
            TxnLite(5000, TxnType.DEBIT, "Food", "Zomato", "1234", 0),
            TxnLite(20000, TxnType.DEBIT, "Shopping", "Amazon", "4321", 0),
            TxnLite(100000, TxnType.CREDIT, "Salary", "ACME", "1234", 0),
        )
        val s = FinanceSummary.of(txns)
        assertEquals(100000, s.incomeMinor)
        assertEquals(35000, s.expenseMinor)
        assertEquals(65000, s.netMinor)
        assertEquals(65, s.savingsRatePercent)
        assertEquals(listOf("Shopping" to 20000L, "Food" to 15000L), s.byCategory.map { it.key to it.totalMinor })
        assertEquals(listOf("4321" to 20000L, "1234" to 15000L), s.byAccount.map { it.key to it.totalMinor })
    }

    @Test
    fun formatting() {
        assertEquals("₹1,23,456.78", formatInr(12345678))
        assertEquals("₹500", formatInr(50000))
        assertEquals("₹999.05", formatInr(99905))
        assertEquals(123450L, parseAmountToMinor("1,234.5"))
        assertNull(parseAmountToMinor("abc"))
    }
}
