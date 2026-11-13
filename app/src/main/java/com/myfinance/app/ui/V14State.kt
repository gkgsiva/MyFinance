package com.myfinance.app.ui

import com.myfinance.app.data.DismissedSuggestionEntity
import com.myfinance.app.data.FinanceRepository
import com.myfinance.app.data.LabelBudgetEntity
import com.myfinance.app.data.LoanDetailsEntity
import com.myfinance.app.data.RefundWatchEntity
import com.myfinance.app.data.SavedReportEntity
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.TxnLabelEntity
import com.myfinance.app.data.toLite
import com.myfinance.core.AutoDebitItem
import com.myfinance.core.AutoDebitKind
import com.myfinance.core.CardBillCheck
import com.myfinance.core.CardBills
import com.myfinance.core.EmiEnd
import com.myfinance.core.FamilyLabelRow
import com.myfinance.core.FamilyLabels
import com.myfinance.core.Granularity
import com.myfinance.core.Insights
import com.myfinance.core.LabelSuggest
import com.myfinance.core.LabelSuggestion
import com.myfinance.core.LoanPayoff
import com.myfinance.core.RefundItem
import com.myfinance.core.RefundWatch
import com.myfinance.core.Refunds
import com.myfinance.core.SalaryBreakdown
import com.myfinance.core.SalaryReport
import com.myfinance.core.StatementGap
import com.myfinance.core.TxnLite
import com.myfinance.core.TxnType
import com.myfinance.core.YearInReview
import com.myfinance.core.YearReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** A pinned Explore view with its numbers worked out, for the Overview. */
data class PinnedReport(
    val report: SavedReportEntity,
    val totalMinor: Long,
    val spentMinor: Long,
    val receivedMinor: Long,
    val rangeText: String,
    /** The last few periods, oldest first, for a small chart. */
    val recent: List<Long>,
    val recentLabels: List<String>,
)

/** An EMI with its loan details, for the loan payoff screen. */
/** The card bill payments list, with display names for cards (by last 4 digits) and bank accounts (by debit id). */
data class CardPaymentsView(
    val months: List<com.myfinance.core.CardMonth> = emptyList(),
    val cardNames: Map<String, String> = emptyMap(),
    val bankNames: Map<Long, String> = emptyMap(),
    val ready: Boolean = false,
    /** Each card's 14th-to-14th (statement day) cycle against the statement total. */
    val cycles: List<com.myfinance.core.CardCycle> = emptyList(),
    /** Each bank account per month: whether every entry is there (the running balance adds up). */
    val bankMonths: List<com.myfinance.core.BankMonth> = emptyList(),
)

data class LoanRow(val item: AutoDebitItem, val ratePct: Double?)

/**
 * The v1.4 features: auto-label suggestions, label budgets, card bill check, EMI end dates and loan payoff,
 * salary breakdown, refund tracker, year in review, statement reminders, family label view and pinned reports.
 * Built from the flows [FinanceViewModel] already has, so nothing is loaded twice.
 */
