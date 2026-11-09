package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class CardBillStatus(val label: String) {
    PAID("Paid in full"),
    PAID_LATE("Paid late"),
    PARTLY_PAID("Part paid"),
    MIN_ONLY("Only minimum paid"),
    UNPAID("Not paid"),
    MARKED_PAID("Marked paid"),
    NOT_DUE("Not due yet"),
}

/** One credit card statement compared with the payments made to that card. */
data class CardBillCheck(
    val bill: BillDue,
    /** When the statement arrived (approximately the statement date). */
    val statementDate: LocalDate,
    val paidByDueMinor: Long,
    val paidLateMinor: Long,
    val firstPayment: LocalDate?,
    val lastPayment: LocalDate?,
    val status: CardBillStatus,
    /** Total due minus everything paid for this bill; 0 when fully paid. */
    val gapMinor: Long,
    /** Days after the due date the bill was cleared (or still open); 0 when on time. */
    val daysLate: Long,
) {
    val paidMinor get() = paidByDueMinor + paidLateMinor
    val needsAttention get() = status in setOf(CardBillStatus.PAID_LATE, CardBillStatus.PARTLY_PAID, CardBillStatus.MIN_ONLY, CardBillStatus.UNPAID)
}

/** Per card: how many bills were checked and how many had a problem. */
data class CardSummary(val card: String, val bills: Int, val late: Int, val short: Int, val totalGapMinor: Long)

/**
 * Card bill check: each credit card statement (from the statement SMS or an imported card statement) against
 * the payments credited to that card, to flag bills that were paid late, only partly or not at all.
 */
object CardBills {

    private val cardPaymentWords = Regex(
        """credit\s*card|card\s*payment|cc\s*payment|\bcc\b|billdesk.*card|cred\b|card\s*bill""",
        RegexOption.IGNORE_CASE,
    )

    /** True when [t] paid this card: money in on the card, or a payment out that names it ("CC payment ••1234"). */
    fun isPaymentTo(bill: BillDue, t: TxnLite): Boolean {
        val tail = bill.accountTail ?: return false
        val onCard = t.account?.endsWith(tail) == true
        if (onCard) return t.type == TxnType.CREDIT
        // Paid from a bank account: the debit mentions the card number and looks like a card payment.
        val m = t.merchant ?: return false
        return t.type == TxnType.DEBIT && m.contains(tail) && cardPaymentWords.containsMatchIn(m)
    }

