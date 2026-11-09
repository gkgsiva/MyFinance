package com.myfinance.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myfinance.app.MyFinanceApp
import com.myfinance.app.data.AutoDebitEntity
import com.myfinance.app.data.BillEntity
import com.myfinance.app.data.DeletedTxnEntity
import com.myfinance.app.data.HiddenAutoDebitEntity
import com.myfinance.app.data.CategoryEntity
import com.myfinance.app.data.FinanceRepository
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.accountLabel
import com.myfinance.app.data.toLite
import com.myfinance.app.report.PdfStatement
import com.myfinance.core.AutoDebitItem
import com.myfinance.core.AutoDebitPlan
import com.myfinance.core.AutoDebitTotals
import com.myfinance.core.AutoDebits
import com.myfinance.core.BalanceReading
import com.myfinance.core.DebitRecord
import com.myfinance.core.GroupTotal
import com.myfinance.core.Labels
import com.myfinance.core.TxnType
import com.myfinance.core.BillParser
import com.myfinance.core.FinanceSummary
import com.myfinance.core.FySummary
import com.myfinance.core.MerchantStat
import com.myfinance.core.ModeShare
import com.myfinance.core.MonthCycle
import com.myfinance.core.MonthTotals
import com.myfinance.core.RecurringDetector
import com.myfinance.core.RecurringPayment
import com.myfinance.core.Reports
import com.myfinance.core.Series
import com.myfinance.core.TxnFilter
import com.myfinance.core.TxnLite
import com.myfinance.core.UnusualSpend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class GroupBy(val label: String) { CATEGORY("Category"), LABEL("Label"), MERCHANT("Merchant"), ACCOUNT("Account"), PERSON("Person") }

data class ImportState(
    val running: Boolean = false,
    val lastAdded: Int? = null,
    val error: String? = null,
    /** Full result of the last scan, for the "Rescan messages" screen. */
    val scan: FinanceRepository.ScanResult? = null,
)

data class SyncState(val running: Boolean = false, val message: String? = null)

data class ReportsData(
    val months: List<YearMonth> = emptyList(),
    val monthly: List<MonthTotals> = emptyList(),
    val categoryTrends: List<Series> = emptyList(),
    val perPerson: List<Series> = emptyList(),
    val topByAmount: List<MerchantStat> = emptyList(),
    val topByCount: List<MerchantStat> = emptyList(),
    val modes: List<ModeShare> = emptyList(),
    val financialYears: List<FySummary> = emptyList(),
    val unusual: List<UnusualSpend> = emptyList(),
    /** Spending per label over the report window. */
    val byLabel: List<GroupTotal> = emptyList(),
)

