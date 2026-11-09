package com.myfinance.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myfinance.app.MyFinanceApp
import com.myfinance.app.data.BillReminderEntity
import com.myfinance.app.data.CustomRuleEntity
import com.myfinance.app.data.GoalEntity
import com.myfinance.app.data.HoldingEntity
import com.myfinance.app.data.LiabilityEntity
import com.myfinance.app.data.ReceiptEntity
import com.myfinance.app.data.SettlementEntity
import com.myfinance.app.data.SplitEntity
import com.myfinance.app.data.TaxTagEntity
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.paymentMode
import com.myfinance.app.security.AppLock
import com.myfinance.core.AssetKind
import com.myfinance.core.Balances
import com.myfinance.core.BillReminders
import com.myfinance.core.BillStatus
import com.myfinance.core.CashEntry
import com.myfinance.core.CashWallet
import com.myfinance.core.CashWalletSummary
import com.myfinance.core.CustomRule
import com.myfinance.core.CustomRules
import com.myfinance.core.DefaultCategories
import com.myfinance.core.GoalProgress
import com.myfinance.core.Goals
import com.myfinance.core.Holding
import com.myfinance.core.NetWorth
import com.myfinance.core.NetWorthSummary
import com.myfinance.core.NotABackupException
import com.myfinance.core.PaymentMode
import com.myfinance.core.RuleInput
import com.myfinance.core.SectionTotal
import com.myfinance.core.Splits
import com.myfinance.core.TaxHelper
import com.myfinance.core.TaxItem
import com.myfinance.core.TaxSection
import com.myfinance.core.Transfer
import com.myfinance.core.TxnType
import com.myfinance.core.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** A holding as shown on the net worth screen; [overrideKey] is set for holdings built from SMS. */
data class HoldingRow(val holding: Holding, val manualId: String?, val overrideKey: String?)

data class NetWorthData(
    val summary: NetWorthSummary = NetWorth.of(emptyList(), emptyList()),
    val rows: List<HoldingRow> = emptyList(),
    val liabilities: List<LiabilityEntity> = emptyList(),
    val bankMinor: Long = 0,
    val cashMinor: Long = 0,
)

data class SplitData(
    val splits: List<SplitEntity> = emptyList(),
    val settlements: List<SettlementEntity> = emptyList(),
    val balances: Map<String, Long> = emptyMap(),
    val plan: List<Transfer> = emptyList(),
)

data class TaxRow(val txn: TransactionEntity, val section: TaxSection, val suggested: Boolean)

data class TaxData(
    val fyStart: Int = TaxHelper.fyStartYear(LocalDate.now()),
    val totals: List<SectionTotal> = emptyList(),
    val tagged: List<TaxRow> = emptyList(),
    val suggestions: List<TaxRow> = emptyList(),
)

data class CashData(val summary: CashWalletSummary = CashWallet.summary(emptyList()), val recent: List<TransactionEntity> = emptyList())

class V12ViewModel(app: Application) : AndroidViewModel(app) {
    private val main = (app as MyFinanceApp).repository
    private val repo = main.v12

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    val ownerName: StateFlow<String> = main.ownerName

    private val allTxns: StateFlow<List<TransactionEntity>> =
        main.allTransactions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Goals ----

