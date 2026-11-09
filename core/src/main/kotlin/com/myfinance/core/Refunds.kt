package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A payment the user is waiting to get money back for, e.g. a cancelled IRCTC ticket or an Amazon return. */
data class RefundWatch(
    val txnId: Long,
    /** What the user expects back; null = the whole payment. */
    val expectedMinor: Long?,
    /** When the user started waiting (the cancellation), epoch millis. */
    val since: Long,
    /** Closed by hand: received in cash, or given up on. */
    val closed: Boolean = false,
)

enum class RefundState(val label: String) { WAITING("Waiting"), OVERDUE("Overdue"), PARTLY("Part received"), RECEIVED("Received"), CLOSED("Closed") }

data class RefundItem(
    val watch: RefundWatch,
    val payment: TxnLite,
    val expectedMinor: Long,
    val receivedMinor: Long,
    val credits: List<TxnLite>,
    val state: RefundState,
    val daysWaiting: Long,
) {
    val pendingMinor get() = (expectedMinor - receivedMinor).coerceAtLeast(0)
}

/** Refund tracker: payments marked "waiting for refund", matched to the money that came back from the same seller. */
object Refunds {

    /** Sellers that often refund (shown as suggestions when a recent payment had no refund yet). */
    private val refundSellers = Regex("""irctc|amazon|flipkart|myntra|ajio|meesho|nykaa|swiggy|zomato|makemytrip|goibibo|cleartrip|yatra|ixigo|redbus|indigo|air\s*india|vistara|akasa|spicejet|uber|ola|bookmyshow|bigbasket|blinkit|zepto|tata\s*cliq""", RegexOption.IGNORE_CASE)

    private val refundWords = Regex("""refund|reversal|reversed|cancel|return|chargeback|cashback""", RegexOption.IGNORE_CASE)

    fun isRefundSeller(merchant: String?): Boolean = merchant != null && refundSellers.containsMatchIn(merchant)

    /** True when [credit] can be the refund of [payment]: same seller (or a refund mentioning it), afterwards, not more than paid. */
    fun canRefund(payment: TxnLite, credit: TxnLite): Boolean {
        if (credit.type != TxnType.CREDIT || payment.type != TxnType.DEBIT) return false
        if (credit.timestamp < payment.timestamp) return false
        if (credit.amountMinor > payment.amountMinor + payment.amountMinor / 100) return false
        val pm = payment.merchant ?: return false
        val cm = credit.merchant ?: return false
        if (Labels.sameSeller(pm, cm)) return true
        // "REFUND" credits often carry just the account: accept them on the same card or account.
        return refundWords.containsMatchIn(cm) && payment.account != null && payment.account == credit.account &&
            credit.amountMinor == payment.amountMinor
    }

    /**
     * Matches watched payments to credits (oldest watch first, each credit used once) and flags the ones still
     * open after [overdueDays].
     */
    fun track(
        watches: List<RefundWatch>,
        txns: List<TxnLite>,
        today: LocalDate,
        overdueDays: Int = 10,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<RefundItem> {
        val byId = txns.associateBy { it.id }
        val credits = txns.filter { it.type == TxnType.CREDIT }.sortedBy { it.timestamp }
        val used = HashSet<Long>()
        val out = ArrayList<RefundItem>()
        for (w in watches.sortedBy { it.since }) {
            val p = byId[w.txnId] ?: continue
            val expected = (w.expectedMinor ?: p.amountMinor).coerceAtMost(p.amountMinor).coerceAtLeast(1)
            var got = 0L
            val matched = ArrayList<TxnLite>()
            for (c in credits) {
                if (got >= expected) break
                if (c.id in used || !canRefund(p, c)) continue
                if (c.amountMinor > expected - got + expected / 100) continue
                used += c.id
                matched += c
                got += c.amountMinor
            }
            val start = Dates.localDate(maxOf(w.since, p.timestamp), zone)
            val days = ChronoUnit.DAYS.between(start, matched.lastOrNull()?.let { Dates.localDate(it.timestamp, zone) } ?: today)
            val state = when {
                got * 100 >= expected * 98 -> RefundState.RECEIVED
                w.closed -> RefundState.CLOSED
                got > 0 && days > overdueDays -> RefundState.OVERDUE
                got > 0 -> RefundState.PARTLY
                days > overdueDays -> RefundState.OVERDUE
                else -> RefundState.WAITING
            }
            out += RefundItem(w, p, expected, got, matched, state, days.coerceAtLeast(0))
        }
        val order = listOf(RefundState.OVERDUE, RefundState.PARTLY, RefundState.WAITING, RefundState.RECEIVED, RefundState.CLOSED)
        return out.sortedWith(compareBy<RefundItem> { order.indexOf(it.state) }.thenByDescending { it.payment.timestamp })
    }

    /**
     * Recent payments to sellers that often refund, not yet watched and with nothing back yet, newest first, so
     * the user can tap "Waiting for refund" on a cancelled ticket or a return.
     */
    fun candidates(txns: List<TxnLite>, watched: Set<Long>, today: LocalDate, days: Int = 45, zone: ZoneId = ZoneId.systemDefault()): List<TxnLite> {
        val from = today.minusDays(days.toLong())
        val credits = txns.filter { it.type == TxnType.CREDIT }
        return txns.filter { t ->
            t.type == TxnType.DEBIT && t.id !in watched && isRefundSeller(t.merchant) &&
                !Dates.localDate(t.timestamp, zone).isBefore(from) && credits.none { canRefund(t, it) }
        }.sortedByDescending { it.timestamp }.take(30)
    }
}