/** Labels and the "hide from lists" flag, by transaction uid and by row id. */
data class TxnMeta(
    val hiddenUids: Set<String> = emptySet(),
    val labelsByUid: Map<String, List<String>> = emptyMap(),
    val hiddenIds: Set<Long> = emptySet(),
    val labelsById: Map<Long, List<String>> = emptyMap(),
) {
    fun labels(t: TransactionEntity): List<String> = labelsByUid[t.uid].orEmpty()
    fun hidden(t: TransactionEntity): Boolean = t.uid in hiddenUids
}

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as MyFinanceApp).repository

    /** Which month a transaction counts towards; set under Manage > Month for budgets and reports. */
    val monthCycle: StateFlow<MonthCycle> = repo.monthCycle

    private val _month = MutableStateFlow(repo.monthCycle.value.current())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    /** The budget month today is in: the latest one the month arrows can reach. */
    val currentMonth: StateFlow<YearMonth> = monthCycle.map { it.current() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.monthCycle.value.current())

    val query = MutableStateFlow("")
    val categoryFilter = MutableStateFlow<String?>(null)

    /** Date range, person, account, amount, type and mode filters for the Transactions tab. */
    val advancedFilter = MutableStateFlow(TxnFilter())
    val groupBy = MutableStateFlow(GroupBy.CATEGORY)

    /** How many months the Reports tab covers. */
    val reportMonths = MutableStateFlow(6)

    private val _import = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _import.asStateFlow()

    val ownerName: StateFlow<String> = repo.ownerName

    private val _sync = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _sync.asStateFlow()

    val syncUri: StateFlow<String?> = repo.syncUri
    val lastSync: StateFlow<Long?> = repo.lastSync
    val autoSync: StateFlow<Boolean> = repo.autoSync

    val categories: StateFlow<List<CategoryEntity>> =
        repo.categories.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val mergedModes: StateFlow<Map<String, String>> =
        repo.mergedModes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val allTransactions: StateFlow<List<TransactionEntity>> =
        repo.allTransactions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Labels and hidden flags. Hidden transactions still count in every total; they just aren't listed. */
    val txnMeta: StateFlow<TxnMeta> =
        combine(allTransactions, repo.v13.observeHiddenTxns(), repo.v13.observeLabels()) { all, hidden, labels ->
            val hiddenUids = hidden.mapTo(HashSet()) { it.uid }
            val byUid = labels.groupBy({ it.uid }, { it.label }).mapValues { (_, v) -> v.sorted() }
            val idOf = all.associate { it.uid to it.id }
            TxnMeta(
                hiddenUids = hiddenUids,
                labelsByUid = byUid,
                hiddenIds = hiddenUids.mapNotNullTo(HashSet()) { idOf[it] },
                labelsById = byUid.mapNotNull { (uid, l) -> idOf[uid]?.let { it to l } }.toMap(),
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TxnMeta())

    /** Labels in use, then the suggestions. */
    val labelChoices: StateFlow<List<String>> =
        txnMeta.map { m -> Labels.choices(m.labelsByUid.values.flatten()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Labels.suggestions)

    /** Labels in use, for the filter chips. */
    val usedLabels: StateFlow<List<String>> =
        txnMeta.map { m -> m.labelsByUid.values.flatten().distinct().sortedBy { it.lowercase() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val labelFilter = MutableStateFlow<String?>(null)
    val showHidden = MutableStateFlow(false)

    /** Every transaction as the reports see it, including ones left out of totals. */
    private val allLiteWithExcluded: StateFlow<List<TxnLite>> =
        combine(allTransactions, ownerName, mergedModes) { list, me, modes -> list.map { it.toLite(me, modes) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Transactions that count towards totals. */
    private val allLite: StateFlow<List<TxnLite>> =
        combine(allTransactions, ownerName, mergedModes) { list, me, modes ->
            list.filterNot { it.excluded }.map { it.toLite(me, modes) }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All transactions in the selected month, newest first. */
    val monthTransactions: StateFlow<List<TransactionEntity>> = combine(_month, monthCycle) { ym, cycle -> ym to cycle }
        .flatMapLatest { (ym, cycle) ->
            val zone = ZoneId.systemDefault()
            val range = cycle.loadRange(ym, zone)
            repo.transactionsBetween(range.first, range.last)
                .map { list -> list.filter { cycle.periodOf(it.timestamp, it.type, it.category, zone) == ym } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** This month's spending per label, for "Where it went" on the Overview. */
    val labelGroups: StateFlow<List<GroupTotal>> =
        combine(monthTransactions, ownerName, txnMeta) { list, me, meta ->
            Labels.spendByLabel(list.filterNot { it.excluded }.map { it.toLite(me) }, meta.labelsById)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val summary: StateFlow<FinanceSummary> = combine(monthTransactions, repo.ownerName) { list, me ->
        FinanceSummary.of(list.filterNot { it.excluded }.map { it.toLite(me) })
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FinanceSummary.of(emptyList()))

    /** The filter actually applied: the search box and category chip plus the advanced options. */
    val effectiveFilter: StateFlow<TxnFilter> =
        combine(query, categoryFilter, advancedFilter) { q, c, a -> a.copy(text = q, category = c) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TxnFilter())

    /**
     * The Transactions tab list: the selected month, or the chosen date range when one is set,
     * after the search and filters.
     */
    val filteredTransactions: StateFlow<List<TransactionEntity>> =
        combine(
            combine(monthTransactions, allTransactions, effectiveFilter) { m, a, f -> Triple(m, a, f) },
            combine(mergedModes, ownerName) { modes, me -> modes to me },
            combine(txnMeta, labelFilter, showHidden) { meta, label, showH -> Triple(meta, label, showH) },
        ) { (month, all, f), (modes, me), (meta, label, showH) ->
            val source = if (f.hasDateRange) all else month
            source.filter { t ->
                (showH || !meta.hidden(t)) &&
                    (label == null || label in meta.labels(t)) &&
                    t.toLite(me, modes).let { lite ->
                        f.matches(lite, t.note) ||
                            // The search box also finds labels.
                            (f.text.isNotBlank() && meta.labels(t).any { it.contains(f.text.trim(), ignoreCase = true) } && f.copy(text = "").matches(lite, t.note))
                    }
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** People and accounts to choose from in the filter dialog. */
    val filterOptions: StateFlow<Pair<List<String>, List<String>>> =
        combine(allTransactions, ownerName) { all, me ->
            all.map { it.owner ?: me }.distinct().sorted() to all.mapNotNull { it.accountLabel }.distinct().sorted()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<String>() to emptyList())

    val reports: StateFlow<ReportsData> =
        combine(allLite, _month, reportMonths, monthCycle, txnMeta) { txns, end, count, cycle, meta ->
            val zone = ZoneId.systemDefault()
            val months = Reports.months(end, count)
            val inWindow = months.toSet()
            val window = txns.filter { cycle.periodOf(it, zone) in inWindow }
            // Hidden payments count in the totals and trends but are not named in the lists.
            val shown = window.filterNot { it.id in meta.hiddenIds }
            val (byAmount, byCount) = Reports.topMerchants(shown, 10)
            ReportsData(
                months = months,
                monthly = Reports.monthly(txns, end, count, zone, cycle),
                categoryTrends = Reports.categoryTrends(txns, end, count, zone, cycle),
                perPerson = Reports.perPerson(txns, end, count, zone, cycle),
                topByAmount = byAmount,
                topByCount = byCount,
                modes = Reports.paymentModes(window),
                financialYears = Reports.financialYears(txns, zone),
                unusual = Reports.unusual(shown).take(20),
                byLabel = Labels.spendByLabel(window, meta.labelsById, includeUnlabelled = false),
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, ReportsData())

    val recurring: StateFlow<List<RecurringPayment>> =
        allLite.map { RecurringDetector.detect(it, LocalDate.now()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Card bills and EMIs that are still to be paid, soonest first. */
    val openBills: StateFlow<List<BillEntity>> =
        combine(repo.bills, allLiteWithExcluded) { bills, txns ->
            val from = LocalDate.now().minusDays(10).toEpochDay()
            FinanceRepository.dedupeBills(bills.filter { !it.markedPaid && it.dueEpochDay >= from })
                .filter { !BillParser.isPaid(it.toBill(), txns) }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val balances: StateFlow<List<BalanceReading>> =
        repo.balances.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun previousMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { if (_month.value < currentMonth.value) _month.value = _month.value.plusMonths(1) }

    /** Saves the month setting and jumps to the budget month today is in, so the screens line up. */
    fun setMonthCycle(cycle: MonthCycle) {
        repo.setMonthCycle(cycle)
        _month.value = cycle.current()
        viewModelScope.launch { repo.onDataChanged() }
    }

    fun clearFilters() {
        query.value = ""
        categoryFilter.value = null
        advancedFilter.value = TxnFilter()
    }

    /** Reads the SMS inbox. [monthsBack] 0 = everything; see [FinanceRepository.importInbox]. */
    fun importInbox(monthsBack: Int = 12, bringBackDeleted: Boolean = false) {
        if (_import.value.running) return
        _import.value = ImportState(running = true)
        viewModelScope.launch {
            _import.value = try {
                val r = repo.importInbox(monthsBack, bringBackDeleted)
                ImportState(lastAdded = r.added, scan = r)
            } catch (e: SecurityException) {
                ImportState(error = "SMS permission is needed to import messages.")
            } catch (e: Exception) {
                ImportState(error = e.message ?: "Import failed")
            }
        }
    }

    fun setOwnerName(name: String) = repo.setOwnerName(name)

    fun exportTo(uri: Uri) = runSync {
        val n = repo.exportTo(uri)
        "Exported $n transactions. Share this file with family to merge on their phone."
    }

    fun mergeFrom(uri: Uri) = runSync { describe(repo.mergeFrom(uri), "Added") }

    /** Prepares the export for sending, then hands the file link to [send] (which opens WhatsApp). */
    fun shareExport(send: (Uri) -> Unit) = runSync {
        val (uri, n) = repo.exportForShare()
        withContext(Dispatchers.Main) { send(uri) }
        "Shared $n transactions. On the main phone, open the file from WhatsApp with MyFinance to merge it."
    }

    /** Merges a file opened from WhatsApp ("Open with MyFinance") or shared to the app. */
    fun mergeShared(uri: Uri) = runSync { describe(repo.mergeShared(uri), "Added") }

    /** Links the shared family file in Google Drive, then syncs straight away. */
    fun linkSyncFile(uri: Uri) {
        repo.linkSyncFile(uri)
        syncNow()
    }

    fun unlinkSyncFile() {
        repo.unlinkSyncFile()
        _sync.value = SyncState(message = "Stopped syncing. Your data stays on this phone.")
    }

    fun setAutoSync(on: Boolean) = repo.setAutoSync(on)

    fun syncNow() = runSync { describe(repo.syncNow(), "Synced. Added") }

    private fun describe(r: FinanceRepository.MergeResult, verb: String) = buildString {
        append("$verb ${r.added} transactions")
        if (r.from.isNotEmpty()) append(" from ${r.from.joinToString(", ")}")
        if (r.duplicates > 0) append(", skipped ${r.duplicates} already here")
        if (r.unreadable > 0) append(", ${r.unreadable} rows could not be read")
        append(".")
    }

    /** Saves the Transactions tab's current list as a statement. */
    fun exportStatement(uri: Uri, excel: Boolean) = runSync {
        val n = repo.exportStatement(uri, filteredTransactions.value, excel)
        "Saved $n transactions to ${if (excel) "Excel" else "CSV"}."
    }

    /** Saves the selected month as a PDF statement for the whole family. */
    fun exportPdf(uri: Uri) = runSync {
        val app = getApplication<Application>()
        val month = _month.value
        val me = ownerName.value
        val modes = mergedModes.value
        val txns = monthTransactions.value.filterNot { it.excluded }.map { it.toLite(me, modes) }
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.use { PdfStatement.write(it, month, txns, me) }
                ?: error("Could not open file for writing")
        }
        "Saved the ${month.month.name.lowercase().replaceFirstChar { it.uppercase() }} statement as PDF."
    }

    private fun runSync(block: suspend () -> String) {
        if (_sync.value.running) return
        _sync.value = SyncState(running = true)
        viewModelScope.launch {
            _sync.value = try {
                SyncState(message = block())
            } catch (e: Exception) {
                SyncState(message = "Failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun markBillPaid(id: Long) = viewModelScope.launch { repo.markBillPaid(id, true) }
    fun deleteBill(id: Long) = viewModelScope.launch { repo.deleteBill(id) }

    suspend fun load(id: Long): TransactionEntity? = repo.get(id)

    fun save(txn: TransactionEntity, learnRule: Boolean) = viewModelScope.launch { repo.save(txn, learnRule) }
    // ---- Delete, undo and the Recently deleted bin ----

    private val _justDeleted = MutableStateFlow<TransactionEntity?>(null)

    /** The transaction deleted a moment ago, for the "Undo" snackbar. */
    val justDeleted: StateFlow<TransactionEntity?> = _justDeleted.asStateFlow()

    fun delete(txn: TransactionEntity) = viewModelScope.launch {
        repo.delete(txn)
        _justDeleted.value = txn
    }

    fun undoDelete() {
        val t = _justDeleted.value ?: return
        _justDeleted.value = null
        viewModelScope.launch { repo.restore(t.uid) }
    }

    fun clearJustDeleted() { _justDeleted.value = null }

    val recentlyDeleted: StateFlow<List<DeletedTxnEntity>> =
        repo.recentlyDeleted.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun restore(uid: String) = viewModelScope.launch { repo.restore(uid) }
    fun deleteForever(uid: String) = viewModelScope.launch { repo.deleteForever(uid) }
    fun emptyTrash() = viewModelScope.launch { repo.emptyTrash() }

    // ---- Auto debits & EMIs ----

    private val autoPlans = repo.v13.observePlans()
    private val hiddenAuto = repo.v13.observeHidden()

    /** EMIs, auto debits, SIPs, insurance and subscriptions with their next expected date, soonest first. */
    private val autoDebitsWithHidden: StateFlow<Pair<List<AutoDebitItem>, Set<String>>> =
        combine(autoPlans, allTransactions, recurring, openBills, hiddenAuto) { plans, all, rec, bills, hidden ->
            val me = ownerName.value
            val debits = all.filter { !it.excluded && it.type == TxnType.DEBIT }.map { DebitRecord(it.toLite(me), it.rawSms) }
            AutoDebits.build(
                plans = plans.map { it.toPlan() },
                debits = debits,
                recurring = rec,
                emiBills = bills.map { it.toBill() },
                hidden = emptySet(),
                today = LocalDate.now(),
            ) to hidden.mapTo(HashSet()) { it.key }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList<AutoDebitItem>() to emptySet())

    /** Rows shown in the list; hidden ones are left out unless [showHiddenAutoDebits] is on. */
    val showHiddenAutoDebits = MutableStateFlow(false)

    val autoDebits: StateFlow<List<AutoDebitItem>> =
        combine(autoDebitsWithHidden, showHiddenAutoDebits) { (all, hidden), show -> if (show) all else all.filter { it.key !in hidden } }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val hiddenAutoDebitKeys: StateFlow<Set<String>> =
        autoDebitsWithHidden.map { it.second }.stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    /** Totals include hidden rows: hiding only takes them off the list. */
    val autoDebitTotals: StateFlow<AutoDebitTotals> =
        autoDebitsWithHidden.map { AutoDebits.totals(it.first, LocalDate.now()) }
            .stateIn(viewModelScope, SharingStarted.Lazily, AutoDebitTotals(0, 0, 0))

    suspend fun autoPlan(id: String): AutoDebitPlan? =
        autoPlans.first().firstOrNull { it.id == id }?.toPlan()

    fun saveAutoPlan(p: AutoDebitPlan) = viewModelScope.launch { repo.v13.upsertPlan(AutoDebitEntity.from(p)) }
    fun deleteAutoPlan(id: String) = viewModelScope.launch { repo.v13.deletePlan(id) }
    fun hideAutoDebit(key: String) = viewModelScope.launch { repo.v13.hide(HiddenAutoDebitEntity(key)) }
    fun unhideAutoDebits() = viewModelScope.launch { repo.v13.unhideAll() }
    fun unhideAutoDebit(key: String) = viewModelScope.launch { repo.v13.unhide(key) }

    // ---- Labels, hiding and payee names ----

    suspend fun labelsOf(uid: String): List<String> = repo.labelsOf(uid)

    /**
     * Saves the extras set on the edit screen: labels, hidden flag, and a payee name for all of this payee's
     * transactions when [renameFrom] (the row as it was before editing) is given. Saves [txn] first, in the same
     * coroutine, so the rename never races the edit.
     */
    fun saveWithExtras(
        txn: TransactionEntity,
        learnRule: Boolean,
        labels: List<String>,
        hidden: Boolean,
        labelAll: Boolean,
        renameFrom: TransactionEntity?,
    ) =
        viewModelScope.launch {
            repo.save(txn, learnRule)
            val name = txn.merchant
            if (renameFrom != null && name != null) repo.renamePayee(renameFrom, name)
            repo.setLabels(txn, labels, hidden, labelAll)
        }

    fun setHidden(txn: TransactionEntity, hidden: Boolean) = viewModelScope.launch {
        repo.setLabels(txn, repo.labelsOf(txn.uid), hidden, forMerchant = false)
    }

    fun renameLabel(from: String, to: String) = viewModelScope.launch { repo.renameLabel(from, to) }
    fun deleteLabel(label: String) = viewModelScope.launch { repo.deleteLabel(label) }

    fun addCategory(name: String, budgetMinor: Long?) = viewModelScope.launch { repo.addCategory(name, budgetMinor) }
    fun updateCategory(c: CategoryEntity) = viewModelScope.launch { repo.updateCategory(c) }
    fun deleteCategory(name: String) = viewModelScope.launch { repo.deleteCategory(name) }
}
