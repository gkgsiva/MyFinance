package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class AutoDebitKind(val label: String) {
    EMI("EMI / loan"),
    AUTO_DEBIT("Auto debit"),
    SIP("SIP / investment"),
    INSURANCE("Insurance"),
    SUBSCRIPTION("Subscription"),
    OTHER("Recurring payment"),
}

/** Where a row in the auto debit list came from. */
enum class AutoDebitSource { MANUAL, DETECTED, BANK_ALERT }

/**
 * An auto debit or EMI the user entered (or confirmed from a detected one): it is due every
 * [everyMonths] months starting on [firstDue], optionally until [endDate].
 */
data class AutoDebitPlan(
    val id: String,
    val name: String,
    val kind: AutoDebitKind,
    val amountMinor: Long,
    val firstDue: LocalDate,
    val everyMonths: Int = 1,
    val endDate: LocalDate? = null,
    /** Words that identify the debit in the SMS or merchant, e.g. "bajaj". Defaults to the name. */
    val matchWords: List<String> = emptyList(),
    val account: String? = null,
)

/** One debit seen in the transaction history, with the SMS text it came from. */
data class DebitRecord(val txn: TxnLite, val sms: String?)

enum class DueState { PAID, OVERDUE, DUE_TODAY, DUE_SOON, UPCOMING }

/** A row of the "Auto debits & EMIs" list. */
data class AutoDebitItem(
    /** Stable key: "plan:<id>", "rec:<merchant key>|<cadence>" or "bill:<…>". */
    val key: String,
    val name: String,
    val kind: AutoDebitKind,
    val amountMinor: Long,
    val cadenceLabel: String,
    val nextDate: LocalDate,
    val daysLeft: Long,
    val state: DueState,
    val source: AutoDebitSource,
    val lastPaid: LocalDate? = null,
    /** Instalments left including [nextDate]; null when there is no end date. */
    val remaining: Int? = null,
    val endDate: LocalDate? = null,
    /** How many past payments were found. */
    val occurrences: Int = 0,
    /** True when the next date is a guess from a single payment. */
    val estimated: Boolean = false,
    val account: String? = null,
    val planId: String? = null,
    val everyMonths: Int = 1,
)

data class AutoDebitTotals(val next30DaysMinor: Long, val next30DaysCount: Int, val monthlyMinor: Long)

object AutoDebits {

    private val mandate = Regex(
        """\bnach\b|\bach\b|\becs\b|e-?mandate|\bmandate\b|auto[\s-]?debit|auto[\s-]?pay|autopay|standing\s+instruction|\bsi\b|\bumrn\b|e-?nach|recurring\s+payment""",
        RegexOption.IGNORE_CASE,
    )
    private val emi = Regex("""\bemi\b|\bloan\b|instal+ment|bajaj\s+fin|home\s+credit|\bnbfc\b""", RegexOption.IGNORE_CASE)
    private val sip = Regex("""\bsip\b|mutual\s+fund|\bmf\b|systematic|\bnps\b|\bppf\b|\brd\b|recurring\s+deposit|zerodha|groww|coin\b|kuvera""", RegexOption.IGNORE_CASE)
    private val insurance = Regex("""insurance|\blic\b|premium|policy|hdfc\s+life|icici\s+pru|max\s+life|star\s+health|care\s+health""", RegexOption.IGNORE_CASE)
    private val subscription = Regex("""netflix|hotstar|spotify|prime|youtube|apple|google\s+(?:play|one)|zee5|sonyliv|jiocinema|subscription|airtel|jio|broadband|fibernet""", RegexOption.IGNORE_CASE)

    /** True when an SMS describes a mandate / NACH / standing instruction debit. */
    fun isMandate(text: String?): Boolean = text != null && mandate.containsMatchIn(text)

    /** What kind of auto debit this looks like, from its merchant, SMS text and category. */
    fun classify(merchant: String?, sms: String?, category: String?): AutoDebitKind {
        val hay = listOfNotNull(merchant, sms).joinToString(" ")
        return when {
            emi.containsMatchIn(hay) || category == DefaultCategories.EMI -> AutoDebitKind.EMI
            sip.containsMatchIn(hay) || category == DefaultCategories.INVESTMENTS -> AutoDebitKind.SIP
            insurance.containsMatchIn(hay) -> AutoDebitKind.INSURANCE
            subscription.containsMatchIn(hay) -> AutoDebitKind.SUBSCRIPTION
            isMandate(sms) -> AutoDebitKind.AUTO_DEBIT
            else -> AutoDebitKind.OTHER
        }
    }

