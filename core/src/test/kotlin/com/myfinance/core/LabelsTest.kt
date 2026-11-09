package com.myfinance.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LabelsTest {
    private fun d(id: Long, amount: Long, type: TxnType = TxnType.DEBIT) = TxnLite(amount, type, "Other", "M$id", null, 0L, id = id)

    @Test
    fun countsEachLabelAndUnlabelledLast() {
        val txns = listOf(d(1, 100), d(2, 300), d(3, 50), d(4, 999, TxnType.CREDIT))
        val labels = mapOf(1L to listOf("Food", "Kids"), 2L to listOf("EMI"), 4L to listOf("EMI"))
        val out = Labels.spendByLabel(txns, labels)
        assertEquals(listOf("EMI", "Food", "Kids", Labels.NONE), out.map { it.key })
        assertEquals(300, out[0].totalMinor)
        assertEquals(50, out.last().totalMinor)
    }

    @Test
    fun choicesPutUsedFirstWithoutDuplicates() {
        val c = Labels.choices(listOf("grocery", "Office"))
        assertEquals("grocery", c[0])
        assertEquals("Office", c[1])
        assertEquals(1, c.count { it.equals("grocery", ignoreCase = true) })
    }

    @Test
    fun refundsNetAgainstSpendForALabel() {
        val txns = listOf(d(1, 150_000), d(2, 80_000), d(3, 150_000, TxnType.CREDIT), d(4, 5_000_000, TxnType.CREDIT))
        val labels = mapOf(1L to listOf("IRCTC"), 2L to listOf("IRCTC"), 3L to listOf("IRCTC"), 4L to listOf("Salary"))
        val out = Labels.netByLabel(txns, labels).associateBy { it.label }
        assertEquals(230_000, out.getValue("IRCTC").spentMinor)
        assertEquals(150_000, out.getValue("IRCTC").receivedMinor)
        assertEquals(80_000, out.getValue("IRCTC").netMinor)
        assertEquals(5_000_000, out.getValue("Salary").receivedMinor)
    }

    @Test
    fun sameSellerMatchesRefundText() {
        kotlin.test.assertTrue(Labels.sameSeller("IRCTC", "IRCTC REFUND"))
        kotlin.test.assertTrue(Labels.sameSeller("Amazon Pay India", "AMAZON SELLER SERVICES"))
        kotlin.test.assertFalse(Labels.sameSeller("Swiggy", "Zomato"))
        kotlin.test.assertFalse(Labels.sameSeller("UPI payment", "UPI refund"))
    }
}
