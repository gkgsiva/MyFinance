package com.myfinance.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FamilySyncTest {

    private fun txn(
        uid: String,
        amount: Long = 50000,
        ts: Long = 1_700_000_000_000,
        account: String? = "1234",
        smsKey: String? = null,
        ref: String? = null,
        merchant: String? = "Swiggy",
        source: String = "SMS",
        owner: String? = "Siva",
    ) = SharedTxn(uid, amount, TxnType.DEBIT, merchant, "Food & Dining", account, "HDFC Bank", ts, null, owner, source, smsKey, ref, false)

    @Test
    fun sameFileImportedTwiceAddsNothing() {
        val mine = listOf(txn("a"), txn("b", ts = 1_700_100_000_000))
        assertEquals(0, Dedupe.newOnly(mine, mine).size)
    }

    @Test
    fun jointAccountSmsOnTwoPhonesIsOneTransaction() {
        val mine = listOf(txn("a", smsKey = "HDFCBK|abc", owner = "Siva"))
        val wife = listOf(txn("z", smsKey = "HDFCBK|abc", ts = 1_700_000_090_000, owner = "Wife"))
        assertEquals(0, Dedupe.newOnly(mine, wife).size)
    }

    @Test
    fun sameAccountAmountWithinMinutesIsDuplicate() {
        val mine = listOf(txn("a", account = "1234"))
        val other = listOf(txn("z", account = "1234", ts = 1_700_000_000_000 + 5 * 60_000))
        assertEquals(0, Dedupe.newOnly(mine, other).size)
    }

    @Test
    fun sameReferenceIsDuplicate() {
        val mine = listOf(txn("a", account = null, ref = "431012345678"))
        val other = listOf(txn("z", account = null, ref = "431012345678", ts = 1_700_000_000_000 + 3 * 3_600_000))
        assertEquals(0, Dedupe.newOnly(mine, other).size)
    }

    @Test
    fun differentAccountsOrAmountsAreKept() {
        val mine = listOf(txn("a", account = "1234"))
        val other = listOf(
            txn("y", account = "9999"),
            txn("z", account = "1234", amount = 60000),
            txn("x", account = "1234", ts = 1_700_000_000_000 + 2 * 3_600_000),
        )
        assertEquals(listOf("y", "z", "x"), Dedupe.newOnly(mine, other).map { it.uid })
    }

    @Test
    fun manualEntriesSameMerchantSameDayAreDuplicates() {
        val mine = listOf(txn("a", account = null, source = "MANUAL", merchant = "Milk man"))
        val other = listOf(txn("z", account = null, source = "MANUAL", merchant = "milk  man", ts = 1_700_000_000_000 + 6 * 3_600_000))
        assertEquals(0, Dedupe.newOnly(mine, other).size)
    }

    @Test
    fun duplicatesInsideTheIncomingFileAreCollapsed() {
        val incoming = listOf(txn("a", smsKey = "k"), txn("b", smsKey = "k"))
        assertEquals(1, Dedupe.newOnly(emptyList(), incoming).size)
    }

    @Test
    fun csvRoundTrip() {
        val rows = listOf(
            txn("a", merchant = "Shop, \"Best\" Ltd", ref = "123456"),
            txn("b", amount = 12345, account = null, merchant = null, source = "MANUAL"),
        ).map { it.copy(note = "line1\nline2") }
        val (decoded, bad) = CsvCodec.decode(CsvCodec.encode(rows))
        assertEquals(0, bad)
        assertEquals(rows, decoded)
    }
}