class V14State(
    private val scope: CoroutineScope,
    private val repo: FinanceRepository,
    private val allTransactions: StateFlow<List<TransactionEntity>>,
    /** Transactions that count in totals. */
    private val allLite: StateFlow<List<TxnLite>>,
    /** Including entries left out of totals: card bill payments are left out, but still pay the bill. */
    private val allLiteWithExcluded: StateFlow<List<TxnLite>>,
    private val meta: StateFlow<TxnMeta>,
    private val ownerName: StateFlow<String>,
    private val autoDebits: StateFlow<Pair<List<AutoDebitItem>, Set<String>>>,
    private val explore: (ExploreKind, String, TxnType?) -> List<TransactionEntity>,
    /** The month picked on the Overview. */
    private val monthTransactions: StateFlow<List<TransactionEntity>>,
) {
    private val dao = repo.v14
    private val zone get() = ZoneId.systemDefault()

    // ---- Label budgets ----

    val labelBudgets: StateFlow<Map<String, Long>> =
        dao.observeLabelBudgets().map { list -> list.associate { it.label to it.monthlyMinor } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** This month's spending per label with refunds taken off, as the budget alerts count it. */
    val labelMonthNet: StateFlow<Map<String, Long>> =
        combine(monthTransactions, meta) { list, m ->
            val out = HashMap<String, Long>()
            for (t in list) if (!t.excluded) for (l in m.labels(t).distinct()) {
                out.merge(l, if (t.type == TxnType.DEBIT) t.amountMinor else -t.amountMinor, Long::plus)
            }
            out.mapValues { it.value.coerceAtLeast(0) }
        }.flowOn(Dispatchers.Default).stateIn(scope, WhileShown, emptyMap())

    /** [minor] null or 0 removes the budget. */
    fun setLabelBudget(label: String, minor: Long?) = scope.launch {
        if (minor == null || minor <= 0) dao.deleteLabelBudget(label) else dao.putLabelBudget(LabelBudgetEntity(label, minor))
        repo.onDataChanged()
    }

    // ---- Auto-label suggestions ----

    private val dismissed = dao.observeDismissed()

    val suggestions: StateFlow<List<LabelSuggestion>> =
        combine(allLite, meta, dismissed, allTransactions) { lite, m, dis, all ->
            val idOf = all.associate { it.uid to it.id }
            val no = dis.mapNotNullTo(HashSet()) { d -> idOf[d.uid]?.let { it to d.label } }
            LabelSuggest.suggest(lite.filterNot { it.id in m.hiddenIds }, m.labelsById, no)
        }.flowOn(Dispatchers.Default).stateIn(scope, WhileShown, emptyList())

    private fun uidOf(id: Long): String? = allTransactions.value.firstOrNull { it.id == id }?.uid

    fun acceptSuggestions(list: List<LabelSuggestion>) = scope.launch {
        val uids = allTransactions.value.associate { it.id to it.uid }
        repo.v13.addLabels(list.mapNotNull { s -> uids[s.txnId]?.let { TxnLabelEntity(it, s.label) } })
        repo.onDataChanged()
    }

    fun dismissSuggestions(list: List<LabelSuggestion>) = scope.launch {
        val uids = allTransactions.value.associate { it.id to it.uid }
        dao.dismiss(list.mapNotNull { s -> uids[s.txnId]?.let { DismissedSuggestionEntity(it, s.label) } })
    }

    fun resetDismissed() = scope.launch { dao.clearDismissed() }

    // ---- Card bill check ----

    val cardChecks: StateFlow<List<CardBillCheck>> =
        combine(repo.bills, allLiteWithExcluded) { bills, lite ->
            val list = bills.map { b ->
                Triple(b.toBill(), com.myfinance.core.Dates.localDate(b.receivedAt, zone), b.markedPaid)
            }
            CardBills.check(list, lite, LocalDate.now(), zone)
        }.flowOn(Dispatchers.Default).stateIn(scope, WhileShown, emptyList())

    // ---- Card bill payments, month by month ----

    /**
     * Card bills paid from the bank account, matched each month with the payment received on the card. Worked out
     * only while the screen is open.
     */
    val cardPayments: StateFlow<CardPaymentsView> =
        combine(allTransactions, repo.bills, repo.myAccounts) { all, bills, mine ->
            val cardWords = Regex("""credit\s*card""", RegexOption.IGNORE_CASE)
            fun tail4(s: String?) = s?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.isNotEmpty() }
            val cards = mine.filter { it.isCard }.mapTo(HashSet()) { it.tail }
            bills.map { it.toBill() }.filter { it.kind == com.myfinance.core.BillKind.CREDIT_CARD }.mapNotNullTo(cards) { tail4(it.accountTail) }
            all.filter { it.type == TxnType.CREDIT && it.rawSms?.let(cardWords::containsMatchIn) == true }.mapNotNullTo(cards) { tail4(it.accountTail) }
            mine.filter { !it.isCard }.forEach { cards -= it.tail }
            val lines = all.filter { it.source != com.myfinance.app.data.TxnSource.MANUAL || it.accountTail != null }
                .map { com.myfinance.core.PayLine(it.id, it.type, it.amountMinor, it.timestamp, it.accountTail, it.merchant, it.rawSms, it.bank, it.balanceMinor) }
            val cardBills = bills.map { it.toBill() }
            val months = com.myfinance.core.CardPayments.monthly(lines, cards, cardBills, LocalDate.now(), zone)
            val billDays = mine.filter { it.isCard && it.billDay != null }.associate { it.tail to it.billDay!! }
            val cycles = com.myfinance.core.CardReconcile.cycles(lines, cards, cardBills, billDays, LocalDate.now(), zone)
            val bankMonths = com.myfinance.core.CardReconcile.bankMonths(lines, cards, mine.filter { !it.isCard }.mapTo(HashSet()) { it.tail }, zone)
            val names = mine.filter { it.isCard }.associate { it.tail to (it.label + (it.owner?.takeIf { o -> o.isNotBlank() }?.let { o -> " · $o" } ?: "")) }
            val banks = all.filter { it.type == TxnType.DEBIT }.associate { it.id to (it.bank?.let { b -> "$b " }.orEmpty() + (it.accountTail?.let { t -> "a/c ••${t.filter(Char::isDigit).takeLast(4)}" } ?: "bank")) }
            CardPaymentsView(months, names, banks, ready = true, cycles = cycles, bankMonths = bankMonths)
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), CardPaymentsView())

    // ---- EMI end dates and loan payoff ----

    private val loanRates = dao.observeLoans().map { l -> l.associate { it.planId to it.annualRatePct } }

    /** EMIs and loans (detected or the user's own), with the interest rate when one was entered. */
    val loans: StateFlow<List<LoanRow>> =
        combine(autoDebits, loanRates) { (items, _), rates ->
            items.filter { it.kind == AutoDebitKind.EMI || it.endDate != null }
                .map { LoanRow(it, it.planId?.let(rates::get)) }
                .sortedWith(compareBy<LoanRow> { it.item.endDate == null }.thenBy { it.item.endDate })
        }.stateIn(scope, WhileShown, emptyList())

    val emiEnds: StateFlow<List<EmiEnd>> =
        autoDebits.map { (items, _) -> LoanPayoff.endings(items) }.stateIn(scope, WhileShown, emptyList())

    fun setLoanRate(planId: String, ratePct: Double) = scope.launch { dao.putLoan(LoanDetailsEntity(planId, ratePct)) }

    // ---- Salary breakdown ----

    /** Calendar years (first) and financial years (second). */
    val salary: StateFlow<Pair<SalaryReport, SalaryReport>?> =
        combine(allLite, meta) { lite, m ->
            SalaryBreakdown.report(lite, m.labelsById, financialYear = false, zone = zone) to
                SalaryBreakdown.report(lite, m.labelsById, financialYear = true, zone = zone)
        }.flowOn(Dispatchers.Default).stateIn(scope, WhileShown, null)

    // ---- Refund tracker ----

    val refundOverdueDays: StateFlow<Int> = repo.refundOverdueDays

    fun setRefundOverdueDays(days: Int) = repo.setRefundOverdueDays(days)

    private val watches = dao.observeRefunds()

    val refunds: StateFlow<List<RefundItem>> =
        combine(watches, allLiteWithExcluded, allTransactions, refundOverdueDays) { w, lite, all, days ->
            val idOf = all.associate { it.uid to it.id }
            Refunds.track(w.mapNotNull { e -> idOf[e.uid]?.let { RefundWatch(it, e.expectedMinor, e.since, e.closed) } }, lite, LocalDate.now(), days, zone)
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Lazily, emptyList())

    /** Recent payments to IRCTC, Amazon, airlines and the like, in case one was cancelled. */
    val refundCandidates: StateFlow<List<TxnLite>> =
        combine(watches, allLite, allTransactions) { w, lite, all ->
            val idOf = all.associate { it.uid to it.id }
            Refunds.candidates(lite, w.mapNotNullTo(HashSet()) { idOf[it.uid] }, LocalDate.now(), zone = zone)
        }.flowOn(Dispatchers.Default).stateIn(scope, WhileShown, emptyList())

    /** Uids being watched, for the edit screen switch. */
    val watchedUids: StateFlow<Set<String>> =
        watches.map { w -> w.filter { !it.closed }.mapTo(HashSet()) { it.uid } }.stateIn(scope, WhileShown, emptySet())

    fun watchRefund(uid: String, expectedMinor: Long?) = scope.launch {
        dao.putRefund(RefundWatchEntity(uid, expectedMinor, System.currentTimeMillis()))
    }

    fun watchRefund(id: Long, expectedMinor: Long? = null) { uidOf(id)?.let { watchRefund(it, expectedMinor) } }

    fun closeRefund(id: Long) = scope.launch {
        val uid = uidOf(id) ?: return@launch
        val w = refunds.value.firstOrNull { it.payment.id == id }?.watch ?: return@launch
        dao.putRefund(RefundWatchEntity(uid, w.expectedMinor, w.since, closed = true))
    }

    fun stopWatching(uid: String) = scope.launch { dao.deleteRefund(uid) }

    fun stopWatching(id: Long) { uidOf(id)?.let { stopWatching(it) } }

    // ---- Year in review ----

    fun reviewYears(financialYear: Boolean): List<Int> = YearReview.years(allLite.value, financialYear, zone)

    fun yearReview(startYear: Int, financialYear: Boolean): YearInReview =
        YearReview.build(allLite.value, meta.value.labelsById, startYear, financialYear, zone)

    fun reviewShareText(r: YearInReview): String = YearReview.shareText(r, ownerName.value)

    // ---- Statement reminders ----

    val statementReminderDay: StateFlow<Int> = repo.statementReminderDay

    fun setStatementReminderDay(day: Int) = repo.setStatementReminderDay(day)

    suspend fun statementGaps(): List<StatementGap> = repo.statementGaps()

    // ---- Family label view ----

    /** Label x person spending between [from] and [to] (inclusive). */
    fun familyLabels(from: LocalDate, to: LocalDate): Pair<List<String>, List<FamilyLabelRow>> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = allLite.value.filter { it.timestamp in start until end }
        return FamilyLabels.table(rows, meta.value.labelsById, ownerName.value)
    }

    // ---- Saved reports pinned to the Overview ----

    val savedReports: StateFlow<List<SavedReportEntity>> =
        dao.observeReports().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Set just before opening Explore from a pinned report, so Explore starts with its settings. */
    val pendingExplore = MutableStateFlow<SavedReportEntity?>(null)

    fun saveReport(r: SavedReportEntity) = scope.launch { dao.putReport(r) }
    fun deleteReport(id: String) = scope.launch { dao.deleteReport(id) }

    val pinned: StateFlow<List<PinnedReport>> =
        combine(savedReports, allTransactions, meta) { list, _, _ -> list.map { summarise(it) } }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, WhileShown, emptyList())

    private fun summarise(r: SavedReportEntity): PinnedReport {
        val (from, to) = rangeOf(r)
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val kind = ExploreKind.entries.firstOrNull { it.name == r.kind } ?: ExploreKind.LABEL
        val rows = explore(kind, r.value, null).filter { it.timestamp in start until end }
        val me = ownerName.value
        val out = rows.filter { it.type == TxnType.DEBIT }
        val back = rows.filter { it.type == TxnType.CREDIT }
        val spent = out.sumOf { it.amountMinor }
        val received = back.sumOf { it.amountMinor }
        val total = when (r.type) { "DEBIT" -> spent; "CREDIT" -> received; else -> spent - received }
        val by = Granularity.entries.firstOrNull { it.name == r.granularity } ?: Granularity.MONTH
        val chosen = when (r.type) { "DEBIT" -> out; "CREDIT" -> back; else -> rows }
        val trend = Insights.trend(chosen.map { it.toLite(me) }, from, to, by, zone)
        // Net: refunds count as negative in the bars.
        val values = if (r.type == null) {
            val o = Insights.trend(out.map { it.toLite(me) }, from, to, by, zone)
            val b = Insights.trend(back.map { it.toLite(me) }, from, to, by, zone)
            o.indices.map { (o[it].totalMinor - (b.getOrNull(it)?.totalMinor ?: 0)).coerceAtLeast(0) }
        } else trend.map { it.totalMinor }
        return PinnedReport(
            report = r, totalMinor = total, spentMinor = spent, receivedMinor = received,
            rangeText = rangeText(r),
            recent = values.takeLast(6),
            recentLabels = trend.takeLast(6).map { it.label },
        )
    }

    companion object {
        val ranges = listOf("3m" to "Last 3 months", "1y" to "Last 12 months", "3y" to "Last 3 years", "all" to "All time")

        fun rangeOf(r: SavedReportEntity, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> = when (r.range) {
            "3m" -> today.minusMonths(2).withDayOfMonth(1) to today
            "1y" -> today.minusMonths(11).withDayOfMonth(1) to today
            "3y" -> today.minusYears(3).plusMonths(1).withDayOfMonth(1) to today
            "all" -> LocalDate.of(2000, 1, 1) to today
            else -> (r.fromEpochDay?.let(LocalDate::ofEpochDay) ?: today.minusYears(1)) to (r.toEpochDay?.let(LocalDate::ofEpochDay) ?: today)
        }

        fun rangeText(r: SavedReportEntity): String =
            ranges.firstOrNull { it.first == r.range }?.second ?: rangeOf(r).let { (a, b) ->
                val f = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
                "${a.format(f)} to ${b.format(f)}"
            }
    }
}
