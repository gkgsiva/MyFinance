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
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 1.9: balances and card use, this month against last, cash / UPI / card, fixed and flexible spending,
 * the savings rate over time, the family summary to share, meal card and FASTag pages, weekly digest switch.
 */
class V19State(
    scope: CoroutineScope,
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

    private val _weekly = MutableStateFlow(repo.weeklyDigestOn)
    val weeklyDigest: StateFlow<Boolean> = _weekly
    fun setWeeklyDigest(on: Boolean) { repo.setWeeklyDigest(on); _weekly.value = on }

}
