package com.myfinance.app.ui

import android.content.Context
import android.net.Uri
import com.myfinance.app.backup.AutoBackup
import com.myfinance.app.data.BillReminderEntity
import com.myfinance.app.data.CapitalGainEntity
import com.myfinance.app.data.FinanceRepository
import com.myfinance.app.data.GoalLinkEntity
import com.myfinance.app.data.GoalReturnEntity
import com.myfinance.app.data.HoldingEntity
import com.myfinance.app.data.InvestmentEventEntity
import com.myfinance.app.data.LendEntryEntity
import com.myfinance.app.data.LendRepaymentEntity
import com.myfinance.app.data.PolicyEntity
import com.myfinance.app.data.SubscriptionMarkEntity
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.ValueOverrideEntity
import com.myfinance.app.data.WarrantyEntity
import com.myfinance.app.data.toLite
import com.myfinance.core.Ask
import com.myfinance.core.AskAnswer
import com.myfinance.core.AssetKind
import com.myfinance.core.AutoDebitItem
import com.myfinance.core.BackupFrequency
import com.myfinance.core.CashFlowCalendar
import com.myfinance.core.CashFlowMonth
import com.myfinance.core.DebitRecord
import com.myfinance.core.FlowEvent
import com.myfinance.core.GoalForecast
import com.myfinance.core.GoalLinks
import com.myfinance.core.GoalMoney
import com.myfinance.core.LendDirection
import com.myfinance.core.LendStatus
import com.myfinance.core.Lending
import com.myfinance.core.LendingSummary
import com.myfinance.core.NetWorth
import com.myfinance.core.Policy
import com.myfinance.core.RecurringPayment
import com.myfinance.core.ReminderKind
import com.myfinance.core.SubscriptionAudit
import com.myfinance.core.SubscriptionAuditResult
import com.myfinance.core.TaxHelper
import com.myfinance.core.TaxItem
import com.myfinance.core.TaxPack
import com.myfinance.core.TaxPackData
import com.myfinance.core.TxnLite
import com.myfinance.core.TxnType
import com.myfinance.core.Vault
import com.myfinance.core.VaultStatus
import com.myfinance.core.Warranty
import com.myfinance.core.XlsxBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class PolicyRow(val policy: Policy, val status: VaultStatus)
data class WarrantyRow(val warranty: Warranty, val status: VaultStatus)
data class LendingData(val statuses: List<LendStatus> = emptyList(), val summary: LendingSummary = Lending.summary(emptyList()))

/** Today's spend and what is due over the next seven days. */
data class TodayData(
    val spentMinor: Long = 0,
    val receivedMinor: Long = 0,
    val payments: Int = 0,
    val upcoming: List<FlowEvent> = emptyList(),
    val upcomingOutMinor: Long = 0,
)

/**
 * The v1.5 features: plain-words questions, tax-time pack, insurance and warranty vault, subscription
 * audit, cash flow calendar, goal-linked savings, lending, auto-backup and the Today card.
 * Built from the flows [FinanceViewModel] already has, so nothing is loaded twice.
 */
