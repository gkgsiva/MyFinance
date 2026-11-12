package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class Cadence(val label: String, val days: Int, private val range: IntRange) {
    WEEKLY("Weekly", 7, 6..8),
    MONTHLY("Monthly", 30, 26..35),
    QUARTERLY("Quarterly", 91, 84..98),
    YEARLY("Yearly", 365, 350..380);

    fun fits(gapDays: Long): Boolean = gapDays in range.first.toLong()..range.last.toLong()

    fun next(from: LocalDate): LocalDate = when (this) {
        WEEKLY -> from.plusWeeks(1)
        MONTHLY -> from.plusMonths(1)
        QUARTERLY -> from.plusMonths(3)
        YEARLY -> from.plusYears(1)
    }
}

/** A payment that repeats on a schedule: a subscription, rent, SIP, insurance premium and so on. */
data class RecurringPayment(
    val merchant: String,
    val category: String,
    /** The latest amount paid. */
    val amountMinor: Long,
    val cadence: Cadence,
    val lastDate: LocalDate,
    val nextDate: LocalDate,
    val occurrences: Int,
    /** Set when one payee has two series (two EMIs to one lender): the amount tells them apart. */
    val amountTag: Long? = null,
) {
    val key: String get() = Categorizer.merchantKey(merchant) + "|" + cadence.name + (amountTag?.let { "|$it" } ?: "")
}

/** Finds repeating payments in the transaction history. */
object RecurringDetector {

    /** Amounts within this share of the typical amount count as "the same payment". */
    private const val AMOUNT_TOLERANCE = 0.2

    fun detect(
        txns: List<TxnLite>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<RecurringPayment> {
        val debits = txns.filter { it.type == TxnType.DEBIT && !it.merchant.isNullOrBlank() }
        val out = ArrayList<RecurringPayment>()
        for ((_, group) in debits.groupBy { Categorizer.merchantKey(it.merchant!!) }) {
            if (group.size < 2) continue
            // The same amount on about the same day each month is recurring, even when the payee also takes other
            // payments (two EMIs to one lender, a subscription among one-off purchases).
            fun live(c: Cadence, s: List<TxnLite>) = ChronoUnit.DAYS.between(Dates.localDate(s.last().timestamp, zone), today) <= c.days * 2 + 7
            val series = sameAmountSeries(group, zone).filter { (c, s) -> live(c, s) }.ifEmpty { listOfNotNull(findSeries(group, zone)) }
            for ((cadence, s) in series) {
                val last = s.last()
                val lastDate = Dates.localDate(last.timestamp, zone)
                // Stopped: missed two cycles in a row.
                if (ChronoUnit.DAYS.between(lastDate, today) > cadence.days * 2 + 7) continue
                var next = cadence.next(lastDate)
                while (next.isBefore(today)) next = cadence.next(next)
                out += RecurringPayment(
                    merchant = last.merchant!!,
                    category = last.category,
                    amountMinor = last.amountMinor,
                    cadence = cadence,
                    lastDate = lastDate,
                    nextDate = next,
                    occurrences = s.size,
                    amountTag = if (series.size > 1) last.amountMinor else null,
                )
            }
        }
        return out.sortedBy { it.nextDate }
    }

    /**
     * Payments of exactly one amount (within 2%) to one payee on about the same day of the month (within 4 days),
     * month after month: at least three, most of them a month apart. Each amount is its own series.
     */
    private fun sameAmountSeries(group: List<TxnLite>, zone: ZoneId): List<Pair<Cadence, List<TxnLite>>> {
        val out = ArrayList<Pair<Cadence, List<TxnLite>>>()
        val sorted = group.sortedBy { it.timestamp }
        val clusters = ArrayList<MutableList<TxnLite>>()
        for (t in sorted.sortedBy { it.amountMinor }) {
            val c = clusters.lastOrNull()
            if (c != null && kotlin.math.abs(t.amountMinor - c[0].amountMinor) * 50 <= c[0].amountMinor) c += t else clusters += mutableListOf(t)
        }
        for (c in clusters) {
            // One a month: two the same month are not a monthly series of this amount.
            val byMonth = c.sortedBy { it.timestamp }.distinctBy { java.time.YearMonth.from(Dates.localDate(it.timestamp, zone)) }
            if (byMonth.size < 3) continue
            val days = byMonth.map { Dates.localDate(it.timestamp, zone).dayOfMonth }
            val typical = days.sorted()[days.size / 2]
            fun near(d: Int) = minOf(kotlin.math.abs(d - typical), 31 - kotlin.math.abs(d - typical)) <= 4
            val onDay = byMonth.filter { near(Dates.localDate(it.timestamp, zone).dayOfMonth) }
            if (onDay.size < 3 || onDay.size * 10 < byMonth.size * 7) continue
            val dates = onDay.map { Dates.localDate(it.timestamp, zone) }
            val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
            if (gaps.count { Cadence.MONTHLY.fits(it) } * 10 >= gaps.size * 7) out += Cadence.MONTHLY to onDay
        }
        return out
    }

    private fun findSeries(group: List<TxnLite>, zone: ZoneId): Pair<Cadence, List<TxnLite>>? {
        // Keep the payments close to the typical (median) amount, one per day.
        val sorted = group.sortedBy { it.timestamp }
        val median = sorted.map { it.amountMinor }.sorted()[sorted.size / 2]
        val similar = sorted
            .filter { kotlin.math.abs(it.amountMinor - median) <= median * AMOUNT_TOLERANCE }
            .distinctBy { Dates.localDate(it.timestamp, zone) }
        if (similar.size < 2) return null

        val dates = similar.map { Dates.localDate(it.timestamp, zone) }
        val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
        for (cadence in Cadence.entries) {
            val fitting = gaps.count { cadence.fits(it) }
            // Weekly/monthly need at least three payments; quarterly/yearly two.
            val minPayments = if (cadence == Cadence.WEEKLY || cadence == Cadence.MONTHLY) 3 else 2
            if (similar.size >= minPayments && fitting * 10 >= gaps.size * 7) return cadence to similar
        }
        return null
    }
}
