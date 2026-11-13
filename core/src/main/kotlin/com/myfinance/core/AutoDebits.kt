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

/** One debit seen in the transaction history, with the SMS text it came from and the user's labels. */
data class DebitRecord(val txn: TxnLite, val sms: String?, val labels: List<String> = emptyList())

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
    /** EMIs whose statement / SMS prints the instalment ("<4/6>", "EMI 3 of 12"): instalments paid and in all. */
    val instalmentsPaid: Int? = null,
    val instalmentsTotal: Int? = null,
    /** What is still to pay: [remaining] times the instalment, when known. */
    val remainingMinor: Long? = null,
    /** True when [endDate] was read from the statements rather than set by the user. */
    val endInferred: Boolean = false,
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

    private val familyUpi = Regex(
        """upi\s*circle|\bdelegat|secondary\s+user|primary\s+user|family""",
        RegexOption.IGNORE_CASE,
    )

    /** Keys in the hidden list that mean "this payee is not a recurring payment": the prefix plus [payeeKey]. */
    const val NOT_RECURRING_PREFIX = "notrec:"

    /**
     * A payment a family member made through UPI Circle / family UPI on the user's account. Banks word these as
     * an "autopay mandate", but they are everyday spends, not an auto debit. Also true when the user gave the
     * payment a label with "family" in it.
     */
    fun isFamilyUpi(d: DebitRecord): Boolean =
        (d.sms != null && familyUpi.containsMatchIn(d.sms)) || d.labels.any { familyUpi.containsMatchIn(it) }

    /** The payee a detected row stands for, so "Not recurring" can be remembered for it; null for the user's own and bank rows. */
    fun payeeKey(item: AutoDebitItem): String? = when {
        item.key.startsWith("rec:") -> item.key.removePrefix("rec:").substringBefore('|')
        item.key.startsWith("nach:") -> item.key.removePrefix("nach:")
        else -> null
    }

    private fun debitPayee(d: DebitRecord): String =
        d.txn.merchant?.let { Categorizer.merchantKey(it) }?.takeIf { it.isNotBlank() }
            ?: "acct ${d.txn.account} ${d.txn.amountMinor / 100}"

    /** "Principal Amount Amortization - <4/6>", "EMI 3 of 12", "Instalment 3/12": the instalment and the count. */
    private val instalmentRe = Regex(
        """<\s*(\d{1,3})\s*/\s*(\d{1,3})\s*>|\b(?:EMI|INSTAL+MENT|INST)\b\D{0,12}?(\d{1,3})\s*(?:OF|/)\s*(\d{1,3})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val emiInterest = Regex("""interest\s+amount\s+amortization|\bemi\s+interest\b""", RegexOption.IGNORE_CASE)

    /** The instalment number and count printed on an EMI line, when there is one. */
    fun instalmentOf(text: String?): Pair<Int, Int>? {
        val m = text?.let { instalmentRe.find(it) } ?: return null
        val k = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return null
        val n = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return null
        return (k to n).takeIf { n in 2..360 && k in 1..n }
    }

    /**
     * EMIs that print which instalment each payment is (card EMIs: "Principal Amount Amortization - <4/6>" with
     * its interest line). Each EMI is told apart by its card, its length and the month it started; the latest
     * instalment gives what is paid, what is left and the last month. Finished EMIs are left out.
     */
    fun instalmentEmis(debits: List<DebitRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AutoDebitItem> {
        data class Inst(val d: DebitRecord, val k: Int, val n: Int, val date: LocalDate, val start: java.time.YearMonth, val interest: Boolean, val name: String?)
        val seen = debits.filter { it.txn.type == TxnType.DEBIT }.mapNotNull { d ->
            val text = d.sms?.removePrefix("Statement: ")?.substringBefore("\n\nSMS: ") ?: d.txn.merchant
            val (k, n) = instalmentOf(text) ?: instalmentOf(d.txn.merchant) ?: return@mapNotNull null
            val date = Dates.localDate(d.txn.timestamp, zone)
            // "<2/6>GADGET SHOP": the purchase the EMI is for.
            val name = text?.let { t -> instalmentRe.find(t)?.let { t.substring(it.range.last + 1) } }?.trim()?.takeIf { it.count(Char::isLetter) >= 3 }
            Inst(d, k, n, date, java.time.YearMonth.from(date).minusMonths((k - 1).toLong()), emiInterest.containsMatchIn(text.orEmpty()), name)
        }
        val out = ArrayList<AutoDebitItem>()
        // The same EMI's start month can read a month apart (billing dates move): allow one month either way.
        val groups = ArrayList<MutableList<Inst>>()
        for (i in seen.sortedBy { it.date }) {
            val g = groups.firstOrNull { g -> g[0].n == i.n && g[0].d.txn.account == i.d.txn.account && kotlin.math.abs(ChronoUnit.MONTHS.between(g[0].start, i.start)) <= 1 &&
                g.none { it.k == i.k && it.interest == i.interest } }
            if (g != null) g += i else groups += mutableListOf(i)
        }
        for (g in groups) {
            val latestK = g.maxOf { it.k }
            val n = g[0].n
            val latest = g.filter { it.k == latestK }
            // One instalment: principal plus interest (when printed on the same month).
            val instalment = latest.sumOf { it.d.txn.amountMinor }
            val lastDate = latest.maxOf { it.date }
            val left = n - latestK
            if (left <= 0) continue
            // Not seen for two months: probably closed early or the statements stop.
            if (ChronoUnit.DAYS.between(lastDate, today) > 70) continue
            var next = lastDate.plusMonths(1)
            var leftNow = left
            while (next.isBefore(today.minusDays(3)) && leftNow > 1) { next = next.plusMonths(1); leftNow-- }
            val end = lastDate.plusMonths(left.toLong())
            val days = ChronoUnit.DAYS.between(today, next)
            val acct = g[0].d.txn.account
            val title = g.firstNotNullOfOrNull { it.name }?.let { Narration.merchant(it) }?.let { "$it EMI" } ?: ("Card EMI" + (acct?.let { " · $it" } ?: ""))
            out += AutoDebitItem(
                key = "emi:${acct.orEmpty()}|${g[0].start}|$n",
                name = title,
                kind = AutoDebitKind.EMI,
                amountMinor = instalment,
                cadenceLabel = "Monthly",
                nextDate = next,
                daysLeft = days,
                state = state(days),
                source = AutoDebitSource.DETECTED,
                lastPaid = lastDate,
                remaining = leftNow,
                endDate = end,
                occurrences = latestK,
                account = acct,
                instalmentsPaid = n - leftNow,
                instalmentsTotal = n,
                remainingMinor = instalment * leftNow,
            )
        }
        return out
    }

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
     * e.g. a new EMI paid only twice. An SMS saying "auto debit" once does not make a payment recurring: it
     * has to have been paid at least twice, a similar amount, a month, quarter or year apart. Family UPI
     * payments are left out.
     */
    fun mandateSeries(debits: List<DebitRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AutoDebitItem> {
        val out = ArrayList<AutoDebitItem>()
        val mandates = debits.filter { it.txn.type == TxnType.DEBIT && isMandate(it.sms) && !isFamilyUpi(it) }
        for ((key, group) in mandates.groupBy { debitPayee(it) }) {
            val sorted = group.sortedBy { it.txn.timestamp }
            val last = sorted.last()
            val lastDate = Dates.localDate(last.txn.timestamp, zone)
            // Payments close to the latest amount, one per day.
            val similar = sorted
                .filter { kotlin.math.abs(it.txn.amountMinor - last.txn.amountMinor) * 5 <= last.txn.amountMinor }
                .distinctBy { Dates.localDate(it.txn.timestamp, zone) }
            if (similar.size < 2) continue
            val dates = similar.map { Dates.localDate(it.txn.timestamp, zone) }
            val gap = ChronoUnit.DAYS.between(dates[dates.size - 2], dates.last())
            val everyMonths = when {
                gap in 24L..38L -> 1
                Cadence.QUARTERLY.fits(gap) -> 3
                Cadence.YEARLY.fits(gap) -> 12
                else -> continue
            }
            // Missed two cycles: probably closed.
            if (ChronoUnit.DAYS.between(lastDate, today) > everyMonths * 30L * 2 + 10) continue
            var next = lastDate.plusMonths(everyMonths.toLong())
            while (next.isBefore(today.minusDays(3))) next = next.plusMonths(everyMonths.toLong())
            val days = ChronoUnit.DAYS.between(today, next)
            val kind = classify(last.txn.merchant, last.sms, last.txn.category).let { if (it == AutoDebitKind.OTHER) AutoDebitKind.AUTO_DEBIT else it }
            out += AutoDebitItem(
                key = "nach:$key",
                name = last.txn.merchant ?: (kind.label + (last.txn.account?.let { " · $it" } ?: "")),
                kind = kind,
                amountMinor = last.txn.amountMinor,
                cadenceLabel = when (everyMonths) { 3 -> "Quarterly"; 12 -> "Yearly"; else -> "Monthly" },
                nextDate = next,
                daysLeft = days,
                state = state(days),
                source = AutoDebitSource.DETECTED,
                lastPaid = lastDate,
                occurrences = similar.size,
                account = last.txn.account,
                everyMonths = everyMonths,
            )
        }
        return out
    }

    /**
     * Builds the full list: the user's own plans, EMI alerts from the bank, recurring payments and mandate
     * debits found in the SMS history. Detected rows that duplicate a plan or a bank alert are dropped, and
     * so are [hidden] keys. Payees in [notRecurring] (see [payeeKey]) and payees whose latest payment was a
     * family UPI payment are never listed as detected.
     */
    fun build(
        plans: List<AutoDebitPlan>,
        debits: List<DebitRecord>,
        recurring: List<RecurringPayment>,
        emiBills: List<BillDue>,
        hidden: Set<String>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        notRecurring: Set<String> = emptySet(),
    ): List<AutoDebitItem> {
        val emiItems = instalmentEmis(debits, today, zone)
        // An EMI plan without an end date takes the last instalment printed on the statements.
        val inferredEnds = RepeatEnds.forPlans(plans, emiItems)
        val planItems = plans.mapNotNull { p ->
            val end = inferredEnds[p.id] ?: return@mapNotNull planItem(p, debits, today, zone)
            val item = planItem(p.copy(endDate = end), debits, today, zone) ?: return@mapNotNull null
            val e = RepeatEnds.emiFor(p.name, p.amountMinor, p.account, p.matchWords, emiItems)
            item.copy(
                endInferred = true,
                instalmentsTotal = e?.instalmentsTotal,
                instalmentsPaid = e?.instalmentsTotal?.let { t -> item.remaining?.let { t - it } },
                remainingMinor = item.remaining?.let { it * p.amountMinor },
            )
        }
        // EMI lines are read above; as plain payments they would show again as a recurring "EMI principal".
        fun isEmiLine(name: String?) = name != null && (instalmentOf(name) != null || name.startsWith("EMI principal") || name.startsWith("EMI interest") ||
            name.endsWith(" EMI") || name.endsWith(" EMI interest"))

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

        val recurringItems = recurring.filterNot { isEmiLine(it.merchant) }.map { r ->
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

        val fixed = planItems + billItems + emiItems.filterNot { e ->
            // The user's own plan for the same EMI wins.
            planItems.any { p -> p.kind == AutoDebitKind.EMI && kotlin.math.abs(p.amountMinor - e.amountMinor) * 100 <= e.amountMinor * 15 && p.account == e.account }
        }
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
        val familyPayees = debits.groupBy { debitPayee(it) }
            .filterValues { g -> g.maxByOrNull { it.txn.timestamp }?.let { isFamilyUpi(it) } == true }.keys
        val detected = (recurringItems + mandateItems)
            .filterNot { duplicatesFixed(it) }
            .filterNot { d -> payeeKey(d).let { it != null && (it in notRecurring || it in familyPayees) } }

        return (fixed + detected).filter { it.key !in hidden }.sortedBy { it.nextDate }
    }

    fun totals(items: List<AutoDebitItem>, today: LocalDate): AutoDebitTotals {
        val soon = items.filter { !it.nextDate.isAfter(today.plusDays(30)) }
        val monthly = items.sumOf { it.amountMinor / it.everyMonths.coerceAtLeast(1) }
        return AutoDebitTotals(soon.sumOf { it.amountMinor }, soon.size, monthly)
    }
}