class V15State(
    private val scope: CoroutineScope,
    private val context: Context,
    private val repo: FinanceRepository,
    private val allTransactions: StateFlow<List<TransactionEntity>>,
    /** Transactions that count in totals. */
    private val allLite: StateFlow<List<TxnLite>>,
    private val meta: StateFlow<TxnMeta>,
    private val ownerName: StateFlow<String>,
    private val autoDebits: StateFlow<List<AutoDebitItem>>,
    private val recurring: StateFlow<List<RecurringPayment>>,
) {
    private val dao = repo.v15
    private val v12 = repo.v12
    private val zone get() = ZoneId.systemDefault()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }
    private fun say(text: String) { _message.value = text }

    private fun work(block: suspend () -> String?) = scope.launch {
        try {
            block()?.let(::say)
        } catch (e: Exception) {
            say(e.message ?: "Something went wrong")
        }
    }

    // ---- Ask in plain words ----

    private val _answer = MutableStateFlow<AskAnswer?>(null)
    val answer: StateFlow<AskAnswer?> = _answer.asStateFlow()

    fun ask(question: String) = scope.launch {
        if (question.isBlank()) { _answer.value = null; return@launch }
        _answer.value = withContext(Dispatchers.Default) {
            val txns = allLite.value
            Ask.ask(question, LocalDate.now(), txns, meta.value.labelsById, txns.mapNotNull { it.owner }.distinct(), zone)
        }
    }

    // ---- Tax-time pack ----

    val taxYear = MutableStateFlow(TaxHelper.fyStartYear(LocalDate.now()))

    val gains: StateFlow<List<CapitalGainEntity>> = dao.observeGains().stateIn(scope, SharingStarted.Lazily, emptyList())

    val taxPack: StateFlow<TaxPackData?> =
        combine(allTransactions, allLite, v12.taxTags, gains, taxYear) { all, lite, tags, g, fy ->
            val byUid = all.associateBy { it.uid }
            val items = tags.mapNotNull { tag ->
                val t = byUid[tag.txnUid] ?: return@mapNotNull null
                val s = tag.taxSection ?: return@mapNotNull null
                if (t.excluded) null else TaxItem(s, t.amountMinor, t.timestamp, t.merchant)
            }
            val text = all.associate { it.id to (listOfNotNull(it.rawSms, it.note).joinToString(" ").ifBlank { null }) }
            TaxPack.build(fy, lite, text, items, g.map { it.toEntry() }, zone)
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Lazily, null)

    fun saveGain(g: CapitalGainEntity) = work { dao.putGain(g); "Sale saved" }
    fun deleteGain(id: String) = work { dao.deleteGain(id); null }

    fun exportTaxPack(uri: Uri) = work {
        val p = taxPack.value ?: return@work "Still working out the numbers, try again in a moment"
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { XlsxBook.write(it, TaxPack.sheets(p, zone)) }
                ?: error("Could not open the file")
        }
        "Tax-time pack saved as Excel"
    }

    // ---- Vault ----

    val policies: StateFlow<List<PolicyRow>> = dao.observePolicies().map { list ->
        val today = LocalDate.now()
        list.map { it.toPolicy() }.map { PolicyRow(it, Vault.policyStatus(it, today)) }
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    val warranties: StateFlow<List<WarrantyRow>> = dao.observeWarranties().map { list ->
        val today = LocalDate.now()
        list.map { it.toWarranty() }.map { WarrantyRow(it, Vault.warrantyStatus(it, today)) }.sortedBy { it.warranty.endDate }
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    fun savePolicy(p: Policy) = work { dao.putPolicy(PolicyEntity.from(p)); "Policy saved" }
    fun deletePolicy(id: String) = work { dao.deletePolicy(id); null }
    fun renewPolicy(p: Policy) = work {
        val n = Vault.renewed(p)
        dao.putPolicy(PolicyEntity.from(n))
        n.renewalDate?.let { "Next renewal ${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.year}" } ?: "Marked renewed"
    }
    fun saveWarranty(w: Warranty) = work { dao.putWarranty(WarrantyEntity.from(w)); "Warranty saved" }
    fun deleteWarranty(id: String) = work { dao.deleteWarranty(id); null }

    // ---- Subscription audit ----

    private val marks = dao.observeMarks()

    val subscriptions: StateFlow<SubscriptionAuditResult?> =
        combine(recurring, allTransactions, marks) { rec, all, m ->
            val me = ownerName.value
            val debits = all.filter { !it.excluded && it.type == TxnType.DEBIT }.map { DebitRecord(it.toLite(me), it.rawSms) }
            SubscriptionAudit.audit(
                rec, debits,
                notUsed = m.filter { it.mark == "NOT_USED" }.mapTo(HashSet()) { it.key },
                ignored = m.filter { it.mark == "IGNORE" }.mapTo(HashSet()) { it.key },
                today = LocalDate.now(), zone = zone,
            )
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Lazily, null)

    fun markSubscription(key: String, notUsed: Boolean) = work {
        if (notUsed) dao.putMark(SubscriptionMarkEntity(key, "NOT_USED")) else dao.deleteMark(key)
        null
    }
    fun ignoreSubscription(key: String) = work { dao.putMark(SubscriptionMarkEntity(key, "IGNORE")); "Removed from the list" }

    // ---- Lending ----

    val lending: StateFlow<LendingData> = combine(dao.observeLending(), dao.observeRepayments()) { entries, reps ->
        val today = LocalDate.now()
        val r = reps.map { it.toRepayment() }
        val statuses = entries.map { Lending.status(it.toEntry(), r, today) }
            .sortedWith(compareBy<LendStatus> { it.state == com.myfinance.core.LendState.SETTLED }.thenBy { it.entry.dueDate ?: LocalDate.MAX })
        LendingData(statuses, Lending.summary(statuses))
    }.stateIn(scope, SharingStarted.Lazily, LendingData())

    fun saveLend(e: LendEntryEntity) = work { dao.putLend(e); "Saved" }
    fun deleteLend(id: String) = work { dao.deleteRepaymentsOf(id); dao.deleteLend(id); null }
    fun addRepayment(entryId: String, minor: Long, date: LocalDate) = work {
        dao.putRepayment(LendRepaymentEntity(entryId = entryId, amountMinor = minor, epochDay = date.toEpochDay()))
        "Repayment added"
    }
    fun settle(s: LendStatus) = work { dao.putLend(LendEntryEntity.from(s.entry.copy(closed = true))); "Marked settled" }
    fun reopen(s: LendStatus) = work { dao.putLend(LendEntryEntity.from(s.entry.copy(closed = false))); null }
    fun reminderText(s: LendStatus): String = Lending.reminderMessage(s, ownerName.value)

    /** People named in splits, lending and family rows, for name suggestions. */
    val people: StateFlow<List<String>> = dao.observeLending().map { l -> l.map { it.person.trim() }.distinct().sorted() }
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    // ---- Goal-linked savings ----

    val goalLinks: StateFlow<List<GoalLinkEntity>> = dao.observeGoalLinks().stateIn(scope, SharingStarted.Lazily, emptyList())
    private val goalReturns = dao.observeGoalReturns()

    /** Names to link: SMS investments and Net worth holdings. */
    val linkChoices: StateFlow<Pair<List<String>, List<String>>> = combine(v12.events, v12.holdings) { e, h ->
        e.map { it.name }.distinct().sorted() to h.map { it.name }.distinct().sorted()
    }.stateIn(scope, SharingStarted.Lazily, emptyList<String>() to emptyList())

    val forecasts: StateFlow<Map<String, GoalForecast>> = combine(
        combine(v12.goals, goalLinks, goalReturns) { g, l, r -> Triple(g, l, r) },
        combine(v12.events, v12.holdings, v12.overrides) { e, h, o -> Triple(e, h, o) },
        allTransactions,
        meta,
    ) { (goals, links, returns), (events, holdings, overrides), all, m ->
        val today = LocalDate.now()
        val evs = events.map { it.toEvent() }
        val money = GoalLinks.fromEvents(evs, zone)
        val ov = overrides.associate { it.key to it.currentMinor }
        val fromSms = NetWorth.applyEvents(emptyList(), evs).associate { h -> h.name to (ov["${h.kind.name}|${h.name.lowercase()}"] ?: h.currentMinor) }
        val holdingValues = fromSms + holdings.associate { it.name to it.currentMinor }
        val labelled = HashMap<String, MutableList<GoalMoney>>()
        for (t in all) if (!t.excluded) for (l in m.labels(t)) {
            labelled.getOrPut(l) { ArrayList() } += GoalMoney(l, t.amountMinor, com.myfinance.core.Dates.localDate(t.timestamp, zone), redemption = t.type == TxnType.CREDIT)
        }
        val rate = returns.associate { it.goalId to it.annualPct }
        goals.associate { g ->
            val linked = links.filter { it.goalId == g.id }.map { GoalLinks.linkedAmount(it.toLink(), money, holdingValues, labelled, today) }
            g.id to GoalLinks.forecast(g.toGoal(), linked, today, rate[g.id] ?: 0.0)
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Lazily, emptyMap())

    val goalReturnRates: StateFlow<Map<String, Double>> = goalReturns.map { l -> l.associate { it.goalId to it.annualPct } }
        .stateIn(scope, SharingStarted.Lazily, emptyMap())

    fun addGoalLink(l: GoalLinkEntity) = work { dao.putGoalLink(l); "Linked" }
    fun deleteGoalLink(id: String) = work { dao.deleteGoalLink(id); null }
    fun setGoalReturn(goalId: String, pct: Double) = work { dao.putGoalReturn(GoalReturnEntity(goalId, pct)); null }

    // ---- Cash flow calendar ----

    val calendarMonth = MutableStateFlow(YearMonth.now())

    private data class Expected(
        val auto: List<AutoDebitItem>,
        val bills: List<com.myfinance.core.BillReminder>,
        val policies: List<Policy>,
        val lending: List<LendStatus>,
        val subs: List<com.myfinance.core.SubscriptionItem>,
    )

    private val expected = combine(autoDebits, v12.bills, policies, lending, subscriptions) { a, b, p, l, s ->
        Expected(a, b.map { it.toBill() }, p.map { it.policy }, l.statuses, s?.items.orEmpty().filter { !it.notUsed })
    }

    private fun month(ym: YearMonth, txns: List<TxnLite>, e: Expected) = CashFlowCalendar.build(
        ym, LocalDate.now(), txns, e.auto, e.bills, e.policies, e.lending, e.subs, zone,
    )

    val calendar: StateFlow<CashFlowMonth?> = combine(calendarMonth, allLite, expected) { ym, txns, e -> month(ym, txns, e) }
        .flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Lazily, null)

    // ---- Today card ----

    val today: StateFlow<TodayData> = combine(allLite, expected) { txns, e ->
        val now = LocalDate.now()
        val ym = YearMonth.from(now)
        val end = now.plusDays(6)
        val months = listOfNotNull(ym, YearMonth.from(end).takeIf { it != ym })
        val days = months.flatMap { month(it, txns, e).days }
        val todays = txns.filter { com.myfinance.core.Dates.localDate(it.timestamp, zone) == now }
        val upcoming = days.filter { !it.date.isBefore(now) && !it.date.isAfter(end) }
            .flatMap { d -> d.events.filter { it.expected } }
            .sortedBy { it.date }
        TodayData(
            spentMinor = todays.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor },
            receivedMinor = todays.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
            payments = todays.count { it.type == TxnType.DEBIT },
            upcoming = upcoming,
            upcomingOutMinor = upcoming.filter { !it.incoming }.sumOf { it.amountMinor ?: 0 },
        )
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Lazily, TodayData())

    // ---- Quick adds: payment due, past investment, property, EPF ----

    /** A payment due on [date]. [everyMonths] 0 = only once. */
    fun addPaymentDue(name: String, amountMinor: Long?, date: LocalDate, everyMonths: Int, kind: ReminderKind) = work {
        v12.dao.upsertBill(
            BillReminderEntity(
                name = name.trim(), kind = kind.name, amountMinor = amountMinor, nextDueEpochDay = date.toEpochDay(),
                everyMonths = everyMonths, remindDaysBefore = 2, matchWords = "",
            ),
        )
        "Payment due on ${date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))} added"
    }

    /** An investment made in the past (e.g. an FD in 2019), so Net worth and goals include it. */
    fun addPastInvestment(name: String, kind: AssetKind, date: LocalDate, amountMinor: Long, currentMinor: Long?) = work {
        val ts = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        v12.dao.insertEvent(
            InvestmentEventEntity(
                smsKey = "manual:" + java.util.UUID.randomUUID(), kind = kind.name, name = name.trim(),
                amountMinor = amountMinor, redemption = false, units = null, currentValueMinor = null, timestamp = ts,
            ),
        )
        currentMinor?.let { v12.dao.upsertOverride(ValueOverrideEntity("${kind.name}|${name.trim().lowercase()}", it, System.currentTimeMillis())) }
        "Investment added to Net worth"
    }

    fun addProperty(name: String, boughtForMinor: Long, valueMinor: Long) = work {
        v12.dao.upsertHolding(HoldingEntity(name = name.trim(), kind = AssetKind.PROPERTY.name, investedMinor = boughtForMinor, currentMinor = valueMinor))
        "Property added to Net worth"
    }

    /**
     * EPF balance as of today. Stored like an EPFO SMS, so later EPFO passbook messages keep it up to date.
     * [contributionsMinor] is what you and your employer put in, when known; otherwise the balance.
     */
    fun setEpf(balanceMinor: Long, contributionsMinor: Long?) = work {
        v12.dao.deleteEvents(AssetKind.EPF.name, "EPF")
        v12.dao.insertEvent(
            InvestmentEventEntity(
                smsKey = "manual:epf:" + System.currentTimeMillis(), kind = AssetKind.EPF.name, name = "EPF",
                amountMinor = contributionsMinor ?: balanceMinor, redemption = false, units = null,
                currentValueMinor = balanceMinor, timestamp = System.currentTimeMillis(),
            ),
        )
        v12.dao.deleteOverride("${AssetKind.EPF.name}|epf")
        "EPF balance saved"
    }

    // ---- Auto-backup ----

    val autoBackup = AutoBackup.state

    fun enableAutoBackup(uri: Uri, password: String, frequency: BackupFrequency) = work {
        AutoBackup.enable(context, uri, password.toCharArray(), frequency)
        AutoBackup.runIfDue(context, force = true) ?: "Auto-backup is on"
    }
    fun setAutoBackupFrequency(f: BackupFrequency) = AutoBackup.setFrequency(context, f)
    fun disableAutoBackup() = work { AutoBackup.disable(context); "Auto-backup is off" }
    fun backupNow() = work { AutoBackup.runIfDue(context, force = true) ?: "Turn auto-backup on first" }

    // ---- Lending direction helper for screens ----
    fun newLend(person: String, lent: Boolean, minor: Long, date: LocalDate, due: LocalDate?, note: String?, id: String? = null) =
        saveLend(
            LendEntryEntity(
                id = id ?: java.util.UUID.randomUUID().toString(), person = person.trim(),
                direction = if (lent) LendDirection.LENT.name else LendDirection.BORROWED.name, amountMinor = minor,
                epochDay = date.toEpochDay(), dueEpochDay = due?.toEpochDay(), note = note?.trim()?.ifEmpty { null },
            ),
        )
}
