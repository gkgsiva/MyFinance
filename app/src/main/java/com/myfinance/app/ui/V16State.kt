package com.myfinance.app.ui

import android.content.Context
import android.net.Uri
import com.myfinance.app.data.ClosedMonthEntity
import com.myfinance.app.data.FamilyLimitEntity
import com.myfinance.app.data.FamilyPersonEntity
import com.myfinance.app.data.FinanceRepository
import com.myfinance.app.data.ReviewAnswerEntity
import com.myfinance.app.data.SplitPartEntity
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.TxnSource
import com.myfinance.app.data.accountLabel
import com.myfinance.app.data.toLite
import com.myfinance.core.AutoDebitItem
import com.myfinance.core.AutoDebits
import com.myfinance.core.CheckRow
import com.myfinance.core.CustomRule
import com.myfinance.core.DataHealth
import com.myfinance.core.DebitRecord
import com.myfinance.core.DuplicateGroup
import com.myfinance.core.ExportRow
import com.myfinance.core.FamilyPayment
import com.myfinance.core.FamilyPersonMonth
import com.myfinance.core.FamilyUpi
import com.myfinance.core.ForeignNote
import com.myfinance.core.FullExport
import com.myfinance.core.Fx
import com.myfinance.core.HealthReport
import com.myfinance.core.HeavySort
import com.myfinance.core.HeavySpendResult
import com.myfinance.core.HeavySpends
import com.myfinance.core.MonthClose
import com.myfinance.core.MonthCloseSummary
import com.myfinance.core.PaymentSplit
import com.myfinance.core.ReviewInbox
import com.myfinance.core.ReviewItem
import com.myfinance.core.RuleTestResult
import com.myfinance.core.RuleTestRow
import com.myfinance.core.RuleTester
import com.myfinance.core.SelfTransfers
import com.myfinance.core.SplitPart
import com.myfinance.core.TransferMatch
import com.myfinance.core.TxnLite
import com.myfinance.core.TxnType
import com.myfinance.core.XlsxBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class FamilyData(
    val month: YearMonth = YearMonth.now(),
    val people: List<FamilyPersonMonth> = emptyList(),
    val payments: List<FamilyPayment> = emptyList(),
    val history: List<Pair<YearMonth, Map<String, Long>>> = emptyList(),
)

data class MonthCloseData(
    val closed: List<YearMonth> = emptyList(),
    val open: List<MonthCloseSummary> = emptyList(),
)

data class ForeignRow(val txn: TransactionEntity, val foreign: ForeignNote)

/**
 * The v1.6 features: review inbox, family UPI view, transfers between own accounts, split payments, monthly
 * close, data health check, rule tester, full export, foreign currency and heavy spends.
 */
