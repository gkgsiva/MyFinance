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
import com.myfinance.core.LabelNet
import com.myfinance.core.IncomeYear
import com.myfinance.core.Insights
import com.myfinance.core.Fact
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
import kotlinx.coroutines.flow.debounce
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
    val byLabel: List<LabelNet> = emptyList(),
    /** Income per label per year: year label -> (label, amount). */
    val incomeLabelsByFy: List<Pair<String, List<Pair<String, Long>>>> = emptyList(),
)

enum class ExploreKind(val label: String) { LABEL("Label"), PAYEE("Payee"), CATEGORY("Category"), ACCOUNT("Account"), PERSON("Person") }

/** Labels and the "hide from lists" flag, by transaction uid and by row id. */
data class TxnMeta(
    val hiddenUids: Set<String> = emptySet(),
    val labelsByUid: Map<String, List<String>> = emptyMap(),
    val hiddenIds: Set<Long> = emptySet(),
    val labelsById: Map<Long, List<String>> = emptyMap(),
) {
    /** Parts of a split payment (uid "<payment uid>#n") share the payment's labels and hidden flag. */
    fun labels(t: TransactionEntity): List<String> = labelsByUid[t.uid.substringBefore('#')].orEmpty()
    fun hidden(t: TransactionEntity): Boolean = t.uid.substringBefore('#') in hiddenUids
}

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
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

    /** Every transaction, with split payments shown as their parts (see [SplitView]). */
    val allTransactions: StateFlow<List<TransactionEntity>> =
        combine(repo.allTransactions(), repo.v16.observeParts()) { list, parts -> SplitView.expand(list, parts) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** False until the stored transactions have been read once, so screens show "Loading" rather than "Nothing here". */
    val loaded: StateFlow<Boolean> = repo.allTransactions().map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Labels and hidden flags. Hidden transactions still count in every total; they just aren't listed. */
    val txnMeta: StateFlow<TxnMeta> =
        combine(allTransactions, repo.v13.observeHiddenTxns(), repo.v13.observeLabels()) { all, hidden, labels ->
            val hiddenUids = hidden.mapTo(HashSet()) { it.uid }
            val byUid = labels.groupBy({ it.uid }, { it.label }).mapValues { (_, v) -> v.sorted() }
            val idOf = all.associate { it.uid to it.id }
            // Parts of a split payment carry the payment's labels and hidden flag.
            val partIds = all.filter { '#' in it.uid }.groupBy({ it.uid.substringBefore('#') }, { it.id })
            fun ids(uid: String) = listOfNotNull(idOf[uid]) + partIds[uid].orEmpty()
            TxnMeta(
                hiddenUids = hiddenUids,
                labelsByUid = byUid,
                hiddenIds = hiddenUids.flatMapTo(HashSet()) { ids(it) },
                labelsById = byUid.flatMap { (uid, l) -> ids(uid).map { it to l } }.toMap(),
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TxnMeta())

    /** Labels in use, then the suggestions. */
    val labelChoices: StateFlow<List<String>> =
        txnMeta.map { m -> Labels.choices(m.labelsByUid.values.flatten()) }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, Labels.suggestions)

    /** Choices for money received: income labels (Salary, Interest, Rent income…) first. */
    val incomeLabelChoices: StateFlow<List<String>> =
        txnMeta.map { m -> Labels.choices(m.labelsByUid.values.flatten(), income = true) }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, Labels.incomeSuggestions)

    /** Labels in use, for the filter chips. */
    val usedLabels: StateFlow<List<String>> =
        txnMeta.map { m -> m.labelsByUid.values.flatten().distinct().sortedBy { it.lowercase() } }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val labelFilter = MutableStateFlow<String?>(null)
    val showHidden = MutableStateFlow(false)

    /** Every transaction in the reports' form, worked out once per change: all of them, and those that count. */
    private val lites: StateFlow<Pair<List<TxnLite>, List<TxnLite>>> =
        combine(allTransactions, ownerName, mergedModes) { list, me, modes ->
            val all = list.map { it.toLite(me, modes) }
            all to all.filterIndexed { i, _ -> !list[i].excluded }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<TxnLite>() to emptyList())

    /** Every transaction as the reports see it, including ones left out of totals. */
    private val allLiteWithExcluded: StateFlow<List<TxnLite>> =
        lites.map { it.first }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Transactions that count towards totals. */
    private val allLite: StateFlow<List<TxnLite>> =
        lites.map { it.second }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All transactions in the selected month, newest first. */
    val monthTransactions: StateFlow<List<TransactionEntity>> = combine(_month, monthCycle) { ym, cycle -> ym to cycle }
        .flatMapLatest { (ym, cycle) ->
            val zone = ZoneId.systemDefault()
            val range = cycle.loadRange(ym, zone)
            repo.transactionsBetween(range.first, range.last)
                .map { list -> list.filter { cycle.periodOf(it.timestamp, it.type, it.category, zone) == ym } }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** This month's spending per label, for "Where it went" on the Overview. */
    val labelGroups: StateFlow<List<GroupTotal>> =
        combine(monthTransactions, ownerName, txnMeta) { list, me, meta ->
            Labels.spendByLabel(list.filterNot { it.excluded }.map { it.toLite(me) }, meta.labelsById)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, WhileShown, emptyList())

    val summary: StateFlow<FinanceSummary> = combine(monthTransactions, repo.ownerName) { list, me ->
        FinanceSummary.of(list.filterNot { it.excluded }.map { it.toLite(me) })
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, FinanceSummary.of(emptyList()))

    /**
     * Home's ledger: the budget month today is in (salary month setting applied), whatever month the
     * Transactions tab is showing. Left-out rows (own transfers, card bill payments) aren't in it.
     */
    val homeLedger: StateFlow<com.myfinance.core.MonthLedger> =
        combine(allLite, currentMonth, monthCycle, txnMeta) { lite, ym, cycle, meta ->
            com.myfinance.core.MonthLedger.build(lite, ym, cycle, ZoneId.systemDefault(), meta.hiddenIds)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, com.myfinance.core.MonthLedger(repo.monthCycle.value.current(), 0, 0, emptyList(), 0))

    /** Transactions with a bill photo, by uid. */
    private val photoUids: StateFlow<Set<String>> =
        repo.v12.receiptUids.map { it.toSet() }.stateIn(viewModelScope, WhileShown, emptySet())

    /** The user's nicknames for payments, by uid (split parts use their payment's). */
    val nicknames: StateFlow<Map<String, String>> =
        repo.nicknames.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun nicknameFor(t: TransactionEntity): String? = nicknames.value[t.uid.substringBefore('#')]

    fun setNickname(uid: String, name: String?) = viewModelScope.launch { repo.setNickname(uid.substringBefore('#'), name) }

    /** The filter actually applied: the search box and category chip plus the advanced options. */
    val effectiveFilter: StateFlow<TxnFilter> =
        // Typing waits a moment before the list is searched again, so each key press doesn't filter every entry.
        combine(query.debounce { if (it.isBlank()) 0L else 250L }, categoryFilter, advancedFilter) { q, c, a -> a.copy(text = q, category = c) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TxnFilter())

    /**
     * The Transactions tab list: the selected month, or the chosen date range when one is set,
     * after the search and filters.
     */
    val filteredTransactions: StateFlow<List<TransactionEntity>> =
        combine(
            combine(monthTransactions, allTransactions, effectiveFilter) { m, a, f -> Triple(m, a, f) },
            combine(mergedModes, ownerName, combine(photoUids, nicknames) { p, n -> p to n }) { modes, me, pn -> Triple(modes, me, pn) },
            combine(txnMeta, labelFilter, showHidden) { meta, label, showH -> Triple(meta, label, showH) },
        ) { (month, all, f), (modes, me, pn), (meta, label, showH) ->
            val (photos, nicks) = pn
            val source = if (f.hasDateRange) all else month
            source.filter { t ->
                (showH || !meta.hidden(t)) &&
                    (label == null || label in meta.labels(t)) &&
                    t.toLite(me, modes).let { lite ->
                        val photo = t.uid.substringBefore('#') in photos
                        val nick = nicks[t.uid.substringBefore('#')]
                        f.matches(lite, t.note, photo, nick) ||
                            // The search box also finds labels.
                            (f.text.isNotBlank() && meta.labels(t).any { it.contains(f.text.trim(), ignoreCase = true) } && f.copy(text = "").matches(lite, t.note, photo, nick))
                    }
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** People and accounts to choose from in the filter dialog. */
    val filterOptions: StateFlow<Pair<List<String>, List<String>>> =
        combine(allTransactions, ownerName) { all, me ->
            all.map { it.owner ?: me }.distinct().sorted() to all.mapNotNull { it.accountLabel }.distinct().sorted()
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<String>() to emptyList())

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
                byLabel = Labels.netByLabel(window, meta.labelsById),
                incomeLabelsByFy = txns.filter { it.type == TxnType.CREDIT && meta.labelsById[it.id] != null }
                    .groupBy { com.myfinance.core.Dates.financialYearStart(com.myfinance.core.Dates.localDate(it.timestamp, zone)) }
                    .toSortedMap(compareByDescending { it })
                    .map { (fy, list) ->
                        com.myfinance.core.TaxHelper.fyLabel(fy) to Labels.netByLabel(list, meta.labelsById).map { it.label to it.receivedMinor }
                    },
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, WhileShown, ReportsData())

    /** The family's own cards and accounts by number (Manage > My cards & accounts). */
    val myAccounts: StateFlow<List<com.myfinance.core.MyAccount>> =
        repo.myAccounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A short note after saving a card or account, e.g. how many past entries moved to that person. */
    val myAccountsMessage = MutableStateFlow<String?>(null)

    fun saveMyAccount(a: com.myfinance.core.MyAccount) = viewModelScope.launch {
        val n = runCatching { repo.saveMyAccount(a) }.getOrElse { e -> myAccountsMessage.value = "Could not save: ${e.message}"; return@launch }
        myAccountsMessage.value = "Saved ${a.label}." + if (n > 0) " $n past entries updated." else ""
    }

    fun deleteMyAccount(id: Long) = viewModelScope.launch { runCatching { repo.deleteMyAccount(id) } }

    /** Dashboard: the period (ending at the month shown) and the account or card it is filtered to. */
    val dashPeriod = MutableStateFlow(com.myfinance.core.DashPeriod.MONTH)
    val dashAccount = MutableStateFlow<String?>(null)

    val dashboard: StateFlow<com.myfinance.core.DashboardData> =
        combine(combine(allLite, txnMeta) { t, m -> t to m }, _month, monthCycle, dashPeriod, dashAccount) { (txns, meta), end, cycle, period, account ->
            com.myfinance.core.Dashboard.compute(txns, end, period, account, ZoneId.systemDefault(), cycle, meta.hiddenIds)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, WhileShown, com.myfinance.core.DashboardData())

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
    fun showMonth(m: YearMonth) { _month.value = minOf(m, currentMonth.value) }
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

    /** The printable report of [month]: every entry, splits, charts and insights. */
    private suspend fun monthReport(month: YearMonth): com.myfinance.core.MonthReport = withContext(Dispatchers.Default) {
        val all = allTransactions.value
        val me = ownerName.value
        val modes = mergedModes.value
        val meta = txnMeta.value
        com.myfinance.core.MonthReport.build(
            txns = allLite.value,
            period = month,
            cycle = monthCycle.value,
            zone = ZoneId.systemDefault(),
            notCounted = all.filter { it.excluded }.map { it.toLite(me, modes) },
            labelsById = meta.labelsById,
            hiddenIds = meta.hiddenIds,
            notesById = all.mapNotNull { e -> e.note?.takeIf { it.isNotBlank() }?.let { e.id to it } }.toMap(),
            cardTails = myAccounts.value.filter { it.isCard }.mapTo(HashSet()) { it.tail.takeLast(4) },
            budgets = categories.value.mapNotNull { c -> c.monthlyBudgetMinor?.let { c.name to it } }.toMap(),
            recurring = recurring.value,
            balanceAfter = all.mapNotNull { e -> e.balanceMinor?.let { e.id to it } }.toMap(),
        )
    }

    private fun monthName(month: YearMonth) = month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))

    /** File name for the month's report, e.g. "MyFinance-2026-11-Siva.pdf". */
    fun pdfFileName(month: YearMonth = _month.value): String =
        "MyFinance-$month-${ownerName.value.replace(Regex("[^A-Za-z0-9]+"), "")}.pdf".replace("-.pdf", ".pdf")

    /** Saves the selected month as a printable PDF report. */
    fun exportPdf(uri: Uri) = runSync {
        val app = getApplication<Application>()
        val month = _month.value
        val report = monthReport(month)
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.use { PdfStatement.write(it, report, ownerName.value) }
                ?: error("Could not open file for writing")
        }
        "Saved the ${monthName(month)} report as PDF."
    }

    /** Makes the selected month's PDF report and hands it to [onReady] to share (WhatsApp, Drive, print …). */
    fun sharePdf(onReady: (Uri) -> Unit) = runSync {
        val app = getApplication<Application>()
        val month = _month.value
        val report = monthReport(month)
        val uri = withContext(Dispatchers.IO) {
            val dir = java.io.File(app.cacheDir, "share").apply { mkdirs() }
            dir.listFiles { f -> f.name.endsWith(".pdf") }?.forEach { it.delete() }
            val file = java.io.File(dir, pdfFileName(month))
            file.outputStream().use { PdfStatement.write(it, report, ownerName.value) }
            androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
        }
        onReady(uri)
        "Made the ${monthName(month)} report."
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

    /** A part of a split payment opens the payment itself. */
    suspend fun load(id: Long): TransactionEntity? = repo.get(SplitView.originalId(id))

    private suspend fun stored(txn: TransactionEntity): TransactionEntity? =
        if (txn.id < 0) repo.get(SplitView.originalId(txn.id)) else txn

    fun save(txn: TransactionEntity, learnRule: Boolean) = viewModelScope.launch {
        if (txn.id >= 0) repo.save(txn, learnRule)
    }
    // ---- Delete, undo and the Recently deleted bin ----

    private val _justDeleted = MutableStateFlow<TransactionEntity?>(null)

    /** The transaction deleted a moment ago, for the "Undo" snackbar. */
    val justDeleted: StateFlow<TransactionEntity?> = _justDeleted.asStateFlow()

    fun delete(txn: TransactionEntity) = viewModelScope.launch {
        val t = stored(txn) ?: return@launch
        repo.delete(t)
        _justDeleted.value = t
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
        combine(autoPlans, allTransactions, recurring, openBills, combine(hiddenAuto, txnMeta) { h, m -> h to m }) { plans, all, rec, bills, (hidden, meta) ->
            val me = ownerName.value
            val debits = all.filter { !it.excluded && it.type == TxnType.DEBIT }.map { DebitRecord(it.toLite(me), it.rawSms, meta.labels(it)) }
            val keys = hidden.mapTo(HashSet()) { it.key }
            AutoDebits.build(
                plans = plans.map { it.toPlan() },
                debits = debits,
                recurring = rec,
                emiBills = bills.map { it.toBill() },
                hidden = emptySet(),
                today = LocalDate.now(),
                notRecurring = keys.filter { it.startsWith(AutoDebits.NOT_RECURRING_PREFIX) }.mapTo(HashSet()) { it.removePrefix(AutoDebits.NOT_RECURRING_PREFIX) },
            ) to keys
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, WhileShown, emptyList<AutoDebitItem>() to emptySet())

    /** Rows shown in the list; hidden ones are left out unless [showHiddenAutoDebits] is on. */
    val showHiddenAutoDebits = MutableStateFlow(false)

    val autoDebits: StateFlow<List<AutoDebitItem>> =
        combine(autoDebitsWithHidden, showHiddenAutoDebits) { (all, hidden), show -> if (show) all else all.filter { it.key !in hidden } }
            .stateIn(viewModelScope, WhileShown, emptyList())

    val hiddenAutoDebitKeys: StateFlow<Set<String>> =
        autoDebitsWithHidden.map { it.second }.stateIn(viewModelScope, WhileShown, emptySet())

    /** Totals include hidden rows: hiding only takes them off the list. */
    val autoDebitTotals: StateFlow<AutoDebitTotals> =
        autoDebitsWithHidden.map { AutoDebits.totals(it.first, LocalDate.now()) }
            .stateIn(viewModelScope, WhileShown, AutoDebitTotals(0, 0, 0))

    suspend fun autoPlan(id: String): AutoDebitPlan? =
        autoPlans.first().firstOrNull { it.id == id }?.toPlan()

    fun saveAutoPlan(p: AutoDebitPlan) = viewModelScope.launch { repo.v13.upsertPlan(AutoDebitEntity.from(p)) }
    fun deleteAutoPlan(id: String) = viewModelScope.launch { repo.v13.deletePlan(id) }
    fun hideAutoDebit(key: String) = viewModelScope.launch { repo.v13.hide(HiddenAutoDebitEntity(key)) }
    fun unhideAutoDebits() = viewModelScope.launch { repo.v13.unhideAll() }
    fun unhideAutoDebit(key: String) = viewModelScope.launch { repo.v13.unhide(key) }

    /** Takes a detected payee off the auto debit list and out of its totals, for good (undo under Settings > Hidden items). */
    fun markNotRecurring(item: AutoDebitItem) = viewModelScope.launch {
        AutoDebits.payeeKey(item)?.let { repo.v13.hide(HiddenAutoDebitEntity(AutoDebits.NOT_RECURRING_PREFIX + it)) }
    }

    /** Payees marked "Not recurring", as stored keys. */
    val notRecurringKeys: StateFlow<List<String>> =
        hiddenAuto.map { list -> list.map { it.key }.filter { it.startsWith(AutoDebits.NOT_RECURRING_PREFIX) }.sorted() }
            .stateIn(viewModelScope, WhileShown, emptyList())

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
        val t = stored(txn) ?: return@launch
        repo.setLabels(t, repo.labelsOf(t.uid), hidden, forMerchant = false)
    }

    /** Saved payee names: name -> the raw payee texts it replaces. */
    val payeeNames: StateFlow<List<Pair<String, List<String>>>> =
        repo.v13.observePayeeNames().map { list -> list.groupBy({ it.name }, { it.merchantKey }).toList() }
            .stateIn(viewModelScope, WhileShown, emptyList())

    fun renamePayeeName(old: String, new: String) = viewModelScope.launch { repo.renamePayeeName(old, new) }
    fun forgetPayeeName(name: String) = viewModelScope.launch { repo.forgetPayeeName(name) }

    /** Hidden auto debit / EMI rows with their names, for Settings > Hidden items. */
    val hiddenAutoDebitItems: StateFlow<List<AutoDebitItem>> =
        autoDebitsWithHidden.map { (all, hidden) -> all.filter { it.key in hidden } }
            .stateIn(viewModelScope, WhileShown, emptyList())

    /** Hidden transactions, newest first. */
    val hiddenTransactions: StateFlow<List<TransactionEntity>> =
        combine(allTransactions, txnMeta) { all, meta -> all.filter { it.uid in meta.hiddenUids } }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun unhideTxn(uid: String) = viewModelScope.launch { repo.v13.unhideTxn(uid) }
    fun unhideAllTxns() = viewModelScope.launch { hiddenTransactions.value.forEach { repo.v13.unhideTxn(it.uid) } }

    // ---- Income over the years and the spending explorer ----

    /** Income per calendar year (first) and per financial year (second). */
    val incomeYears: StateFlow<Pair<List<IncomeYear>, List<IncomeYear>>> =
        allLite.map { Insights.incomeByYear(it, financialYear = false) to Insights.incomeByYear(it, financialYear = true) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, WhileShown, emptyList<IncomeYear>() to emptyList())

    val salaryByMonth: StateFlow<List<Pair<YearMonth, Long>>> =
        allLite.map { Insights.salaryByMonth(it) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, WhileShown, emptyList())

    /** "Did you know" facts about the selected month: record months, jumps, top category, streaks. */
    val facts: StateFlow<List<Fact>> =
        combine(allLite, _month, txnMeta) { txns, m, meta -> Insights.facts(txns, meta.labelsById, m) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, WhileShown, emptyList())

    /** Payees by how often they appear, for the explorer's picker. */
    val payeeOptions: StateFlow<List<String>> =
        allTransactions.map { all -> all.mapNotNull { it.merchant }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, WhileShown, emptyList())

    /** Transactions (not left out of totals) matching one label, payee, category, account or person. */
    /**
     * [type] null = both. For a payee, money received from the same seller under a slightly different name
     * ("IRCTC REFUND" for "IRCTC") is included, so refunds net against what was spent.
     */
    fun explore(kind: ExploreKind, value: String, type: TxnType?): List<TransactionEntity> {
        val meta = txnMeta.value
        val me = ownerName.value
        return allTransactions.value.filter { t ->
            !t.excluded && (type == null || t.type == type) && when (kind) {
                ExploreKind.LABEL -> value in meta.labels(t)
                ExploreKind.PAYEE -> t.merchant == value || (t.type == TxnType.CREDIT && t.merchant != null && Labels.sameSeller(value, t.merchant))
                ExploreKind.CATEGORY -> t.category == value
                ExploreKind.ACCOUNT -> t.accountLabel == value
                ExploreKind.PERSON -> (t.owner ?: me) == value
            }
        }
    }

    fun renameLabel(from: String, to: String) = viewModelScope.launch { repo.renameLabel(from, to) }
    fun deleteLabel(label: String) = viewModelScope.launch { repo.deleteLabel(label) }

    fun addCategory(name: String, budgetMinor: Long?) = viewModelScope.launch { repo.addCategory(name, budgetMinor) }
    fun updateCategory(c: CategoryEntity) = viewModelScope.launch { repo.updateCategory(c) }
    fun deleteCategory(name: String) = viewModelScope.launch { repo.deleteCategory(name) }

    /** v1.4 insights: label suggestions and budgets, card bills, loans, salary, refunds, year in review and more. */
    val v14 = V14State(viewModelScope, repo, allTransactions, allLite, allLiteWithExcluded, txnMeta, ownerName, autoDebitsWithHidden, ::explore, monthTransactions)

    /** v1.5: Ask, tax-time pack, vault, subscriptions, calendar, goal links, lending, auto-backup, Today card. */
    val v15 = V15State(viewModelScope, getApplication(), repo, allTransactions, allLite, txnMeta, ownerName, autoDebits, recurring, openBills)

    /** v1.6: review inbox, family UPI, transfers, splits, monthly close, health check, rule tester, export, currencies, heavy spends. */
    val v16 = V16State(viewModelScope, getApplication(), repo, allTransactions, allLite, txnMeta, ownerName, autoDebits)

    /** 1.9: balances and card use, month compare, payment modes, fixed vs flexible, savings rate, family summary, meal card, FASTag. */
    val v19 = V19State(viewModelScope, repo, allTransactions, allLite, month, monthCycle, ownerName, balances, myAccounts, recurring, dashPeriod)
}
