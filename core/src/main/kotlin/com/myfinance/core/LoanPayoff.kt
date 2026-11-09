package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/** Where a loan stands today, worked out from its EMI, interest rate and instalments left. */
data class LoanStatus(
    val emiMinor: Long,
    val annualRatePct: Double,
    val instalmentsLeft: Int,
    val instalmentsPaid: Int,
    /** Principal still owed. */
    val outstandingMinor: Long,
    /** The original loan amount, from all instalments (paid and left). */
    val originalMinor: Long,
    val interestPaidMinor: Long,
    val interestLeftMinor: Long,
    val principalPaidMinor: Long,
    val endDate: LocalDate?,
) {
    val totalLeftMinor get() = emiMinor * instalmentsLeft
}

/** What a part-prepayment does: keep the EMI and finish sooner, or keep the end date and pay a smaller EMI. */
data class PrepayResult(
    val prepayMinor: Long,
    /** Keeping the same EMI. */
    val newInstalments: Int,
    val monthsSaved: Int,
    val interestSavedMinor: Long,
    val newEndDate: LocalDate?,
    /** Keeping the same end date instead. */
    val reducedEmiMinor: Long,
    val interestSavedReducedEmiMinor: Long,
)

/** An EMI as shown in "EMI end dates": when it finishes and what it frees up. */
data class EmiEnd(
    val name: String,
    val emiMinor: Long,
    val endDate: LocalDate,
    val instalmentsLeft: Int,
    val totalLeftMinor: Long,
    val planId: String?,
)

object LoanPayoff {

    private fun r(annualRatePct: Double) = annualRatePct / 1200.0

    /** Principal that [n] EMIs of [emi] repay at [annualRatePct]. */
    fun presentValue(emi: Long, annualRatePct: Double, n: Int): Long {
        if (n <= 0) return 0
        val i = r(annualRatePct)
        if (i <= 0.0) return emi * n
        return (emi * (1 - (1 + i).pow(-n)) / i).roundToLong()
    }

    /** EMI for a loan of [principal] over [n] months. */
    fun emiFor(principal: Long, annualRatePct: Double, n: Int): Long {
        if (n <= 0) return principal
        val i = r(annualRatePct)
        if (i <= 0.0) return ceil(principal.toDouble() / n).toLong()
        return (principal * i / (1 - (1 + i).pow(-n))).roundToLong()
    }

    /** Months needed to repay [principal] with [emi]; null when the EMI does not even cover the interest. */
    fun monthsFor(principal: Long, annualRatePct: Double, emi: Long): Int? {
        if (principal <= 0) return 0
        if (emi <= 0) return null
        val i = r(annualRatePct)
        if (i <= 0.0) return ceil(principal.toDouble() / emi).toInt()
        val x = 1 - principal * i / emi
        if (x <= 0) return null
        return ceil(-ln(x) / ln(1 + i) - 1e-9).toInt()
    }

    fun status(emi: Long, annualRatePct: Double, instalmentsLeft: Int, instalmentsPaid: Int, endDate: LocalDate?): LoanStatus {
        val outstanding = presentValue(emi, annualRatePct, instalmentsLeft)
        val original = presentValue(emi, annualRatePct, instalmentsLeft + instalmentsPaid)
        val principalPaid = (original - outstanding).coerceAtLeast(0)
        return LoanStatus(
            emiMinor = emi,
            annualRatePct = annualRatePct,
            instalmentsLeft = instalmentsLeft,
            instalmentsPaid = instalmentsPaid,
            outstandingMinor = outstanding,
            originalMinor = original,
            interestPaidMinor = (emi * instalmentsPaid - principalPaid).coerceAtLeast(0),
            interestLeftMinor = (emi * instalmentsLeft - outstanding).coerceAtLeast(0),
            principalPaidMinor = principalPaid,
            endDate = endDate,
        )
    }

    /** Effect of paying [prepay] extra now. [everyMonths] spaces instalments for the new end date. */
    fun prepay(s: LoanStatus, prepay: Long, everyMonths: Int = 1): PrepayResult {
        val left = (s.outstandingMinor - prepay).coerceAtLeast(0)
        val (n, paidKeepingEmi) = amortise(left, s.annualRatePct, s.emiMinor, s.instalmentsLeft)
        val interestNew = (paidKeepingEmi - left).coerceAtLeast(0)
        val reduced = emiFor(left, s.annualRatePct, s.instalmentsLeft)
        val interestReduced = (reduced * s.instalmentsLeft - left).coerceAtLeast(0)
        val saved = (n.coerceAtMost(s.instalmentsLeft)).let { s.instalmentsLeft - it }
        return PrepayResult(
            prepayMinor = prepay,
            newInstalments = n,
            monthsSaved = saved * everyMonths.coerceAtLeast(1),
            interestSavedMinor = (s.interestLeftMinor - interestNew).coerceAtLeast(0),
            newEndDate = s.endDate?.minusMonths(saved.toLong() * everyMonths.coerceAtLeast(1)),
            reducedEmiMinor = reduced,
            interestSavedReducedEmiMinor = (s.interestLeftMinor - interestReduced).coerceAtLeast(0),
        )
    }

    /** Pays [principal] down with [emi] a month: (instalments needed, total paid). Stops after [maxMonths] * 4. */
    fun amortise(principal: Long, annualRatePct: Double, emi: Long, maxMonths: Int): Pair<Int, Long> {
        val i = r(annualRatePct)
        var bal = principal.toDouble()
        var n = 0
        var paid = 0.0
        val cap = (maxMonths * 4).coerceAtLeast(12)
        while (bal > 0.5 && n < cap) {
            val due = bal * (1 + i)
            val pay = minOf(emi.toDouble(), due)
            if (pay <= bal * i) break // EMI does not cover the interest
            bal = due - pay
            paid += pay
            n++
        }
        return n to paid.roundToLong()
    }

    /** EMIs that have an end date, soonest finishing first. */
    fun endings(items: List<AutoDebitItem>): List<EmiEnd> =
        items.filter { it.endDate != null && it.remaining != null && it.remaining > 0 }
            .map { EmiEnd(it.name, it.amountMinor, it.endDate!!, it.remaining!!, it.amountMinor * it.remaining, it.planId) }
            .sortedBy { it.endDate }

    /** How much a month frees up as each EMI ends: (month after the last instalment, monthly amount freed). */
    fun freedByMonth(ends: List<EmiEnd>): List<Pair<YearMonth, Long>> =
        ends.groupBy { YearMonth.from(it.endDate).plusMonths(1) }
            .map { (m, v) -> m to v.sumOf { it.emiMinor } }
            .sortedBy { it.first }
}