class V16State(
    private val scope: CoroutineScope,
    private val context: Context,
    private val repo: FinanceRepository,
    /** With split payments shown as parts. */
    private val allTransactions: StateFlow<List<TransactionEntity>>,
    private val allLite: StateFlow<List<TxnLite>>,
    private val meta: StateFlow<TxnMeta>,
    private val ownerName: StateFlow<String>,
    private val autoDebits: StateFlow<List<AutoDebitItem>>,
) {
    private val dao = repo.v16
    private val zone get() = ZoneId.systemDefault()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private fun work(block: suspend () -> String?) = scope.launch {
        try {
            block()?.let { _message.value = it }
        } catch (e: Exception) {
            _message.value = e.message ?: "Something went wrong"
        }
    }

    /** The stored rows, one per payment (split payments not broken up). */
    private val stored: StateFlow<List<TransactionEntity>> =
        repo.allTransactions().stateIn(scope, SharingStarted.Lazily, emptyList())

    private val answers: StateFlow<Map<String, String>> =
        dao.observeAnswers().map { l -> l.associate { it.key to it.answer } }.stateIn(scope, WhileShown, emptyMap())

    private fun TransactionEntity.check(me: String) = CheckRow(
        id, uid, type, amountMinor, accountLabel, timestamp, merchant, rawSms, category, owner ?: me,
        manual = source == TxnSource.MANUAL, statement = source == TxnSource.STATEMENT, reference = reference, edited = userEdited,
    )

    private val checkRows: StateFlow<List<CheckRow>> =
        combine(stored, ownerName) { list, me -> list.filterNot { it.excluded }.map { it.check(me) } }
            .flowOn(Dispatchers.Default).stateIn(scope, WhileShown, emptyList())

    fun answer(key: String, value: String = "ok") = work { dao.answer(ReviewAnswerEntity(key, value)); null }
    fun forgetAnswer(key: String) = work { dao.forget(key); null }

    // ---- Failed payments, reversals, refunds and autopay holds (1.14) ----

    /** A payment and the money that came back for it, both left out of totals. */
    data class ReversalView(
        val key: String,
        val kind: com.myfinance.core.ReversalKind,
        val debit: TransactionEntity,
        val credit: TransactionEntity,
    ) {
        val amountMinor: Long get() = debit.amountMinor
    }

    /** Newest first; pairs whose entries were deleted are left out. */
    val reversals: StateFlow<List<ReversalView>> =
        combine(stored, repo.reviewAnswers) { list, answers ->
            val byUid = list.associateBy { it.uid }
            repo.reversalKeys(answers).mapNotNull { (key, kindName) ->
                val (d, c) = com.myfinance.core.ReversalPair.uidsOf(key) ?: return@mapNotNull null
                val kind = runCatching { com.myfinance.core.ReversalKind.valueOf(kindName) }.getOrDefault(com.myfinance.core.ReversalKind.REFUND)
                ReversalView(key, kind, byUid[d] ?: return@mapNotNull null, byUid[c] ?: return@mapNotNull null)
            }.sortedByDescending { it.credit.timestamp }
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Lazily, emptyList())

    /** The pair each entry belongs to, by uid, for the edit screen. */
    val reversalByUid: StateFlow<Map<String, ReversalView>> =
        reversals.map { l -> l.flatMap { r -> listOf(r.debit.uid to r, r.credit.uid to r) }.toMap() }
            .stateIn(scope, SharingStarted.Lazily, emptyMap())

    /** "Not a reversal": both count again, and the pair is never made again. */
    fun undoReversal(key: String) = work { repo.undoReversal(key); "Both entries count again" }

    /** Looks for pairs again across all entries (they are also found as messages and statements come in). */
    fun findReversals() = work {
        val n = repo.leaveOutReversals()
        if (n == 0) "No new ones found" else if (n == 1) "1 pair found and left out of totals" else "$n pairs found and left out of totals"
    }

    // ---- Transfers between own accounts and card bill payments ----

    val transfers: StateFlow<List<TransferMatch>> =
        combine(checkRows, answers) { rows, a -> SelfTransfers.find(rows, a.keys) }
            .flowOn(Dispatchers.Default).stateIn(scope, WhileShown, emptyList())

    /** Leaves both sides out of totals (they stay in the list, marked "not counted"). */
    fun dontCount(matches: List<TransferMatch>) = work {
        var n = 0
        for (m in matches) {
            for (r in m.rows) {
                val t = repo.get(r.id) ?: continue
                if (!t.excluded) { repo.save(t.copy(excluded = true), learnRule = false); n++ }
            }
            dao.answer(ReviewAnswerEntity(m.key, "transfer"))
        }
        if (n == 1) "1 entry no longer counted" else "$n entries no longer counted"
    }

    fun notTransfer(m: TransferMatch) = answer(m.key, "no")

    // ---- Data health check ----

    val health: StateFlow<HealthReport?> =
        combine(checkRows, answers) { rows, a -> DataHealth.check(rows, LocalDate.now(), a.keys, zone) }
            .flowOn(Dispatchers.Default).stateIn(scope, WhileShown, null)

    /** Keeps the best entry of a group and moves the other copies to Recently deleted. */
    fun removeDuplicate(g: DuplicateGroup) = removeDuplicates(listOf(g))

    /** "Remove all": every group at once, in one go; each removed entry can be restored from Recently deleted. */
    fun removeDuplicates(groups: List<DuplicateGroup>) = work {
        val n = repo.removeDuplicateGroups(groups)
        when (n) {
            0 -> null
            1 -> "Moved 1 entry to Recently deleted"
            else -> "Moved $n entries to Recently deleted"
        }
    }

    /** "All are real": the group and every pair in it are never shown again. */
    fun notDuplicate(g: DuplicateGroup) = work {
        val keys = listOf(g.key) + g.rows.flatMapIndexed { i, a -> g.rows.drop(i + 1).map { b -> com.myfinance.core.Duplicates.pairKey(a, b) } }
        dao.answers(keys.distinct().map { ReviewAnswerEntity(it, "no") })
        null
    }

    /** Entries removed automatically because they had the same transaction ID as another. */
    val sameIdRemoved: Int get() = repo.sameIdRemoved

    // ---- Family UPI ----

    val familyMonth = MutableStateFlow(YearMonth.now())

    private val familyPayments: StateFlow<List<FamilyPayment>> =
        combine(allTransactions, meta, dao.observePeople()) { all, m, people ->
            val set = people.associate { it.txnUid to it.person }
            all.filter { !it.excluded && it.type == TxnType.DEBIT }.mapNotNull { t ->
                val labels = m.labels(t)
                val lite = t.toLite(null)
                val base = t.uid.substringBefore('#')
                if (base !in set && !AutoDebits.isFamilyUpi(DebitRecord(lite, t.rawSms, labels))) return@mapNotNull null
                val person = set[base] ?: FamilyUpi.personFromLabels(labels) ?: FamilyUpi.personFromSms(t.rawSms) ?: FamilyUpi.DEFAULT_PERSON
                FamilyPayment(t.uid, t.id, person, t.amountMinor, t.timestamp, t.merchant)
            }
        }.flowOn(Dispatchers.Default).stateIn(scope, WhileShown, emptyList())

    val familyLimits: StateFlow<Map<String, Long>> =
        dao.observeLimits().map { l -> l.associate { it.person to it.monthlyMinor } }.stateIn(scope, WhileShown, emptyMap())

    val family: StateFlow<FamilyData> =
        combine(familyPayments, familyLimits, familyMonth) { pays, limits, month ->
            FamilyData(
                month = month,
                people = FamilyUpi.month(pays, limits, month, zone),
                payments = pays.filter { YearMonth.from(com.myfinance.core.Dates.localDate(it.timestamp, zone)) == month }.sortedByDescending { it.timestamp },
                history = FamilyUpi.history(pays, month, 6, zone),
            )
        }.flowOn(Dispatchers.Default).stateIn(scope, WhileShown, FamilyData())

    fun setPerson(uid: String, person: String) = work {
        val base = uid.substringBefore('#')
        if (person.isBlank()) dao.clearPerson(base) else dao.putPerson(FamilyPersonEntity(base, person.trim()))
        null
    }

    fun setLimit(person: String, minor: Long?) = work {
        if (minor == null || minor <= 0) dao.deleteLimit(person) else dao.putLimit(FamilyLimitEntity(person.trim(), minor))
        "Limit saved"
    }

    // ---- Split one payment ----

    suspend fun partsOf(uid: String): List<SplitPart> =
        dao.partsOf(uid.substringBefore('#')).map { SplitPart(it.category, it.amountMinor, it.note) }

    /** Saves the extra parts of a payment (an empty list joins it back together). Returns the problem, if any. */
    suspend fun saveSplit(t: TransactionEntity, parts: List<SplitPart>): String? {
        val uid = t.uid.substringBefore('#')
        val original = repo.get(SplitView.originalId(t.id)) ?: return "Payment not found"
        if (parts.isNotEmpty()) PaymentSplit.problem(original.amountMinor, parts)?.let { return it }
        dao.clearParts(uid)
        if (parts.isNotEmpty()) dao.putParts(parts.map { SplitPartEntity(txnUid = uid, category = it.category, amountMinor = it.amountMinor, note = it.note) })
        _message.value = if (parts.isEmpty()) "Joined back into one payment" else "Split into ${parts.size + 1} parts"
        return null
    }

    // ---- Monthly close ----

    private val closed: StateFlow<Set<YearMonth>> =
        dao.observeClosed().map { l -> l.mapNotNull { MonthClose.parse(it.month) }.toSet() }.stateIn(scope, WhileShown, emptySet())

    val monthClose: StateFlow<MonthCloseData> =
        combine(closed, allLite, repo.monthCycle) { c, lite, cycle ->
            val byMonth = lite.groupBy { cycle.periodOf(it.timestamp, it.type, it.category, zone) }
            val first = byMonth.keys.minOrNull()
            MonthCloseData(
                closed = c.sortedDescending(),
                open = MonthClose.openPastMonths(LocalDate.now(), c, first).map { MonthClose.summary(it, byMonth[it].orEmpty()) },
            )
        }.flowOn(Dispatchers.Default).stateIn(scope, WhileShown, MonthCloseData())

    fun closeMonth(m: YearMonth) = work {
        dao.close(ClosedMonthEntity(MonthClose.key(m), System.currentTimeMillis()))
        dao.clearLateMonth(MonthClose.key(m))
        "${m.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${m.year} closed"
    }

    fun reopenMonth(m: YearMonth) = work { dao.reopen(MonthClose.key(m)); "Reopened" }

    private val late: StateFlow<List<TxnLite>> =
        combine(dao.observeLate(), allTransactions, ownerName) { l, all, me ->
            val uids = l.mapTo(HashSet()) { it.txnUid }
            all.filter { it.uid in uids }.map { it.toLite(me) }
        }.stateIn(scope, WhileShown, emptyList())

    // ---- Review inbox ----

    val inbox: StateFlow<List<ReviewItem>> =
        combine(
            combine(allLite, autoDebits) { a, b -> a to b },
            combine(transfers, health) { t, h -> t to h },
            late, family, answers,
        ) { (lite, autos), (xfers, h), lateRows, fam, a ->
            val since = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            ReviewInbox.build(
                unknown = ReviewInbox.unknownPayees(lite, since),
                recurring = autos,
                transfers = xfers,
                duplicates = h?.duplicates.orEmpty(),
                late = lateRows,
                family = if (fam.month == YearMonth.now()) fam.people else emptyList(),
                dismissed = a.keys,
                zoneMillisOf = { d -> d.atStartOfDay(zone).toInstant().toEpochMilli() },
            )
        }.flowOn(Dispatchers.Default).stateIn(scope, WhileShown, emptyList())

    fun lateSeen(item: ReviewItem) = work {
        allTransactions.value.firstOrNull { it.id == item.txnId }?.let { dao.clearLate(it.uid) }
        null
    }

    // ---- Rule tester ----

    suspend fun testRule(rule: CustomRule, others: List<CustomRule>): RuleTestResult = withContext(Dispatchers.Default) {
        val rows = stored.value.ifEmpty { repo.allTransactions().first() }.map {
            RuleTestRow(it.id, it.rawSms, it.merchant, it.type, it.amountMinor, it.category, it.timestamp, it.userEdited || it.source == TxnSource.MANUAL)
        }
        RuleTester.test(rule, others, rows)
    }

    // ---- Full export ----

    private fun exportRows(photos: Set<String>): List<ExportRow> {
        val m = meta.value
        val me = ownerName.value
        return allTransactions.value.map { t ->
            ExportRow(t.toLite(me), m.labels(t), t.note, t.bank, t.source.name.lowercase().replaceFirstChar(Char::uppercase), m.hidden(t), t.excluded, t.uid.substringBefore('#') in photos)
        }
    }

    fun exportAll(uri: Uri, excel: Boolean) = work {
        withContext(Dispatchers.IO) {
            val photos = repo.v12.receiptUids.first().toSet()
            val rows = exportRows(photos)
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                if (excel) XlsxBook.write(out, FullExport.sheets(rows, zone)) else out.write(FullExport.csv(rows, zone).toByteArray())
            } ?: error("Couldn't write the file")
            "Exported ${rows.size} entries"
        }
    }

    // ---- Foreign currency ----

    val fxRates: StateFlow<Map<String, Double>> = repo.fxRates

    fun setFxRate(code: String, rate: Double) { repo.setFxRate(code, rate); _message.value = "$code rate saved" }

    val foreign: StateFlow<List<ForeignRow>> =
        allTransactions.map { all -> all.mapNotNull { t -> Fx.parseNote(t.note)?.let { ForeignRow(t, it) } }.sortedByDescending { it.txn.timestamp } }
            .flowOn(Dispatchers.Default).stateIn(scope, WhileShown, emptyList())

    // ---- Heavy spends ----

    fun heavy(thresholdMinor: Long, from: LocalDate, to: LocalDate, sort: HeavySort, type: TxnType, account: String?, label: String?): HeavySpendResult {
        val labels = meta.value.labelsById
        val txns = allLite.value.filter { (account == null || it.account == account) && (label == null || label in labels[it.id].orEmpty()) }
        return HeavySpends.find(
            txns, thresholdMinor, from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1, sort, type,
        )
    }
}