    val goals: StateFlow<List<GoalProgress>> = repo.goals
        .map { list -> val today = LocalDate.now(); list.map { Goals.progress(it.toGoal(), today) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveGoal(id: String?, name: String, targetMinor: Long, deadline: LocalDate?, savedMinor: Long) = launch {
        val existing = id?.let { gid -> goalEntities().firstOrNull { it.id == gid } }
        repo.dao.upsertGoal(
            existing?.copy(name = name, targetMinor = targetMinor, deadlineEpochDay = deadline?.toEpochDay(), savedMinor = savedMinor)
                ?: GoalEntity(name = name, targetMinor = targetMinor, deadlineEpochDay = deadline?.toEpochDay(), savedMinor = savedMinor),
        )
    }

    fun addToGoal(id: String, deltaMinor: Long) = launch {
        goalEntities().firstOrNull { it.id == id }?.let { repo.dao.upsertGoal(it.copy(savedMinor = (it.savedMinor + deltaMinor).coerceAtLeast(0))) }
    }

    fun deleteGoal(id: String) = launch { repo.dao.deleteGoal(id) }

    private val goalList = repo.goals.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private fun goalEntities() = goalList.value

    // ---- Cash wallet ----

    val cash: StateFlow<CashData> = allTxns.map { list ->
        val relevant = list.filter { t ->
            t.type == TxnType.DEBIT && (CashWallet.isAtmWithdrawal(t.category, t.rawSms) || t.paymentMode() == PaymentMode.CASH)
        }
        val entries = relevant.map { t ->
            CashEntry(t.amountMinor, CashWallet.isAtmWithdrawal(t.category, t.rawSms), t.category, t.note, t.timestamp)
        }
        CashData(CashWallet.summary(entries), relevant.sortedByDescending { it.timestamp }.take(50))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, CashData())

    /** Logs cash spent (it counts in totals like any other expense). */
    fun addCashSpend(amountMinor: Long, category: String, note: String?) = launch {
        main.save(
            TransactionEntity(
                amountMinor = amountMinor, type = TxnType.DEBIT, merchant = note, category = category,
                accountTail = null, bank = null, timestamp = System.currentTimeMillis(),
                source = com.myfinance.app.data.TxnSource.MANUAL, note = "Cash",
            ),
            learnRule = false,
        )
    }

    // ---- Net worth ----

    val netWorth: StateFlow<NetWorthData> = combine(
        combine(repo.holdings, repo.events, repo.overrides) { h, e, o -> Triple(h, e, o) },
        repo.liabilities,
        main.balances,
        cash,
    ) { (manual, events, overrides), liabilities, balances, cashData ->
        val fromSms = NetWorth.applyEvents(emptyList(), events.map { it.toEvent() })
        val ov = overrides.associate { it.key to it.currentMinor }
        val smsRows = fromSms.map { h ->
            val key = "${h.kind.name}|${h.name.lowercase()}"
            HoldingRow(ov[key]?.let { h.copy(currentMinor = it) } ?: h, null, key)
        }
        val manualRows = manual.map { HoldingRow(it.toHolding(), it.id, null) }
        val bank = Balances.totalBankMinor(balances)
        val cashInHand = cashData.summary.inHandMinor
        val all = (smsRows + manualRows).map { it.holding } +
            listOfNotNull(
                Holding("Bank accounts", AssetKind.BANK, bank).takeIf { bank != 0L },
                Holding("Cash in hand", AssetKind.CASH, cashInHand).takeIf { cashInHand > 0 },
            )
        NetWorthData(NetWorth.of(all, liabilities.map { it.toLiability() }), smsRows + manualRows, liabilities, bank, cashInHand)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, NetWorthData())

    fun saveHolding(id: String?, name: String, kind: AssetKind, investedMinor: Long, currentMinor: Long) = launch {
        repo.dao.upsertHolding(HoldingEntity(id ?: java.util.UUID.randomUUID().toString(), name, kind.name, investedMinor, currentMinor))
    }

    fun deleteHolding(row: HoldingRow) = launch {
        row.manualId?.let { repo.dao.deleteHolding(it) }
        row.overrideKey?.let { key ->
            repo.dao.deleteOverride(key)
            repo.dao.deleteEvents(row.holding.kind.name, row.holding.name)
        }
    }

    fun setCurrentValue(row: HoldingRow, currentMinor: Long) = launch {
        if (row.manualId != null) {
            repo.dao.upsertHolding(HoldingEntity(row.manualId, row.holding.name, row.holding.kind.name, row.holding.investedMinor, currentMinor))
        } else if (row.overrideKey != null) {
            repo.dao.upsertOverride(com.myfinance.app.data.ValueOverrideEntity(row.overrideKey, currentMinor, System.currentTimeMillis()))
        }
    }

    fun saveLiability(id: String?, name: String, kind: com.myfinance.core.LiabilityKind, outstandingMinor: Long) = launch {
        repo.dao.upsertLiability(LiabilityEntity(id ?: java.util.UUID.randomUUID().toString(), name, kind.name, outstandingMinor))
    }

    fun deleteLiability(id: String) = launch { repo.dao.deleteLiability(id) }

    // ---- Bill reminders ----

    val bills: StateFlow<List<Pair<BillReminderEntity, BillStatus>>> = repo.bills
        .map { list -> val today = LocalDate.now(); list.map { it to BillReminders.status(it.toBill(), today) }.sortedBy { it.second.daysLeft } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveBill(b: BillReminderEntity) = launch { repo.dao.upsertBill(b) }
    fun markBillPaid(b: BillReminderEntity) = launch { repo.markBillPaid(b) }
    fun deleteBill(id: String) = launch { repo.dao.deleteBill(id) }

    // ---- Split & settle ----

    val splits: StateFlow<SplitData> = combine(repo.splits, repo.settlements) { s, st ->
        val bal = Splits.balances(s.map { it.toSplit() }, st.map { it.toSettlement() })
        SplitData(s, st, bal, Splits.settleUp(bal))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SplitData())

    /** Names to offer when splitting: this phone's owner, family members seen in merged data, and past split partners. */
    val people: StateFlow<List<String>> = combine(allTxns, repo.splits, main.ownerName) { txns, s, me ->
        (listOf(me) + txns.mapNotNull { it.owner } + s.flatMap { it.toSplit().shares.keys + it.paidBy })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun splitFor(txnUid: String): SplitEntity? = repo.dao.splitFor(txnUid)

    fun saveSplit(s: SplitEntity) = launch { repo.dao.upsertSplit(s) }
    fun deleteSplit(id: String) = launch { repo.dao.deleteSplit(id) }
    fun settle(from: String, to: String, amountMinor: Long) =
        launch { repo.dao.insertSettlement(SettlementEntity(fromPerson = from, toPerson = to, amountMinor = amountMinor, timestamp = System.currentTimeMillis())) }
    fun deleteSettlement(id: String) = launch { repo.dao.deleteSettlement(id) }

    // ---- Receipts ----

    fun receipts(txnUid: String): Flow<List<ReceiptEntity>> = repo.receipts(txnUid)
    val receiptUids: StateFlow<Set<String>> = repo.receiptUids.map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    fun receiptFile(r: ReceiptEntity): File = repo.receiptFile(r)
    fun newReceiptFile(): File = repo.newReceiptFile()
    fun addReceiptFromCamera(txnUid: String, file: File) = launch { repo.addReceiptFromCamera(txnUid, file) }
    fun addReceiptFromUri(txnUid: String, uri: Uri) = launch { repo.addReceiptFromUri(txnUid, uri) }
    fun deleteReceipt(r: ReceiptEntity) = launch { repo.deleteReceipt(r) }

    // ---- Tax helper ----

    val taxYear = MutableStateFlow(TaxHelper.fyStartYear(LocalDate.now()))

    val tax: StateFlow<TaxData> = combine(allTxns, repo.taxTags, taxYear) { txns, tags, fy ->
        val byUid = tags.associateBy { it.txnUid }
        val range = TaxHelper.fyRange(fy)
        val inYear = txns.filter { it.timestamp in range && it.type == TxnType.DEBIT }
        val tagged = inYear.mapNotNull { t -> byUid[t.uid]?.taxSection?.let { TaxRow(t, it, false) } }
        val suggestions = inYear.filter { it.uid !in byUid }.mapNotNull { t ->
            TaxHelper.suggest(t.merchant, t.category, t.note, t.rawSms)?.let { TaxRow(t, it, true) }
        }
        val totals = TaxHelper.yearTotals(tagged.map { TaxItem(it.section, it.txn.amountMinor, it.txn.timestamp, it.txn.merchant) }, fy)
        TaxData(fy, totals, tagged.sortedByDescending { it.txn.timestamp }, suggestions.sortedByDescending { it.txn.timestamp })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, TaxData())

    suspend fun taxTagFor(uid: String): TaxTagEntity? = repo.dao.taxTag(uid)

    /** [section] null removes the tag; "not tax-saving" is stored so the suggestion stops showing. */
    fun setTax(uid: String, section: TaxSection?, notTaxSaving: Boolean = false) = launch {
        when {
            section != null -> repo.dao.upsertTaxTag(TaxTagEntity(uid, section.name))
            notTaxSaving -> repo.dao.upsertTaxTag(TaxTagEntity(uid, "NONE"))
            else -> repo.dao.deleteTaxTag(uid)
        }
    }

    // ---- Custom rules ----

    val rules: StateFlow<List<CustomRuleEntity>> = repo.rules.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveRule(rule: CustomRule) = launch {
        repo.dao.upsertRule(CustomRuleEntity.from(rule))
        repo.invalidateRules()
    }

    fun deleteRule(id: String) = launch {
        repo.dao.deleteRule(id)
        repo.invalidateRules()
    }

    /** How many stored SMS transactions [rule] would match, as a live preview while editing. */
    fun previewCount(rule: CustomRule): Int = allTxns.value.count { t ->
        t.source != com.myfinance.app.data.TxnSource.MANUAL &&
            CustomRules.matches(rule, RuleInput(null, t.rawSms, t.merchant, t.type, t.amountMinor))
    }

    fun applyRulesNow() = launch {
        val n = main.applyRulesToExisting()
        _message.value = "Rules applied: $n transactions updated."
    }

    val categoryNames: StateFlow<List<String>> = main.categories.map { list -> list.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultCategories.all)

    // ---- App lock ----

    val lockEnabled: StateFlow<Boolean> = AppLock.enabled
    val biometricEnabled: StateFlow<Boolean> = AppLock.biometric

    fun enableLock(pin: String) = AppLock.setPin(getApplication<Application>(), pin)
    fun disableLock() = AppLock.disable(getApplication<Application>())
    fun setBiometric(on: Boolean) = AppLock.setBiometric(getApplication<Application>(), on)

    // ---- Backup ----

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _restoreReady = MutableStateFlow(false)
    /** True once a restore is staged; the app must restart to apply it. */
    val restoreReady: StateFlow<Boolean> = _restoreReady.asStateFlow()

    val lastBackup: Long? get() = repo.lastBackup

    fun backup(uri: Uri, password: String) = work {
        val n = repo.backupTo(uri, password.toCharArray())
        "Backup saved ($n files), encrypted with your password. Keep the password safe: without it the backup cannot be opened."
    }

    fun restore(uri: Uri, password: String) = work {
        try {
            repo.stageRestore(uri, password.toCharArray())
            _restoreReady.value = true
            "Backup unlocked. Restart the app to finish restoring."
        } catch (e: WrongPasswordException) {
            "Wrong password, or the file is damaged."
        } catch (e: NotABackupException) {
            "That file is not a MyFinance backup."
        }
    }

    private fun work(block: suspend () -> String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _message.value = try { block() } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
            _busy.value = false
        }
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { _message.value = "Failed: ${e.message ?: e.javaClass.simpleName}" }
    }
}
