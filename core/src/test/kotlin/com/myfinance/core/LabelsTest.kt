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
}
