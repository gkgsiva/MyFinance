package com.myfinance.app.ui

import com.myfinance.app.data.FinanceRepository
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.toLite
import com.myfinance.core.AccountBalances
import com.myfinance.core.BalanceReading
import com.myfinance.core.BalancesOverview
import com.myfinance.core.Categorizer
import com.myfinance.core.DashPeriod
import com.myfinance.core.FamilySummary
import com.myfinance.core.FixedFlexible
import com.myfinance.core.FixedSpend
import com.myfinance.core.Instrument
import com.myfinance.core.InstrumentView
import com.myfinance.core.Instruments
import com.myfinance.core.ModeShare
import com.myfinance.core.ModeSplit
import com.myfinance.core.MonthCompare
import com.myfinance.core.MonthComparison
import com.myfinance.core.MonthCycle
import com.myfinance.core.MyAccount
import com.myfinance.core.RecurringPayment
import com.myfinance.core.SavingsPoint
import com.myfinance.core.SavingsTrend
import com.myfinance.core.TxnLite
import com.myfinance.core.TxnType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 1.9: balances and card use, this month against last, cash / UPI / card, fixed and flexible spending,
 * the savings rate over time, the family summary to share, meal card and FASTag pages, weekly digest switch.
 */
class V19State(
    private val scope: CoroutineScope,
    private val repo: FinanceRepository,
    allTransactions: StateFlow<List<TransactionEntity>>,
    allLite: StateFlow<List<TxnLite>>,
    month: StateFlow<YearMonth>,
    cycle: StateFlow<MonthCycle>,
    ownerName: StateFlow<String>,
    balances: StateFlow<List<BalanceReading>>,
    myAccounts: StateFlow<List<MyAccount>>,
    recurring: StateFlow<List<RecurringPayment>>,
    dashPeriod: StateFlow<DashPeriod>,
) {
    private val zone get() = ZoneId.systemDefault()

    private fun <T> state(initial: T, flow: kotlinx.coroutines.flow.Flow<T>, scope: CoroutineScope): StateFlow<T> =
        flow.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Lazily, initial)

    val balancesOverview: StateFlow<BalancesOverview> = state(
        BalancesOverview(emptyList(), emptyList()),
        combine(balances, myAccounts, allLite) { readings, accounts, txns ->
            // Spending on each card this calendar month, for cards with no limit / available-limit SMS.
            val thisMonth = YearMonth.now()
            val spends = HashMap<String, Long>()
            txns.filter { it.type == TxnType.DEBIT && YearMonth.from(com.myfinance.core.Dates.localDate(it.timestamp, zone)) == thisMonth }
                .forEach { t -> t.account?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 }?.let { spends.merge(it, t.amountMinor, Long::plus) } }
            val cardSpends = accounts.filter { it.isCard }.associate { it.tail to (spends[it.tail] ?: 0L) }
            AccountBalances.overview(readings, accounts, cardSpends = cardSpends)
        },
        scope,
    )

    val compare: StateFlow<MonthComparison?> = state(
        null,
        combine(allLite, month, cycle) { txns, m, c -> MonthCompare.of(txns, m, LocalDate.now(), c, zone) },
        scope,
    )

    /** Cash, UPI, card and net banking over the Dashboard's period. */
    val modes: StateFlow<List<ModeShare>> = state(
        emptyList(),
        combine(allLite, month, cycle, dashPeriod) { txns, m, c, p -> ModeSplit.of(txns, p.months(m).toSet(), c, zone) },
        scope,
    )

    val fixedFlexible: StateFlow<FixedFlexible?> = state(
        null,
        combine(allLite, month, cycle, recurring) { txns, m, c, rec ->
            FixedSpend.of(txns, m, rec.mapTo(HashSet()) { Categorizer.merchantKey(it.merchant) }, c, zone)
        },
        scope,
    )

    val savings: StateFlow<List<SavingsPoint>> = state(
        emptyList(),
        combine(allLite, month, cycle) { txns, m, c -> SavingsTrend.of(txns, m, 12, c, zone) },
        scope,
    )

    val familySummary: StateFlow<String> = state(
        "",
        combine(allLite, month, cycle, ownerName) { txns, m, c, me -> FamilySummary.text(txns, m, me, c, zone) },
        scope,
    )

    /** Meal card and FASTag pages; null when that instrument has never been seen. */
    val instruments: StateFlow<Map<Instrument, InstrumentView>> = state(
        emptyMap(),
        combine(allTransactions, ownerName) { all, me ->
            val rows = all.filterNot { it.excluded }.map { t -> Triple(t.toLite(me), t.rawSms, t.balanceMinor) }
            val now = YearMonth.now()
            Instrument.entries.associateWith { Instruments.view(it, rows, now, zone) }.filterValues { it.entries.isNotEmpty() }
        },
        scope,
    )

    // ---- 1.10: import history, missing statements, uncategorised cleanup, backup ----

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun clearMessage() { _message.value = null }

    val importBatches: StateFlow<List<com.myfinance.app.data.ImportBatchEntity>> = state(emptyList(), repo.importBatches, scope)

    fun undoImport(id: Long) = scope.launch {
        val n = runCatching { repo.undoImport(id) }.getOrElse { e -> _message.value = "Could not undo: ${e.message}"; return@launch }
        _message.value = "Removed $n entries from that import. You can import the same files again."
    }

    val coverage: StateFlow<List<com.myfinance.core.AccountCoverage>> = state(
        emptyList(),
        combine(allTransactions, ownerName) { all, me ->
            com.myfinance.core.MissingStatements.find(all.filter { it.source == com.myfinance.app.data.TxnSource.STATEMENT }.map { it.toLite(me) }, LocalDate.now(), zone)
        },
        scope,
    )

    val uncategorised: StateFlow<List<com.myfinance.core.UncategorisedGroup>> = state(
        emptyList(),
        combine(allLite, month) { txns, _ -> com.myfinance.core.Uncategorised.groups(txns) },
        scope,
    )

    fun categorise(g: com.myfinance.core.UncategorisedGroup, category: String) = scope.launch {
        val n = runCatching { repo.categorise(g.ids, g.name.takeIf { g.key.isNotBlank() }, category) }.getOrElse { e -> _message.value = "Could not save: ${e.message}"; return@launch }
        _message.value = "$n entries from ${g.name} are now $category" + if (g.key.isNotBlank()) ", and future ones will be too." else "."
    }

    fun applyRulesToAll() = scope.launch {
        val n = runCatching { repo.applyRulesToExisting() }.getOrDefault(0)
        _message.value = if (n == 0) "Your rules already match every entry." else "Your rules changed $n older entries, statements included."
    }

    // ---- 1.11: card bills, salary month by itself ----

    /** Each card with a bill day: the last bill, what's on the next one, and whether it was paid. */
    val cardCycles: StateFlow<List<com.myfinance.core.CardCycleView>> = state(
        emptyList(),
        combine(allTransactions, myAccounts) { all, accounts ->
            val cards = accounts.filter { it.isCard && it.billDay != null }
            val lines = repo.cardLines(all, cards)
            cards.mapNotNull { com.myfinance.core.CardCycles.view(it, lines, LocalDate.now(), zone) }
        },
        scope,
    )

    private val _salaryState = MutableStateFlow(repo.salaryAutoState)

    /**
     * A salary that arrives in the last week, so the month it is for shows it as income. Found by itself the
     * first time; [SalaryNote.applied] says it was switched on (the user can keep it or turn it off).
     */
    data class SalaryNote(val guess: com.myfinance.core.SalaryGuess, val applied: Boolean)

    val salaryNote: StateFlow<SalaryNote?> = state(
        null,
        combine(allLite, cycle, _salaryState) { txns, c, st ->
            when {
                st == "applied" && c.salaryFromDay != null ->
                    com.myfinance.core.SalaryDetect.guess(txns, LocalDate.now(), zone)?.let { SalaryNote(it, true) }
                    ?: c.salaryFromDay?.let { d -> SalaryNote(com.myfinance.core.SalaryGuess(d, null, emptyList(), 0, true, d), true) }
                st != null || c.salaryFromDay != null -> null
                else -> com.myfinance.core.SalaryDetect.guess(txns, LocalDate.now(), zone)?.let { SalaryNote(it, false) }
            }
        },
        scope,
    )

    init {
        // Salary already in the Salary category: switch it on straight away (the Home card says so and can undo it).
        scope.launch {
            salaryNote.collect { n -> if (n != null && !n.applied && n.guess.alreadySalary) useSalaryDay(n.guess) }
        }
    }

    /** Count salary from [g]'s day for the next month; credits not in the Salary category yet are put there. */
    fun useSalaryDay(g: com.myfinance.core.SalaryGuess) = scope.launch {
        if (!g.alreadySalary && g.ids.isNotEmpty()) runCatching { repo.categorise(g.ids, g.payer, com.myfinance.core.DefaultCategories.SALARY) }
        repo.setMonthCycle(repo.monthCycle.value.copy(salaryFromDay = g.fromDay), byUser = false)
        repo.salaryAutoState = "applied"
        _salaryState.value = "applied"
    }

    fun salaryNoteDone(keep: Boolean) {
        if (!keep) repo.setMonthCycle(repo.monthCycle.value.copy(salaryFromDay = null), byUser = false)
        val st = if (keep) "ok" else "dismissed"
        repo.salaryAutoState = st
        _salaryState.value = st
    }

    /** Days since the last backup or saved export (or since first use), for the Home nudge. */
    fun backupDaysAgo(): Long = com.myfinance.core.BackupNudge.daysSince(repo.lastBackupAt, repo.firstUseAt, System.currentTimeMillis())

    private val _weekly = MutableStateFlow(repo.weeklyDigestOn)
    val weeklyDigest: StateFlow<Boolean> = _weekly
    fun setWeeklyDigest(on: Boolean) { repo.setWeeklyDigest(on); _weekly.value = on }

}