    /**
     * Checks each card bill in [bills] (paired with when it arrived and whether the user marked it paid). Payments
     * from the statement date to the due date count as on time; later payments until the next statement count as late.
     */
    fun check(
        bills: List<Triple<BillDue, LocalDate, Boolean>>,
        txns: List<TxnLite>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CardBillCheck> {
        val cards = bills.filter { it.first.kind == BillKind.CREDIT_CARD && it.first.accountTail != null }
            // One per card and due date: the statement SMS and the imported statement describe the same bill.
            .groupBy { "${it.first.accountTail}|${it.first.dueDate}" }.values.map { g -> g.maxByOrNull { it.first.amountMinor }!! }
        val byCard = cards.groupBy { it.first.accountTail!! }
        val out = ArrayList<CardBillCheck>()
        val used = HashSet<Long>()
        for ((_, list) in byCard) {
            val sorted = list.sortedBy { it.first.dueDate }
            for ((i, triple) in sorted.withIndex()) {
                val (bill, arrived, marked) = triple
                val statementDate = minOf(arrived, bill.dueDate.minusDays(10))
                val nextStatement = sorted.getOrNull(i + 1)?.let { minOf(it.second, it.first.dueDate.minusDays(10)) }
                val lateUntil = nextStatement ?: bill.dueDate.plusDays(30)
                var onTime = 0L
                var late = 0L
                var first: LocalDate? = null
                var last: LocalDate? = null
                val payments = txns.filter { isPaymentTo(bill, it) && it.id !in used }
                    .map { it to Dates.localDate(it.timestamp, zone) }
                    .filter { (_, d) -> !d.isBefore(statementDate.minusDays(2)) && d.isBefore(lateUntil) }
                    .sortedBy { it.first.timestamp }
                for ((t, d) in payments) {
                    if (onTime + late >= bill.amountMinor) break
                    if (t.id != 0L) used += t.id
                    if (!d.isAfter(bill.dueDate)) onTime += t.amountMinor else late += t.amountMinor
                    if (first == null) first = d
                    last = d
                }
                val paid = onTime + late
                // A rupee or two short is rounding, not a gap.
                val tolerance = maxOf(200L, bill.amountMinor / 200)
                val full = paid + tolerance >= bill.amountMinor
                val pastDue = today.isAfter(bill.dueDate)
                val status = when {
                    full && late == 0L -> CardBillStatus.PAID
                    full -> CardBillStatus.PAID_LATE
                    marked -> CardBillStatus.MARKED_PAID
                    !pastDue && paid == 0L -> CardBillStatus.NOT_DUE
                    !pastDue -> CardBillStatus.PARTLY_PAID
                    paid == 0L -> CardBillStatus.UNPAID
                    bill.minDueMinor != null && paid + tolerance >= bill.minDueMinor && paid < bill.minDueMinor * 2 -> CardBillStatus.MIN_ONLY
                    else -> CardBillStatus.PARTLY_PAID
                }
                val gap = if (full || marked) 0L else (bill.amountMinor - paid).coerceAtLeast(0)
                val daysLate = when {
                    status == CardBillStatus.PAID_LATE -> ChronoUnit.DAYS.between(bill.dueDate, last ?: bill.dueDate)
                    gap > 0 && pastDue -> ChronoUnit.DAYS.between(bill.dueDate, today)
                    else -> 0L
                }
                out += CardBillCheck(bill, statementDate, onTime, late, first, last, status, gap, daysLate.coerceAtLeast(0))
            }
        }
        return out.sortedByDescending { it.bill.dueDate }
    }

    fun summaries(checks: List<CardBillCheck>): List<CardSummary> =
        checks.groupBy { it.bill.title }.map { (card, list) ->
            CardSummary(
                card = card,
                bills = list.size,
                late = list.count { it.status == CardBillStatus.PAID_LATE || (it.status == CardBillStatus.UNPAID && it.daysLate > 0) },
                short = list.count { it.gapMinor > 0 && it.status != CardBillStatus.NOT_DUE },
                totalGapMinor = list.filter { it.status != CardBillStatus.NOT_DUE }.sumOf { it.gapMinor },
            )
        }.sortedBy { it.card }

    // ---- Reading the bill summary printed on a card statement PDF ----

    private val money = Regex("""(?:rs\.?|inr|₹)?\s*([0-9][0-9,]*\.[0-9]{2})""", RegexOption.IGNORE_CASE)
    private val totalLabel = Regex("""total\s+(?:amount|amt\.?)\s+(?:due|payable)|total\s+dues?\b|total\s+payment\s+due|closing\s+balance""", RegexOption.IGNORE_CASE)
    private val minLabel = Regex("""min(?:imum)?\.?\s+(?:amount|amt\.?)\s+(?:due|payable)|minimum\s+due|min\.?\s+due""", RegexOption.IGNORE_CASE)
    private val dueLabel = Regex("""payment\s+due\s+date|due\s+date|pay\s+by|due\s+by""", RegexOption.IGNORE_CASE)

    /**
     * The total due, minimum due and due date printed at the top of a credit card statement. Handles both
     * "Total Amount Due: 12,345.00" and a header row followed by a row of values. Null when not found.
     */
    fun summaryFromText(text: String, reference: LocalDate): Triple<Long, Long?, LocalDate>? {
        val flat = text.replace(' ', ' ').take(8000)
        val totalAt = totalLabel.find(flat) ?: return null
        val total = money.find(flat, totalAt.range.last + 1)
            ?.takeIf { it.range.first - totalAt.range.last < 250 }
            ?.let { parseAmountToMinor(it.groupValues[1]) }?.takeIf { it > 0 } ?: return null
        val dueAt = dueLabel.find(flat) ?: return null
        val due = Dates.findDate(flat.substring(dueAt.range.last + 1).take(250), reference) ?: return null
        val min = minLabel.find(flat)?.let { m ->
            // In a header-then-values layout the first amount after "Minimum" is often the total again; take the next smaller one.
            money.findAll(flat, m.range.last + 1).take(4)
                .mapNotNull { parseAmountToMinor(it.groupValues[1]) }
                .firstOrNull { it in 1 until total }
        }
        return Triple(total, min, due)
    }
}
