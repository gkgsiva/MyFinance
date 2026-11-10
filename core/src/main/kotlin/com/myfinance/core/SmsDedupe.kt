package com.myfinance.core

/**
 * One payment often sends two SMS: the bank's alert and the wallet / UPI app's message ("Paid Rs 250 to
 * Swiggy" from PhonePe or Paytm). Decides whether a new SMS transaction is the same payment as one already
 * stored, so it is not counted twice.
 *
 * Rules adapted from kharcha's capture dedupe (Apache-2.0, github.com/AkashPriyadarshii/kharcha):
 * 1. The same UPI / bank reference, amount and direction within 3 days is the same payment.
 * 2. A wallet / UPI app message and a bank message for the same amount and direction within 5 minutes are
 *    the same payment, unless both carry different references (two real back-to-back payments).
 */
object SmsDedupe {

    data class Seen(
        val amountMinor: Long,
        val type: TxnType,
        val timestamp: Long,
        val reference: String?,
        /** The bank or wallet the SMS came from, as [SmsParser] names it. */
        val bank: String?,
    )

    /** Senders that repeat a payment a bank also reports. */
    val wallets = setOf("Paytm", "PhonePe", "Amazon Pay", "MobiKwik", "Freecharge")

    private const val REF_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
    private const val WALLET_WINDOW_MS = 5L * 60 * 1000

    private fun ref(r: String?) = r?.trim()?.takeIf { it.length >= 8 }?.lowercase()

    /** The stored entry [new] repeats, or null when it is a payment of its own. */
    fun duplicateOf(new: Seen, existing: List<Seen>): Seen? {
        val nr = ref(new.reference)
        val same = existing.filter { it.amountMinor == new.amountMinor && it.type == new.type }
        if (nr != null) {
            same.firstOrNull { ref(it.reference) == nr && kotlin.math.abs(it.timestamp - new.timestamp) <= REF_WINDOW_MS }?.let { return it }
        }
        val newWallet = new.bank in wallets
        return same.firstOrNull { e ->
            val oneWallet = newWallet != (e.bank in wallets)
            val distinctRefs = nr != null && ref(e.reference) != null && ref(e.reference) != nr
            oneWallet && !distinctRefs && kotlin.math.abs(e.timestamp - new.timestamp) <= WALLET_WINDOW_MS
        }
    }
}