    fun cadenceLabel(everyMonths: Int): String = when (everyMonths) {
        1 -> "Monthly"
        3 -> "Quarterly"
        6 -> "Half-yearly"
        12 -> "Yearly"
        else -> "Every $everyMonths months"
    }

    private fun state(days: Long): DueState = when {
        days < 0 -> DueState.OVERDUE
        days == 0L -> DueState.DUE_TODAY
        days <= 3 -> DueState.DUE_SOON
        else -> DueState.UPCOMING
    }

    private fun words(plan: AutoDebitPlan): List<String> =
        plan.matchWords.map { it.trim().lowercase() }.filter { it.length >= 3 }.ifEmpty {
            plan.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in genericWords }
        }

    private val genericWords = setOf("loan", "home", "auto", "debit", "insurance", "premium", "monthly", "payment", "bank", "card", "policy")

    /** True when [d] looks like the payment of [plan] due on [due]: matching words, ±7 days, amount within 15%. */
    fun matches(plan: AutoDebitPlan, d: DebitRecord, due: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (d.txn.type != TxnType.DEBIT) return false
        val paidOn = Dates.localDate(d.txn.timestamp, zone)
        if (kotlin.math.abs(ChronoUnit.DAYS.between(due, paidOn)) > 7) return false
        if (plan.amountMinor > 0 && kotlin.math.abs(d.txn.amountMinor - plan.amountMinor) * 100 > plan.amountMinor * 15) return false
        val w = words(plan)
        if (w.isEmpty()) return true
        val hay = listOfNotNull(d.txn.merchant, d.sms).joinToString(" ").lowercase()
        return w.any { hay.contains(it) }
    }

    /** The due date of cycle [n] (0 = first), keeping the original day of month where possible. */
    fun dueAt(plan: AutoDebitPlan, n: Int): LocalDate = plan.firstDue.plusMonths(n.toLong() * plan.everyMonths.coerceAtLeast(1))

    /**
     * Status of a plan today: the first cycle not yet paid (a payment within a week of the date counts),
     * or null once the plan has ended.
     */
    fun planItem(plan: AutoDebitPlan, debits: List<DebitRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): AutoDebitItem? {
        var n = 0
        var lastPaid: LocalDate? = null
        var paidCount = 0
        // Walk forward through cycles that are in the past or have already been paid.
        while (n < 1200) {
            val due = dueAt(plan, n)
            if (plan.endDate != null && due.isAfter(plan.endDate)) return null
            val payment = debits.firstOrNull { matches(plan, it, due, zone) }
            if (payment != null) {
                lastPaid = Dates.localDate(payment.txn.timestamp, zone)
                paidCount++
                n++
                continue
            }
            // Unpaid: if it is long gone (more than a week ago and a later cycle has started), skip it.
            val nextDue = dueAt(plan, n + 1)
            if (due.isBefore(today.minusDays(7)) && !nextDue.isAfter(today.plusDays(7))) { n++; continue }
            break
        }
        val due = dueAt(plan, n)
        val days = ChronoUnit.DAYS.between(today, due)
        val remaining = plan.endDate?.let { end ->
            var k = 0
            while (!dueAt(plan, n + k).isAfter(end) && k < 1200) k++
            k
        }
        return AutoDebitItem(
            key = "plan:${plan.id}",
            name = plan.name,
            kind = plan.kind,
            amountMinor = plan.amountMinor,
            cadenceLabel = cadenceLabel(plan.everyMonths),
            nextDate = due,
            daysLeft = days,
            state = state(days),
            source = AutoDebitSource.MANUAL,
            lastPaid = lastPaid,
            remaining = remaining,
            endDate = plan.endDate,
            occurrences = paidCount,
            account = plan.account,
            planId = plan.id,
            everyMonths = plan.everyMonths,
        )
    }

    /**
     * Mandate debits (NACH, ECS, auto-pay, standing instructions) that [RecurringDetector] does not catch yet,
     * e.g. a new EMI paid only once or twice. Assumed monthly; a single payment gives an estimated date.
     */
    fun mandateSeries(debits: List<DebitRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AutoDebitItem> {
        val out = ArrayList<AutoDebitItem>()
        val mandates = debits.filter { it.txn.type == TxnType.DEBIT && isMandate(it.sms) }
        val groups = mandates.groupBy { d ->
            d.txn.merchant?.let { Categorizer.merchantKey(it) }?.takeIf { it.isNotBlank() }
                ?: "acct ${d.txn.account} ${d.txn.amountMinor / 100}"
        }
        for ((key, group) in groups) {
            val sorted = group.sortedBy { it.txn.timestamp }
            val last = sorted.last()
            val lastDate = Dates.localDate(last.txn.timestamp, zone)
            // Gone quiet for over two months: probably closed.
            if (ChronoUnit.DAYS.between(lastDate, today) > 70) continue
            var next = lastDate.plusMonths(1)
            while (next.isBefore(today.minusDays(3))) next = next.plusMonths(1)
            val days = ChronoUnit.DAYS.between(today, next)
            val kind = classify(last.txn.merchant, last.sms, last.txn.category).let { if (it == AutoDebitKind.OTHER) AutoDebitKind.AUTO_DEBIT else it }
            out += AutoDebitItem(
                key = "nach:$key",
                name = last.txn.merchant ?: (kind.label + (last.txn.account?.let { " · $it" } ?: "")),
                kind = kind,
                amountMinor = last.txn.amountMinor,
                cadenceLabel = "Monthly",
                nextDate = next,
                daysLeft = days,
                state = state(days),
                source = AutoDebitSource.DETECTED,
                lastPaid = lastDate,
                occurrences = sorted.distinctBy { Dates.localDate(it.txn.timestamp, zone) }.size,
                estimated = sorted.size < 2,
                account = last.txn.account,
            )
        }
        return out
    }

    /**
     * Builds the full list: the user's own plans, EMI alerts from the bank, recurring payments and mandate
     * debits found in the SMS history. Detected rows that duplicate a plan or a bank alert are dropped, and
     * so are [hidden] keys.
     */
    fun build(
        plans: List<AutoDebitPlan>,
        debits: List<DebitRecord>,
        recurring: List<RecurringPayment>,
        emiBills: List<BillDue>,
        hidden: Set<String>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AutoDebitItem> {
        val planItems = plans.mapNotNull { planItem(it, debits, today, zone) }

        val billItems = emiBills.filter { it.kind == BillKind.EMI }.map { b ->
            val days = ChronoUnit.DAYS.between(today, b.dueDate)
            AutoDebitItem(
                key = "bill:${b.bank}|${b.accountTail}|${b.dueDate}",
                name = b.title,
                kind = AutoDebitKind.EMI,
                amountMinor = b.amountMinor,
                cadenceLabel = "From bank SMS",
                nextDate = b.dueDate,
                daysLeft = days,
                state = state(days),
                source = AutoDebitSource.BANK_ALERT,
                account = b.accountTail?.let { "••$it" },
            )
        }

        val recurringItems = recurring.map { r ->
            val days = ChronoUnit.DAYS.between(today, r.nextDate)
            val sms = debits.lastOrNull { d -> d.txn.merchant?.let { Categorizer.merchantKey(it) } == Categorizer.merchantKey(r.merchant) }?.sms
            AutoDebitItem(
                key = "rec:${r.key}",
                name = r.merchant,
                kind = classify(r.merchant, sms, r.category),
                amountMinor = r.amountMinor,
                cadenceLabel = r.cadence.label,
                nextDate = r.nextDate,
                daysLeft = days,
                state = state(days),
                source = AutoDebitSource.DETECTED,
                lastPaid = r.lastDate,
                occurrences = r.occurrences,
                everyMonths = when (r.cadence) { Cadence.QUARTERLY -> 3; Cadence.YEARLY -> 12; else -> 1 },
            )
        }
        val recurringNames = recurring.map { Categorizer.merchantKey(it.merchant) }.toSet()
        val mandateItems = mandateSeries(debits, today, zone).filter { it.key.removePrefix("nach:") !in recurringNames }

        val fixed = planItems + billItems
        fun duplicatesFixed(d: AutoDebitItem): Boolean {
            val nameKey = Categorizer.merchantKey(d.name)
            return fixed.any { f ->
                val sameAmount = f.amountMinor > 0 && kotlin.math.abs(f.amountMinor - d.amountMinor) * 100 <= f.amountMinor * 15
                val near = kotlin.math.abs(ChronoUnit.DAYS.between(f.nextDate, d.nextDate)) <= 10
                val plan = plans.firstOrNull { "plan:${it.id}" == f.key }
                val named = plan != null && words(plan).any { nameKey.contains(it) }
                named || (sameAmount && near)
            }
        }
        val detected = (recurringItems + mandateItems).filterNot { duplicatesFixed(it) }

        return (fixed + detected).filter { it.key !in hidden }.sortedBy { it.nextDate }
    }

    fun totals(items: List<AutoDebitItem>, today: LocalDate): AutoDebitTotals {
        val soon = items.filter { !it.nextDate.isAfter(today.plusDays(30)) }
        val monthly = items.sumOf { it.amountMinor / it.everyMonths.coerceAtLeast(1) }
        return AutoDebitTotals(soon.sumOf { it.amountMinor }, soon.size, monthly)
    }
}
