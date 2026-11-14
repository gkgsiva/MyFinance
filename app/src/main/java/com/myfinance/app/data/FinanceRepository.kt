package com.myfinance.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.myfinance.app.notify.Notifier
import com.myfinance.app.widget.SpendWidget
import com.myfinance.core.BalanceParser
import com.myfinance.core.BalanceReading
import com.myfinance.core.Balances
import com.myfinance.core.BillParser
import com.myfinance.core.BudgetAlerts
import com.myfinance.core.Categorizer
import com.myfinance.core.CsvCodec
import com.myfinance.core.Dedupe
import com.myfinance.core.ExistingTxn
import com.myfinance.core.ImportCandidate
import com.myfinance.core.StatementImport
import com.myfinance.core.DefaultCategories
import com.myfinance.core.RecurringDetector
import com.myfinance.core.SmsParser
import com.myfinance.core.StatementExport
import com.myfinance.core.TxnType
import com.myfinance.core.XlsxWriter
import com.myfinance.core.formatInr
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.myfinance.core.MonthCycle
import java.time.YearMonth
import java.time.ZoneId

class FinanceRepository(
    private val context: Context,
    private val db: FinanceDatabase,
    private val extrasDb: ExtrasDatabase,
    v12Db: V12Database,
    private val v13Db: V13Database,
    v14Db: V14Database,
    v15Db: V15Database,
    v16Db: V16Database,
    v17Db: V17Database,
    v18Db: V18Database,
) {

    /** Keeps shared database reads alive between screens. */
    private val sharingScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    /** v1.2 features: goals, net worth, bill reminders, splits, receipts, tax, rules, backups. */
    val v12 = V12Repository(context, v12Db) {
        listOf("myfinance.db" to db, "myfinance-extras.db" to extrasDb, V12Database.FILE to v12Db, V13Database.FILE to v13Db, V14Database.FILE to v14Db, V15Database.FILE to v15Db, V16Database.FILE to v16Db, V17Database.FILE to v17Db, V18Database.FILE to v18Db)
    }

    /** v1.3: recently deleted bin, deleted-row records, auto debit plans. */
    val v13: V13Dao = v13Db.dao()

    /** v1.4: label budgets, label suggestion answers, refunds awaited, loan rates, pinned reports. */
    val v14: V14Dao = v14Db.dao()

    /** v1.5: vault, lending, goal links, capital gains, subscription answers, sent reminders. */
    val v15: V15Dao = v15Db.dao()

    /** v1.6: split parts, family UPI people and limits, closed months, review answers. */
    val v16: V16Dao = v16Db.dao()

    /** v1.7: nicknames for payments, kept apart from the merchant name. */
    val v17: V17Dao = v17Db.dao()

    /** Next release: the family's own cards and accounts by number. */
    val v18: V18Dao = v18Db.dao()

    val myAccounts: Flow<List<com.myfinance.core.MyAccount>> = v18.observeAccounts().map { l -> l.map { it.toCore() } }

    private suspend fun myAccountsNow(): List<com.myfinance.core.MyAccount> = runCatching { v18.accounts().map { it.toCore() } }.getOrDefault(emptyList())

    /**
     * Saves a card or account, then puts past entries on that card under its person. Returns how many past
     * entries changed hands.
     */
    suspend fun saveMyAccount(a: com.myfinance.core.MyAccount): Int = withContext(Dispatchers.IO) {
        v18.putAccount(a.toEntity())
        applyMyAccounts()
    }

    suspend fun deleteMyAccount(id: Long) = withContext(Dispatchers.IO) { v18.deleteAccount(id) }

    /** Sets the person on stored entries whose card / account number is registered to someone. */
    suspend fun applyMyAccounts(): Int = withContext(Dispatchers.IO) {
        val accounts = myAccountsNow()
        if (accounts.isEmpty()) return@withContext 0
        val me = ownerName.value
        val changed = db.transactions().allOnce().mapNotNull { t ->
            if (t.source == TxnSource.MANUAL || t.accountTail == null) return@mapNotNull null
            val acc = com.myfinance.core.MyAccounts.match(accounts, t.accountTail, t.bank) ?: return@mapNotNull null
            // Null owner = this phone's owner; a merged entry from another phone keeps its person unless the card says otherwise.
            val want = acc.owner?.takeIf { it.isNotBlank() && it != me }
            val bank = t.bank ?: acc.bank
            if (t.owner == want && t.bank == bank) null else t.copy(owner = want, bank = bank)
        }
        if (changed.isNotEmpty()) db.transactions().updateAll(changed)
        onDataChanged()
        changed.size
    }

    /** True when [tail] is a registered account, or one this phone already has SMS for at the same bank. */
    private suspend fun isOwnAccount(tail: String, bank: String?): Boolean {
        if (com.myfinance.core.MyAccounts.match(myAccountsNow(), tail, bank) != null) return true
        val since = System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000
        return db.transactions().rangeOnce(since, System.currentTimeMillis() + 86_400_000L)
            .any { it.source == TxnSource.SMS && it.accountTail != null && it.accountTail.takeLast(tail.length) == tail && com.myfinance.core.MyAccounts.sameBank(it.bank, bank) }
    }

    /** The person a card / account number belongs to, when registered to someone other than this phone's owner. */
    private suspend fun ownerForNumber(tail: String?, bank: String?): String? =
        com.myfinance.core.MyAccounts.ownerOf(myAccountsNow(), tail, bank)?.takeIf { it.isNotBlank() && it != ownerName.value }

    /** Rupees per unit of other currencies, for foreign spends. Starts from [com.myfinance.core.Fx.defaultRates]. */
    private val _fxRates = MutableStateFlow(com.myfinance.core.Fx.defaultRates + com.myfinance.core.Fx.parseRates(
        context.getSharedPreferences("myfinance", Context.MODE_PRIVATE).getString("fx_rates", null),
    ))
    val fxRates: StateFlow<Map<String, Double>> = _fxRates.asStateFlow()

    fun setFxRate(code: String, rate: Double) {
        val m = LinkedHashMap(_fxRates.value).apply { put(code.uppercase(), rate) }
        _fxRates.value = m
        prefs.edit().putString("fx_rates", com.myfinance.core.Fx.formatRates(m)).apply()
    }

    /** Months the user closed (budget months), so imports leave them alone. */
    private suspend fun closedMonths(): Set<YearMonth> = runCatching { v16.closed().mapNotNull { com.myfinance.core.MonthClose.parse(it.month) }.toSet() }.getOrDefault(emptySet())

    private fun monthOf(t: TransactionEntity): YearMonth = monthCycle.value.periodOf(t.timestamp, t.type, t.category, ZoneId.systemDefault())

    private val extras get() = extrasDb.extras()
    private val prefs = context.getSharedPreferences("myfinance", Context.MODE_PRIVATE)
    private val _ownerName = MutableStateFlow(prefs.getString("owner", null) ?: "Me")

    /** Name of this phone's owner; used to label rows in family exports. */
    val ownerName: StateFlow<String> = _ownerName.asStateFlow()

    fun setOwnerName(name: String) {
        val n = name.trim().ifEmpty { "Me" }
        prefs.edit().putString("owner", n).apply()
        _ownerName.value = n
    }

    private val _monthCycle = MutableStateFlow(readMonthCycle())

    /** Which month a transaction counts towards (custom start day, late salary for next month). */
    val monthCycle: StateFlow<MonthCycle> = _monthCycle.asStateFlow()

    private fun readMonthCycle(): MonthCycle = runCatching {
        MonthCycle(
            startDay = prefs.getInt(KEY_CYCLE_START, 1),
            salaryFromDay = prefs.getInt(KEY_SALARY_FROM, 0).takeIf { it > 0 },
        )
    }.getOrDefault(MonthCycle.CALENDAR)

    /** null: salary month not looked at yet; "applied" (found by itself), "ok", "dismissed", or "user" (set in Manage). */
    var salaryAutoState: String?
        get() = prefs.getString(KEY_SALARY_AUTO, null)
        set(v) { prefs.edit().putString(KEY_SALARY_AUTO, v).apply() }

    fun setMonthCycle(cycle: MonthCycle, byUser: Boolean = true) {
        if (byUser) salaryAutoState = "user"
        prefs.edit()
            .putInt(KEY_CYCLE_START, cycle.startDay)
            .putInt(KEY_SALARY_FROM, cycle.salaryFromDay ?: 0)
            .apply()
        _monthCycle.value = cycle
    }

    val categories: Flow<List<CategoryEntity>> = db.categories().observeAll()

    fun transactionsBetween(from: Long, to: Long): Flow<List<TransactionEntity>> =
        db.transactions().observeRange(from, to)

    /**
     * Every stored transaction, read once per change and shared by all screens (each screen used to run its own
     * query of the whole table on every change). Unchanged results are dropped, and a burst of changes (an import
     * writing many chunks) only delivers the latest list.
     */
    private val sharedTransactions: Flow<List<TransactionEntity>> = db.transactions().observeAll()
        .distinctUntilChanged()
        .conflate()
        .flowOn(Dispatchers.IO)
        .shareIn(sharingScope, SharingStarted.WhileSubscribed(10_000, replayExpirationMillis = 0), replay = 1)

    fun allTransactions(): Flow<List<TransactionEntity>> = sharedTransactions

    val bills: Flow<List<BillEntity>> = extras.observeBills()

    /** Latest balance per account. */
    val balances: Flow<List<BalanceReading>> = extras.observeBalances().map { list -> Balances.latest(list.map { it.toReading() }) }

    /** Payment modes of rows merged from other phones, by uid. */
    val mergedModes: Flow<Map<String, String>> = extras.observeModes().map { list -> list.associate { it.uid to it.mode } }

    suspend fun get(id: Long): TransactionEntity? = db.transactions().get(id)

    suspend fun ensureDefaults() {
        runCatching { leaveOutCardBillPaymentsOnce() }
        runCatching { removeStatementRepeatsOnce() }
        runCatching { removeSmsDoublesOnce() }
        runCatching { refreshStatementMerchantsOnce() }
        runCatching { restoreSalaryOnce() }
        runCatching { findInvestmentsOnce() }
        runCatching { leaveOutCardPaymentPairs() }
        runCatching { removeBillerReceiptsOnce() }
        runCatching { nameCodePayeesOnce() }
        runCatching { leaveOutReversals() }
        runCatching { fillEmiEndDates() }
        runCatching { removeSameIdDuplicates() }
        if (db.categories().count() > 0) return
        db.categories().insertAll(
            DefaultCategories.all.mapIndexed { i, name ->
                CategoryEntity(name = name, builtIn = true, color = palette[i % palette.size])
            },
        )
    }

    /**
     * Once, for entries stored before card bill payments were left out automatically: the payment from the
     * bank account and the "payment received" on the card stop counting as spending and income. Entries the
     * user edited are left alone.
     */
    private suspend fun leaveOutCardBillPaymentsOnce() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_CARD_BILL_CLEANUP, false)) return@withContext
        val cards = myAccountsNow().filter { it.isCard }
        val changed = db.transactions().allOnce().filter { t ->
            !t.excluded && !t.userEdited && t.source != TxnSource.MANUAL && run {
                val onCard = t.source == TxnSource.STATEMENT && com.myfinance.core.MyAccounts.match(cards, t.accountTail, t.bank) != null
                com.myfinance.core.SelfTransfers.isCardBillPayment(t.type, t.merchant, t.rawSms, onCard)
            }
        }.map { it.copy(excluded = true) }
        if (changed.isNotEmpty()) db.transactions().updateAll(changed)
        prefs.edit().putBoolean(KEY_CARD_BILL_CLEANUP, true).apply()
    }

    /**
     * Once (1.11.1): statement lines stored twice under two card numbers by earlier imports. They go to
     * Recently deleted (so they can be restored) and are remembered, so importing again won't add them back.
     */
    private suspend fun removeStatementRepeatsOnce() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_STMT_REPEAT_CLEANUP, false)) return@withContext
        val zone = ZoneId.systemDefault()
        val all = db.transactions().allOnce().filter { it.source == TxnSource.STATEMENT }
        val ids = com.myfinance.core.StatementImport.storedRepeats(
            all.map { t ->
                com.myfinance.core.StatementImport.StoredLine(
                    t.id, com.myfinance.core.Dates.localDate(t.timestamp, zone).toEpochDay(), t.amountMinor, t.type,
                    t.rawSms, t.accountTail?.filter(Char::isDigit)?.takeLast(4), t.userEdited,
                )
            },
        ).toHashSet()
        // Marked done first: if anything below fails, the app must not try (and fail) again at every start.
        prefs.edit().putBoolean(KEY_STMT_REPEAT_CLEANUP, true).apply()
        if (ids.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val gone = all.filter { it.id in ids }
            // Remembered first, so a removed line is never imported again; then removed in one go, so the
            // screens refresh once instead of once per line.
            for (t in gone) {
                runCatching { v13.putTrash(DeletedTxnEntity.from(t, now)) }
                runCatching { v13.putKey(DeletedKeyEntity(t.uid, t.smsKey, now)) }
            }
            db.withTransaction { gone.map { it.uid }.chunked(500).forEach { db.transactions().deleteByUids(it) } }
            prefs.edit().putInt(KEY_STMT_REPEAT_REMOVED, gone.size).apply()
            onDataChanged()
        }
    }

    /**
     * Once (1.11.3): an SMS entry and the statement line for the same payment, both kept by earlier versions
     * because they didn't see them as one (ICICI's SMS say "Acct XX144" where the statement says 4144; a debit
     * card's SMS names the card; a foreign spend's SMS is in dollars). The statement line stays, as the official
     * record, and takes the SMS text and anything the user changed on the SMS entry (category, note, labels,
     * nickname). The SMS entry goes to Recently deleted and is remembered, so a rescan won't add it back.
     */
    private suspend fun removeSmsDoublesOnce() = withContext(Dispatchers.IO) {
        // Run again once in 1.14: a statement line and its SMS kept apart since 1.11.3 (Jio, 4 Nov) are put together.
        if (prefs.getBoolean(KEY_SMS_DOUBLE_CLEANUP_2, false)) return@withContext
        // Marked done first: if anything below fails, the app must not try (and fail) again at every start.
        prefs.edit().putBoolean(KEY_SMS_DOUBLE_CLEANUP, true).putBoolean(KEY_SMS_DOUBLE_CLEANUP_2, true).apply()
        val all = db.transactions().allOnce()
        val sms = all.filter { it.source == TxnSource.SMS && it.smsKey != null && !it.smsKey.startsWith("STMT|") }
        // Statement lines not yet linked to an SMS.
        val lines = all.filter { it.source == TxnSource.STATEMENT && it.smsKey?.startsWith("STMT|") == true }
        if (sms.isEmpty() || lines.isEmpty()) return@withContext
        fun ex(t: TransactionEntity, line: Boolean) =
            ExistingTxn(t.amountMinor, t.type, t.timestamp, t.accountTail, t.reference, t.smsKey, t.id, line, t.rawSms)
        // Family members' entries pair only with their own statements.
        val pairs = sms.groupBy { it.owner.orEmpty() }.flatMap { (owner, mine) ->
            val theirLines = lines.filter { it.owner.orEmpty() == owner }
            StatementImport.storedSmsDoubles(mine.map { ex(it, false) }, theirLines.map { ex(it, true) })
                .map { (si, li) -> mine[si] to theirLines[li] }
        }
        if (pairs.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val labelsByUid = runCatching { v13.allLabels() }.getOrDefault(emptyList()).groupBy({ it.uid }, { it.label })
        val newLabels = ArrayList<TxnLabelEntity>()
        val nicks = ArrayList<NicknameEntity>()
        val updated = pairs.map { (s, line) ->
            labelsByUid[s.uid].orEmpty().filter { it !in labelsByUid[line.uid].orEmpty() }.forEach { newLabels += TxnLabelEntity(line.uid, it) }
            runCatching { v17.nicknameOf(s.uid)?.let { n -> if (v17.nicknameOf(line.uid) == null) nicks += NicknameEntity(line.uid, n) } }
            val edits = s.userEdited && !line.userEdited
            line.copy(
                rawSms = statementText(line.rawSms.orEmpty(), s.rawSms),
                reference = line.reference ?: s.reference, bank = line.bank ?: s.bank,
                // The card the SMS names (a UPI payment on the RuPay card) and the SMS's time of day.
                accountTail = StatementImport.keptTail(line.accountTail, s.accountTail, s.rawSms),
                timestamp = statementTime(com.myfinance.core.Dates.localDate(line.timestamp, ZoneId.systemDefault()), s.timestamp),
                category = com.myfinance.core.SalaryRepair.mergedCategory(line.category, s.category, line.userEdited, s.userEdited),
                note = line.note ?: s.note,
                excluded = if (edits) s.excluded else line.excluded || s.excluded,
                userEdited = line.userEdited || s.userEdited,
            )
        }
        val gone = pairs.map { it.first }
        // Remembered first (in one go), so a removed SMS is never added again; then all changes in one transaction,
        // so the screens refresh once instead of once per entry.
        runCatching {
            v13Db.withTransaction {
                for (t in gone) {
                    v13.putTrash(DeletedTxnEntity.from(t, now))
                    v13.putKey(DeletedKeyEntity(t.uid, t.smsKey, now))
                }
            }
        }
        db.withTransaction {
            db.transactions().updateAll(updated)
            gone.map { it.uid }.chunked(500).forEach { db.transactions().deleteByUids(it) }
        }
        if (newLabels.isNotEmpty()) runCatching { v13.addLabels(newLabels) }
        if (nicks.isNotEmpty()) runCatching { v17.putNicknames(nicks) }
        prefs.edit().putInt(KEY_SMS_DOUBLE_REMOVED, gone.size).apply()
        onDataChanged()
    }

    /**
     * Once (1.12.1): salary credits that lost the Salary category when their SMS was joined with the statement line
     * (the line kept "Transfers" or "Income"), so the salary didn't count as salary or move to the salary month.
     * Found by the SMS set aside in Recently deleted, or by "salary" in the text. Entries the user edited stay.
     */
    /**
     * Once (1.14): uncategorised payments that the new investment words now recognise (CAMS, KFintech, BSE StAR MF,
     * clearing corporations, more brokers, FD/RD, NPS ...) move to Investments, so they stop counting as spending.
     * Only entries still in "Uncategorized" that the user never edited; nothing else changes.
     */
    private suspend fun findInvestmentsOnce() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_INVEST_FIND, false)) return@withContext
        prefs.edit().putBoolean(KEY_INVEST_FIND, true).apply()
        val cat = categorizer()
        val changed = db.transactions().allOnce()
            .filter { it.type == TxnType.DEBIT && it.category == DefaultCategories.OTHER && !it.userEdited && !it.excluded }
            .filter { cat.categorize(it.merchant, it.type, it.rawSms) == DefaultCategories.INVESTMENTS }
            .map { it.copy(category = DefaultCategories.INVESTMENTS) }
        if (changed.isNotEmpty()) {
            db.withTransaction { changed.chunked(500).forEach { db.transactions().updateAll(it) } }
            onDataChanged()
        }
    }

    private suspend fun restoreSalaryOnce() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_SALARY_RESTORE, false)) return@withContext
        prefs.edit().putBoolean(KEY_SALARY_RESTORE, true).apply()
        fun row(t: TransactionEntity) = com.myfinance.core.SalaryRepair.Row(t.id, t.type, t.amountMinor, t.timestamp, t.category, t.rawSms, t.userEdited)
        val rows = db.transactions().allOnce().filter { it.type == TxnType.CREDIT }
        val removed = runCatching { v13.trash() }.getOrDefault(emptyList()).filter { it.source == TxnSource.SMS.name }.map {
            com.myfinance.core.SalaryRepair.Row(it.originalId, runCatching { TxnType.valueOf(it.type) }.getOrDefault(TxnType.DEBIT), it.amountMinor, it.timestamp, it.category, it.rawSms, it.userEdited)
        }
        val ids = com.myfinance.core.SalaryRepair.toRestore(rows.map(::row), removed).toSet()
        if (ids.isEmpty()) return@withContext
        db.withTransaction { db.transactions().updateAll(rows.filter { it.id in ids }.map { it.copy(category = DefaultCategories.SALARY) }) }
        onDataChanged()
    }

    /**
     * Once per parser update (last: 1.15, brands and UPI payees in all ICICI layouts): statement lines get the merchant name read from their narration again, now that names are
     * picked out of UPI, IMPS, NEFT, debit card and bill payment remarks (and never left empty). Lines the user
     * edited keep theirs; a line still in "Other" is categorised again by its new name.
     */
    private suspend fun refreshStatementMerchantsOnce() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_MERCHANT_REFRESH, false)) return@withContext
        prefs.edit().putBoolean(KEY_MERCHANT_REFRESH, true).apply()
        val cat = categorizer()
        val changed = db.transactions().allOnce().filter { it.source == TxnSource.STATEMENT && !it.userEdited && !it.rawSms.isNullOrBlank() }
            .mapNotNull { t ->
                val narration = t.rawSms!!.removePrefix("Statement: ").substringBefore("\n\nSMS: ")
                val name = com.myfinance.core.Narration.merchant(narration) ?: return@mapNotNull null
                if (name == t.merchant) return@mapNotNull null
                // A rule of the user's that names the merchant keeps its name.
                if (runCatching { v12.ruleFor(null, narration, t.merchant, t.type, t.amountMinor)?.merchant }.getOrNull() != null) return@mapNotNull null
                val category = if (t.category == DefaultCategories.OTHER && !t.excluded) cat.categorize(name, t.type, narration) else t.category
                t.copy(merchant = name, category = category)
            }
        if (changed.isNotEmpty()) {
            db.withTransaction { changed.chunked(500).forEach { db.transactions().updateAll(it) } }
            onDataChanged()
        }
    }

    /** How many SMS entries the 1.11.3 cleanup found already imported from a statement (0 when none). */
    val smsDoublesRemoved: Int get() = prefs.getInt(KEY_SMS_DOUBLE_REMOVED, 0)

    /**
     * A card bill paid from a bank account, with both sides stored (the bank's debit and the "payment received"
     * on the card): both stop counting as spending and income. Cards are those in My cards & accounts, plus any
     * account whose messages say "credit card". Entries the user edited are left alone.
     */
    suspend fun leaveOutCardPaymentPairs(from: Long = 0, to: Long = Long.MAX_VALUE): Int = withContext(Dispatchers.IO) {
        val rows = if (from == 0L && to == Long.MAX_VALUE) db.transactions().allOnce() else db.transactions().rangeOnce(from, to)
        fun tail(t: TransactionEntity) = t.accountTail?.filter(Char::isDigit)?.takeLast(4)
        val cardWords = Regex("""credit\s*card""", RegexOption.IGNORE_CASE)
        val cards = myAccountsNow().filter { it.isCard }.map { it.tail }.toHashSet()
        rows.filter { it.rawSms?.let { s -> cardWords.containsMatchIn(s) } == true && it.type == TxnType.CREDIT }.mapNotNullTo(cards) { tail(it) }
        // A bank account is never a card, even when one of its messages mentions paying a credit card.
        myAccountsNow().filter { !it.isCard }.forEach { cards -= it.tail }
        if (cards.isEmpty()) return@withContext 0
        val pairs = com.myfinance.core.SelfTransfers.cardPaymentPairs(
            rows.filter { it.source != TxnSource.MANUAL }.map { com.myfinance.core.SelfTransfers.PayRow(it.id, it.type, it.amountMinor, it.timestamp, it.accountTail, listOfNotNull(it.merchant, it.rawSms).joinToString(" "), it.userEdited) },
            cards,
        )
        val ids = pairs.flatMap { listOf(it.first, it.second) }.toHashSet()
        val changed = rows.filter { it.id in ids && (!it.excluded || it.category != DefaultCategories.TRANSFERS) }
            .map { it.copy(excluded = true, category = DefaultCategories.TRANSFERS) }
        if (changed.isNotEmpty()) db.transactions().updateAll(changed)
        changed.size
    }

    // ---- 1.14: failed payments, reversals, refunds and autopay holds ----

    private fun TransactionEntity.checkRow() = com.myfinance.core.CheckRow(
        id, uid, type, amountMinor, accountTail, timestamp, merchant, rawSms, category, owner.orEmpty(),
        manual = source == TxnSource.MANUAL, statement = source == TxnSource.STATEMENT, reference = reference, edited = userEdited,
    )

    // ---- 1.15: duplicates by transaction ID ----

    /** How many entries with the same transaction ID as another were removed automatically, in all. */
    val sameIdRemoved: Int get() = prefs.getInt(KEY_SAME_ID_REMOVED, 0)

    /**
     * Entries that carry the same transaction ID (UPI reference, RRN, card statement reference number) as another
     * entry of the same amount and direction within 3 days are one payment stored twice: the extra copies go to
     * Recently deleted. Runs at start and after an import. Returns how many were removed.
     */
    suspend fun removeSameIdDuplicates(): Int = withContext(Dispatchers.IO) {
        val rows = db.transactions().allOnce().filter { !it.excluded }
        val dismissed = v16.answersOnce().filter { it.key.startsWith("dup:") && it.answer == "no" }.mapTo(HashSet()) { it.key }
        val groups = com.myfinance.core.Duplicates.find(rows.map { it.checkRow() }, dismissed).filter { it.sameId }
        if (groups.isEmpty()) return@withContext 0
        val n = removeDuplicateGroups(groups, rows)
        prefs.edit().putInt(KEY_SAME_ID_REMOVED, sameIdRemoved + n).apply()
        n
    }

    /**
     * Keeps one entry of each group and moves the others to Recently deleted, all in one go (one database
     * transaction, so a big clean-up can't stop half way). The kept entry takes what the user changed on a removed
     * one (category, note, labels, nickname) when it was not edited itself, and its transaction ID when it had
     * none. Removed entries are remembered, so a rescan or import won't add them back. Returns how many were removed.
     */
    suspend fun removeDuplicateGroups(
        groups: List<com.myfinance.core.DuplicateGroup>,
        loaded: List<TransactionEntity>? = null,
    ): Int = withContext(Dispatchers.IO) {
        val byId = (loaded ?: db.transactions().allOnce()).associateBy { it.id }
        val labelsByUid = runCatching { v13.allLabels() }.getOrDefault(emptyList()).groupBy({ it.uid }, { it.label })
        val newLabels = ArrayList<TxnLabelEntity>()
        val nicks = ArrayList<NicknameEntity>()
        val updated = ArrayList<TransactionEntity>()
        val gone = ArrayList<TransactionEntity>()
        for (g in groups) {
            var keep = byId[g.keep.id] ?: continue
            val drop = g.extras.mapNotNull { byId[it.id] }
            if (drop.isEmpty()) continue
            val before = keep
            drop.firstOrNull { it.userEdited }?.takeIf { !keep.userEdited }?.let { e ->
                keep = keep.copy(category = e.category, note = keep.note ?: e.note, userEdited = true)
            }
            if (keep.reference == null) drop.firstNotNullOfOrNull { it.reference }?.let { keep = keep.copy(reference = it) }
            if (keep.note == null) drop.firstNotNullOfOrNull { it.note }?.let { keep = keep.copy(note = it) }
            if (keep != before) updated += keep
            val have = labelsByUid[keep.uid].orEmpty().toMutableSet()
            for (d in drop) {
                labelsByUid[d.uid].orEmpty().filter { have.add(it) }.forEach { newLabels += TxnLabelEntity(keep.uid, it) }
                runCatching { v17.nicknameOf(d.uid)?.let { nk -> if (nicks.none { it.txnUid == keep.uid } && v17.nicknameOf(keep.uid) == null) nicks += NicknameEntity(keep.uid, nk) } }
            }
            gone += drop
        }
        if (gone.isEmpty()) return@withContext 0
        val now = System.currentTimeMillis()
        // Remembered first (in one go), so a removed entry is never added again; then all changes in one transaction.
        v13Db.withTransaction {
            for (t in gone) {
                v13.putTrash(DeletedTxnEntity.from(t, now))
                v13.putKey(DeletedKeyEntity(t.uid, t.smsKey, now))
            }
        }
        db.withTransaction {
            if (updated.isNotEmpty()) db.transactions().updateAll(updated)
            gone.map { it.uid }.chunked(500).forEach { db.transactions().deleteByUids(it) }
        }
        if (newLabels.isNotEmpty()) runCatching { v13.addLabels(newLabels) }
        if (nicks.isNotEmpty()) runCatching { v17.putNicknames(nicks) }
        onDataChanged()
        gone.size
    }

    /**
     * A payment and the money that came back for it (failed and reversed, refunded, an autopay hold released):
     * both stop counting as spending and income, and the pair is remembered so it can be listed and undone.
     * Pairs the user undid ("Not a reversal") are never made again. Returns how many pairs were made.
     */
    suspend fun leaveOutReversals(from: Long = 0, to: Long = Long.MAX_VALUE): Int = withContext(Dispatchers.IO) {
        val rows = if (from == 0L && to == Long.MAX_VALUE) db.transactions().allOnce() else db.transactions().rangeOnce(from, to)
        val answers = v16.answersOnce().filter { it.key.startsWith("rev:") }
        val dismissed = answers.filter { it.answer == REV_NOT }.mapTo(HashSet()) { it.key }
        val pairs = com.myfinance.core.Reversals.find(rows.filter { !it.excluded }.map { it.checkRow() }, dismissed)
        if (pairs.isEmpty()) return@withContext 0
        val ids = pairs.flatMap { listOf(it.debit.id, it.credit.id) }.toHashSet()
        val changed = rows.filter { it.id in ids }.map { it.copy(excluded = true) }
        // Remembered first, so the list can show the pair even if the update below is interrupted.
        for (p in pairs) v16.answer(ReviewAnswerEntity(p.key, REV_PAIR + ":" + p.kind.name))
        db.withTransaction { db.transactions().updateAll(changed) }
        onDataChanged()
        pairs.size
    }

    /** Reversal pairs remembered in [answers]: (key, kind name) for the ones still paired. */
    fun reversalKeys(answers: List<ReviewAnswerEntity>): List<Pair<String, String>> =
        answers.filter { it.key.startsWith("rev:") && it.answer.startsWith(REV_PAIR) }
            .map { it.key to it.answer.substringAfter(':', "") }

    val reviewAnswers: Flow<List<ReviewAnswerEntity>> get() = v16.observeAnswers()

    /** "Not a reversal": both entries count again, and this pair is never made again. */
    suspend fun undoReversal(key: String) = withContext(Dispatchers.IO) {
        val (d, c) = com.myfinance.core.ReversalPair.uidsOf(key) ?: return@withContext
        val rows = db.transactions().allOnce().filter { it.uid == d || it.uid == c }
        v16.answer(ReviewAnswerEntity(key, REV_NOT))
        val changed = rows.filter { it.excluded }.map { it.copy(excluded = false) }
        if (changed.isNotEmpty()) db.withTransaction { db.transactions().updateAll(changed) }
        onDataChanged()
    }

    /**
     * A biller's receipt for [amountMinor] names the payee: a debit of that amount within a day whose payee is a UPI
     * code or missing takes [brand], and the code is remembered as that payee's name for next time.
     */
    private suspend fun nameFromReceipt(amountMinor: Long, brand: String, at: Long) {
        val day = 24 * 60 * 60 * 1000L
        val rows = db.transactions().rangeOnce(at - day, at + day).filter { t ->
            t.type == TxnType.DEBIT && t.amountMinor == amountMinor && !t.userEdited &&
                (t.merchant == null || com.myfinance.core.PayeeNames.isCode(t.merchant))
        }
        if (rows.isEmpty()) return
        rows.mapNotNull { it.merchant }.distinct().forEach { code ->
            if (payeeName(code) == null) v13.putPayeeNames(listOf(PayeeNameEntity(Categorizer.merchantKey(code), brand)))
        }
        db.transactions().updateAll(rows.map { it.copy(merchant = brand) })
    }

    /**
     * Once (1.14): billers' receipts ("Payment of Rs. 2576.71 for your JioHome connection has been received") that
     * earlier versions stored as money received. They name the payee of the matching debit, then go to Recently
     * deleted (restorable) and are remembered, so a rescan won't add them back.
     */
    private suspend fun removeBillerReceiptsOnce() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_RECEIPTS_REMOVED, false)) return@withContext
        prefs.edit().putBoolean(KEY_RECEIPTS_REMOVED, true).apply()
        val receipts = db.transactions().allOnce().mapNotNull { t ->
            if (t.type != TxnType.CREDIT || t.userEdited || t.source != TxnSource.SMS) return@mapNotNull null
            val body = t.rawSms ?: return@mapNotNull null
            val sender = t.smsKey?.substringBefore('|') ?: ""
            SmsParser.billerReceipt(sender, body)?.let { t to it }
        }
        if (receipts.isEmpty()) return@withContext
        for ((t, r) in receipts) r.second?.let { brand -> runCatching { nameFromReceipt(r.first, brand, t.timestamp) } }
        val now = System.currentTimeMillis()
        for ((t, _) in receipts) {
            runCatching { v13.putTrash(DeletedTxnEntity.from(t, now)) }
            runCatching { v13.putKey(DeletedKeyEntity(t.uid, t.smsKey, now)) }
        }
        db.withTransaction { receipts.map { it.first.uid }.chunked(500).forEach { db.transactions().deleteByUids(it) } }
        onDataChanged()
    }

    /**
     * Once (1.14): payees stored as a UPI code ("yblec123879a52a") whose message names the brand ("JioFiber") take
     * the brand's name. Entries the user edited and payees the user named are left alone.
     */
    private suspend fun nameCodePayeesOnce() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_CODE_PAYEES, false)) return@withContext
        prefs.edit().putBoolean(KEY_CODE_PAYEES, true).apply()
        val changed = db.transactions().allOnce().mapNotNull { t ->
            val m = t.merchant ?: return@mapNotNull null
            if (t.userEdited || !com.myfinance.core.PayeeNames.isCode(m)) return@mapNotNull null
            val better = com.myfinance.core.PayeeNames.brandIn(t.rawSms) ?: return@mapNotNull null
            t.copy(merchant = better)
        }
        if (changed.isNotEmpty()) db.withTransaction { changed.chunked(500).forEach { db.transactions().updateAll(it) } }
    }

    /** How many repeated statement lines the 1.11.1 cleanup moved to Recently deleted (0 when none). */
    val statementRepeatsRemoved: Int get() = prefs.getInt(KEY_STMT_REPEAT_REMOVED, 0)

    private suspend fun categorizer(): Categorizer =
        Categorizer(db.merchantRules().all().associate { it.merchantKey to it.category })

    /** What happened to one SMS on import. */
    enum class Ingest { ADDED, ALREADY_HERE, DELETED_BEFORE, NOT_A_TRANSACTION }

    /**
     * Parses one SMS: stores the transaction it describes, plus any bill due date or account balance
     * it mentions. A message whose transaction the user deleted is not added again.
     */
    suspend fun ingestSms(sender: String, body: String, timestamp: Long, categorizer: Categorizer? = null): Ingest {
        val key = smsKey(sender, body)
        BillParser.parse(sender, body, timestamp)?.let { b ->
            extras.insertBill(
                BillEntity(
                    smsKey = key, kind = b.kind.name, amountMinor = b.amountMinor, minDueMinor = b.minDueMinor,
                    dueEpochDay = b.dueDate.toEpochDay(), accountTail = b.accountTail, bank = b.bank, receivedAt = timestamp,
                ),
            )
        }
        BalanceParser.parse(sender, body, timestamp)?.let { r ->
            extras.insertBalance(
                BalanceEntity(
                    smsKey = key, bank = r.bank, accountTail = r.accountTail, amountMinor = r.amountMinor,
                    isCardLimit = r.isCardLimit, timestamp = timestamp,
                ),
            )
        }

        runCatching { v12.onSms(key, body, timestamp) }
        // Electricity, water, gas, broadband and property tax bills fill in their payment reminder.
        runCatching { com.myfinance.core.UtilityBills.parse(sender, body, timestamp)?.let { v12.onUtilityBill(it) } }

        // A biller's receipt (JioHome "payment received") is not income; it names the payee of the bank's debit.
        SmsParser.billerReceipt(sender, body)?.let { (amount, brand) ->
            if (brand != null) runCatching { nameFromReceipt(amount, brand, timestamp) }
            return Ingest.NOT_A_TRANSACTION
        }
        val parsed = SmsParser.parse(sender, body, timestamp, _fxRates.value) ?: return Ingest.NOT_A_TRANSACTION
        if (v13.isSmsDeleted(key) > 0) return Ingest.DELETED_BEFORE
        // The user's own rules ("any SMS with SWIGGY goes to Food") come before everything else.
        val rule = v12.ruleFor(sender, body, parsed.merchant, parsed.type, parsed.amountMinor)
        if (rule?.ignore == true) return Ingest.NOT_A_TRANSACTION
        // "Acct XX144 debited … & Acct XX848 credited": money moved to another account of the family is not spending.
        // Paying a card bill from the bank, or the card saying the payment arrived: the card's purchases are the spending.
        val ownTransfer = parsed.counterTail?.let { t -> runCatching { isOwnAccount(t, parsed.bank) }.getOrDefault(false) } == true ||
            com.myfinance.core.SelfTransfers.isCardBillPayment(parsed.type, parsed.merchant, body)
        val cat = if (ownTransfer) com.myfinance.core.DefaultCategories.TRANSFERS
            else (categorizer ?: categorizer()).categorize(parsed.merchant, parsed.type, body)
        val base = TransactionEntity(
            excluded = ownTransfer,
            amountMinor = parsed.amountMinor,
            type = parsed.type,
            merchant = parsed.merchant,
            category = cat,
            accountTail = parsed.accountTail,
            bank = parsed.bank,
            timestamp = parsed.timestamp,
            source = TxnSource.SMS,
            smsKey = key,
            rawSms = body,
            reference = parsed.reference,
            balanceMinor = parsed.balanceMinor,
            note = parsed.foreign?.let { f -> parsed.fxRate?.let { com.myfinance.core.Fx.note(f, it) } },
            // A registered card or account of another family member (an add-on card, a joint account).
            owner = runCatching { ownerForNumber(parsed.accountTail, parsed.bank) }.getOrNull(),
        )
        // A name the user gave this payee ("Vegetable seller Ravi") replaces the raw UPI id / merchant text.
        val nick = parsed.merchant?.let { m -> runCatching { payeeName(m) }.getOrNull() }
        val row = (rule?.applyTo(base) ?: base).let { r -> if (nick != null && rule?.merchant == null) r.copy(merchant = nick) else r }
        // Already imported from a bank / card statement: link the SMS to that line, never a second entry.
        if (runCatching { linkToStatementLine(row, nick) }.getOrDefault(false)) return Ingest.ALREADY_HERE
        // The bank's alert and the UPI / wallet app's message for one payment: keep one.
        if (runCatching { repeatsAnotherSms(row) }.getOrDefault(false)) return Ingest.ALREADY_HERE
        val added = db.transactions().insert(row) != -1L
        if (added) {
            // The other side of a card bill payment may already be here (bank debit and card credit).
            runCatching { val w = 5L * 24 * 60 * 60 * 1000; leaveOutCardPaymentPairs(timestamp - w, timestamp + w) }
            // A failed payment's money coming back (or the payment it came back for).
            runCatching { val w = 11L * 24 * 60 * 60 * 1000; leaveOutReversals(timestamp - w, timestamp + w) }
            runCatching { v12.onNewTransaction(row, rule) }
            runCatching { applyLabelRules(row, parsed.merchant) }
            // A late SMS for a month already closed: added, and shown in the review inbox.
            runCatching {
                val m = monthOf(row)
                if (m in closedMonths()) v16.putLate(LateArrivalEntity(row.uid, m.toString(), System.currentTimeMillis()))
            }
        }
        return if (added) Ingest.ADDED else Ingest.ALREADY_HERE
    }

    /**
     * Runs the custom rules over SMS transactions already stored (ones you have not edited by hand).
     * Returns how many changed.
     */
    suspend fun applyRulesToExisting(): Int = withContext(Dispatchers.IO) {
        v12.invalidateRules()
        val changed = ArrayList<TransactionEntity>()
        for (t in db.transactions().allOnce()) {
            if (t.userEdited || t.source == TxnSource.MANUAL) continue
            val r = v12.ruleFor(null, t.rawSms, t.merchant, t.type, t.amountMinor) ?: continue
            val updated = r.applyTo(t).let { if (r.ignore) it.copy(excluded = true) else it }
            r.taxSection?.let { v12.dao.upsertTaxTag(TaxTagEntity(t.uid, it.name)) }
            if (updated != t) changed += updated
        }
        if (changed.isNotEmpty()) db.transactions().updateAll(changed)
        onDataChanged()
        changed.size
    }

    data class ScanResult(
        val messages: Int = 0,
        val added: Int = 0,
        val alreadyHere: Int = 0,
        val skippedDeleted: Int = 0,
        val restored: Int = 0,
    )

    /**
     * Reads the SMS inbox and imports every transaction message received in the last [monthsBack] months
     * (0 = the whole inbox). Safe to run repeatedly: messages already imported (including ones you edited)
     * are left as they are, and ones you deleted are skipped unless [bringBackDeleted] is set.
     */
    suspend fun importInbox(monthsBack: Int = 12, bringBackDeleted: Boolean = false): ScanResult = withContext(Dispatchers.IO) {
        val since = if (monthsBack <= 0) 0L else System.currentTimeMillis() - monthsBack * 31L * 24 * 60 * 60 * 1000
        var restored = 0
        if (bringBackDeleted) {
            // Restore the bin's copies first, so any edits made before deleting are kept.
            for (t in v13.trash()) if (t.smsKey != null && restoreRow(t)) restored++
            v13.forgetDeletedSms()
        }
        val cat = categorizer()
        var messages = 0
        var added = 0
        var already = 0
        var skipped = 0
        context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"),
            "date >= ?",
            arrayOf(since.toString()),
            "date ASC",
        )?.use { c ->
            val iAddr = c.getColumnIndexOrThrow("address")
            val iBody = c.getColumnIndexOrThrow("body")
            val iDate = c.getColumnIndexOrThrow("date")
            while (c.moveToNext()) {
                val sender = c.getString(iAddr) ?: continue
                val body = c.getString(iBody) ?: continue
                if (!SmsParser.looksLikeFinancialSender(sender)) continue
                messages++
                when (ingestSms(sender, body, c.getLong(iDate), cat)) {
                    Ingest.ADDED -> added++
                    Ingest.ALREADY_HERE -> already++
                    Ingest.DELETED_BEFORE -> skipped++
                    Ingest.NOT_A_TRANSACTION -> {}
                }
            }
        }
        prefs.edit().putBoolean(KEY_BACKFILLED, true).putLong(KEY_LAST_SCAN, System.currentTimeMillis()).apply()
        onDataChanged()
        ScanResult(messages, added, already, skipped, restored)
    }

    /** When the inbox was last read in full (0 = never). */
    val lastScan: Long get() = prefs.getLong(KEY_LAST_SCAN, 0L)

    /** v1.0 never stored bills or balances, so v1.1 re-reads the inbox once after the upgrade. */
    val needsBackfill: Boolean get() = !prefs.getBoolean(KEY_BACKFILLED, false)

    /**
     * Saves a new or edited transaction. When [learnRule] is set and the category changed, the
     * merchant -> category mapping is remembered and applied to that merchant's other unedited rows.
     */
    suspend fun save(txn: TransactionEntity, learnRule: Boolean = true) {
        if (txn.id == 0L) {
            db.transactions().insert(txn)
        } else {
            val before = db.transactions().get(txn.id)
            db.transactions().update(txn.copy(userEdited = true))
            if (learnRule && before != null && before.category != txn.category && txn.merchant != null) {
                learn(txn.merchant, txn.category)
            }
        }
        onDataChanged()
    }

    private suspend fun learn(merchant: String, category: String) {
        val key = Categorizer.merchantKey(merchant)
        db.merchantRules().upsert(MerchantRuleEntity(key, category))
        val updates = db.transactions().uneditedWithMerchant()
            .filter { Categorizer.merchantKey(it.merchant!!) == key && it.category != category }
            .map { it.copy(category = category) }
        if (updates.isNotEmpty()) db.transactions().updateAll(updates)
    }

    /**
     * Moves a transaction to the Recently deleted bin. It can be restored from there; a record of it is
     * kept even after the bin is emptied, so rescanning the inbox or merging a family file won't add it back.
     */
    suspend fun delete(txn: TransactionEntity) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        v13.putTrash(DeletedTxnEntity.from(txn, now))
        v13.putKey(DeletedKeyEntity(txn.uid, txn.smsKey, now))
        db.transactions().delete(txn)
        onDataChanged()
    }

    val recentlyDeleted: Flow<List<DeletedTxnEntity>> = v13.observeTrash()

    /** Puts a deleted transaction back where it was. Returns false when it was not in the bin. */
    suspend fun restore(uid: String): Boolean = withContext(Dispatchers.IO) {
        val t = v13.trashed(uid) ?: return@withContext false
        restoreRow(t).also { onDataChanged() }
    }

    private suspend fun restoreRow(t: DeletedTxnEntity): Boolean {
        val row = t.toEntity()
        // Same id first, so links from receipts, splits and tax tags (by uid) keep working either way.
        val ok = db.transactions().insert(row) != -1L || db.transactions().insert(row.copy(id = 0)) != -1L
        v13.removeTrash(t.uid)
        v13.removeKey(t.uid)
        return ok
    }

    // ---- Labels and hiding ----

    /**
     * Sets a transaction's labels and whether it is hidden from lists. When [forMerchant] is set, the labels
     * are also added to every other transaction from the same merchant, and to new ones as they arrive.
     */
    suspend fun setLabels(txn: TransactionEntity, labels: List<String>, hidden: Boolean, forMerchant: Boolean) = withContext(Dispatchers.IO) {
        val clean = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        v13.clearLabels(txn.uid)
        if (clean.isNotEmpty()) v13.addLabels(clean.map { TxnLabelEntity(txn.uid, it) })
        if (hidden) v13.hideTxn(HiddenTxnEntity(txn.uid)) else v13.unhideTxn(txn.uid)
        val merchant = txn.merchant
        if (forMerchant && merchant != null && clean.isNotEmpty()) {
            val key = Categorizer.merchantKey(merchant)
            v13.addLabelRules(clean.map { LabelRuleEntity(key, it) })
            // The same seller under a slightly different name too, e.g. "IRCTC REFUND" for "IRCTC".
            val same = db.transactions().allOnce().filter { it.merchant != null && com.myfinance.core.Labels.sameSeller(merchant, it.merchant) }
            v13.addLabels(same.flatMap { t -> clean.map { TxnLabelEntity(t.uid, it) } })
        }
    }

    private suspend fun applyLabelRules(row: TransactionEntity, rawMerchant: String? = null) {
        val names = listOfNotNull(row.merchant, rawMerchant)
        if (names.isEmpty()) return
        val labels = v13.labelRules().filter { r -> names.any { com.myfinance.core.Labels.sameSeller(r.merchantKey, it) } }.map { it.label }.distinct()
        if (labels.isNotEmpty()) v13.addLabels(labels.map { TxnLabelEntity(row.uid, it) })
    }

    private suspend fun payeeName(merchant: String): String? {
        val key = Categorizer.merchantKey(merchant)
        return v13.payeeNames().firstOrNull { it.merchantKey == key }?.name
    }

    /**
     * Gives the payee of [txn] a friendly name and uses it on every transaction from them, past and future.
     * Returns how many transactions were renamed.
     */
    suspend fun renamePayee(txn: TransactionEntity, newName: String): Int = withContext(Dispatchers.IO) {
        val name = newName.trim()
        val old = txn.merchant ?: return@withContext 0
        if (name.isEmpty()) return@withContext 0
        val names = v13.payeeNames()
        // The payee's original key, plus any raw keys already mapped to the name it has now.
        val keys = (names.filter { it.name == old }.map { it.merchantKey } + Categorizer.merchantKey(old)).toSet()
        v13.putPayeeNames(keys.map { PayeeNameEntity(it, name) })
        val rows = db.transactions().allOnce().filter { it.merchant != null && Categorizer.merchantKey(it.merchant) in keys && it.merchant != name }
        if (rows.isNotEmpty()) db.transactions().updateAll(rows.map { it.copy(merchant = name) })
        onDataChanged()
        rows.size
    }

    suspend fun labelsOf(uid: String): List<String> = v13.labelsOf(uid)

    /** Nicknames by transaction uid. */
    val nicknames: Flow<Map<String, String>> = v17.observeNicknames().map { l -> l.associate { it.txnUid to it.name } }

    suspend fun nicknameOf(uid: String): String? = withContext(Dispatchers.IO) { runCatching { v17.nicknameOf(uid) }.getOrNull() }

    /** Sets (or with a blank name, clears) the user's own name for one payment. */
    suspend fun setNickname(uid: String, name: String?) = withContext(Dispatchers.IO) {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) v17.clearNickname(uid) else v17.putNickname(NicknameEntity(uid, n))
    }

    /** Renames a payee name everywhere: the saved name for new SMS and every transaction already using it. */
    suspend fun renamePayeeName(old: String, new: String) = withContext(Dispatchers.IO) {
        val name = new.trim()
        if (name.isEmpty() || name == old) return@withContext
        v13.putPayeeNames(v13.payeeNames().filter { it.name == old }.map { it.copy(name = name) })
        val rows = db.transactions().allOnce().filter { it.merchant == old }
        if (rows.isNotEmpty()) db.transactions().updateAll(rows.map { it.copy(merchant = name) })
        onDataChanged()
    }

    /** Stops naming new SMS with [name]. Transactions already renamed keep the name (edit them to change it). */
    suspend fun forgetPayeeName(name: String) = v13.deletePayeeName(name)

    suspend fun renameLabel(from: String, to: String) = withContext(Dispatchers.IO) {
        val t = to.trim()
        if (t.isEmpty() || t == from) return@withContext
        v13.renameLabel(from, t)
        v13.deleteLabel(from) // rows that already had the new label
        val rules = v13.labelRules().filter { it.label == from }
        v13.deleteLabelRules(from)
        v13.addLabelRules(rules.map { it.copy(label = t) })
    }

    suspend fun deleteLabel(label: String) = withContext(Dispatchers.IO) {
        v13.deleteLabel(label)
        v13.deleteLabelRules(label)
    }

    /** Removes it from the bin for good. It still won't come back on a rescan. */
    suspend fun deleteForever(uid: String) = v13.removeTrash(uid)

    suspend fun emptyTrash() = v13.emptyTrash()

    /** The bin keeps rows for 60 days. */
    suspend fun purgeOldTrash() = v13.purgeTrashBefore(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000)

    // ---- Family export / merge / Drive sync ----

    private suspend fun modeMap(): Map<String, String> = extras.modes().associate { it.uid to it.mode }

    private suspend fun exportCsv(): Pair<String, Int> {
        val me = ownerName.value
        val modes = modeMap()
        val labels = v13.allLabels().groupBy({ it.uid }, { it.label })
        val rows = db.transactions().allOnce().map { it.toShared(me, modes).copy(labels = labels[it.uid].orEmpty().sorted()) }
        return CsvCodec.encode(rows) to rows.size
    }

    /** Writes every transaction (yours and merged ones) to a CSV file the family can share. */
    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val (csv, n) = exportCsv()
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(csv.toByteArray(Charsets.UTF_8))
        } ?: error("Could not open file for writing")
        prefs.edit().putLong(KEY_LAST_EXPORT, System.currentTimeMillis()).apply()
        n
    }

    /**
     * Writes the export to a private cache file and returns a content link to it, so it can be sent
     * through WhatsApp (or any app in the share sheet) without saving it anywhere first.
     */
    suspend fun exportForShare(): Pair<Uri, Int> = withContext(Dispatchers.IO) {
        val (csv, n) = exportCsv()
        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val who = ownerName.value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "family" }
        val file = java.io.File(dir, "myfinance-$who-${LocalDate.now()}.csv")
        file.writeText(csv, Charsets.UTF_8)
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file) to n
    }

    data class MergeResult(
        val added: Int,
        val duplicates: Int,
        val unreadable: Int,
        /** Whose entries the file held, e.g. "Priya". */
        val from: List<String> = emptyList(),
    )

    /** Merges a file opened from WhatsApp or a file manager, refusing anything that is not a MyFinance export. */
    suspend fun mergeShared(uri: Uri): MergeResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Could not open the file")
        require(CsvCodec.isExport(text)) { "This is not a MyFinance export file." }
        mergeText(text).also { onDataChanged() }
    }

    /**
     * Merges another phone's export. Rows that already exist here, such as a joint-account payment
     * whose SMS reached both phones, are skipped.
     */
    suspend fun mergeFrom(uri: Uri): MergeResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Could not open file")
        mergeText(text).also { onDataChanged() }
    }

    private suspend fun mergeText(text: String): MergeResult {
        val (incoming, bad) = CsvCodec.decode(text)
        val me = ownerName.value
        val existing = db.transactions().allOnce().map { it.toShared(me) }
        // Rows deleted on this phone stay deleted, even if another phone still has them.
        val deleted = v13.keys()
        val deletedUids = deleted.mapTo(HashSet()) { it.uid }
        val deletedSms = deleted.mapNotNullTo(HashSet()) { it.smsKey }
        val fresh = Dedupe.newOnly(existing, incoming).filter { it.uid !in deletedUids && (it.smsKey == null || it.smsKey !in deletedSms) }
        val known = db.categories().names().toMutableSet()
        var added = 0
        val modes = ArrayList<TxnModeEntity>()
        for (t in fresh) {
            // Rows exported from this phone come back labelled with our own name; store them unlabelled.
            val row = t.toEntity().let { if (it.owner == me) it.copy(owner = null) else it }
            if (db.transactions().insert(row) != -1L) {
                added++
                t.mode?.let { modes += TxnModeEntity(t.uid, it) }
            }
            if (t.category !in known) {
                addCategory(t.category, null)
                known += t.category
            }
        }
        if (modes.isNotEmpty()) extras.upsertModes(modes)
        // Labels travel with the rows (by uid), so each person's labels show in the family label view.
        val localUids = db.transactions().allOnce().mapTo(HashSet()) { it.uid }
        val incomingLabels = incoming.filter { it.labels.isNotEmpty() && it.uid in localUids && it.uid !in deletedUids }
            .flatMap { t -> t.labels.map { TxnLabelEntity(t.uid, it) } }
        if (incomingLabels.isNotEmpty()) v13.addLabels(incomingLabels)
        val from = CsvCodec.owners(incoming).filter { it != me }
        return MergeResult(added = added, duplicates = incoming.size - added, unreadable = bad, from = from)
    }

    private val _syncUri = MutableStateFlow(prefs.getString(KEY_SYNC_URI, null))

    /** The shared family file (usually in Google Drive) this phone syncs with. */
    val syncUri: StateFlow<String?> = _syncUri.asStateFlow()

    private val _lastSync = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 })
    val lastSync: StateFlow<Long?> = _lastSync.asStateFlow()

    private val _autoSync = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SYNC, true))

    /** Sync with the shared file when the app opens and a few times a day in the background. */
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()

    fun setAutoSync(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, on).apply()
        _autoSync.value = on
    }

    /** Remembers the shared file, keeping access to it after restarts. */
    fun linkSyncFile(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers do not offer lasting access; syncing still works until the app restarts.
        }
        prefs.edit().putString(KEY_SYNC_URI, uri.toString()).apply()
        _syncUri.value = uri.toString()
    }

    fun unlinkSyncFile() {
        _syncUri.value?.let { s ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(s),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit().remove(KEY_SYNC_URI).apply()
        _syncUri.value = null
    }

    /**
     * Two-way sync with the shared family file: merges in what the other phones wrote, then writes
     * back everything this phone has, so after each phone syncs the file holds the whole family.
     */
    suspend fun syncNow(): MergeResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(_syncUri.value ?: error("Choose the shared family file first"))
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        val result = if (text.isBlank()) MergeResult(0, 0, 0) else mergeText(text)
        val (csv, _) = exportCsv()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not write the shared file")
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
        _lastSync.value = now
        onDataChanged()
        result
    }

    // ---- Statements ----

    /** Writes the given transactions as a readable statement: Excel (.xlsx) or CSV. */
    suspend fun exportStatement(uri: Uri, txns: List<TransactionEntity>, excel: Boolean): Int = withContext(Dispatchers.IO) {
        val me = ownerName.value
        val modes = modeMap()
        val rows = StatementExport.rows(txns.map { it.toLite(me, modes) to it.note })
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            if (excel) {
                XlsxWriter.write(out, "MyFinance", StatementExport.header, rows)
            } else {
                out.write(CsvCodec.encodeTable(StatementExport.header, rows).toByteArray(Charsets.UTF_8))
            }
        } ?: error("Could not open file for writing")
        rows.size
    }

    // ---- Budgets, reminders, widget ----

    data class MonthTotals(val incomeMinor: Long, val expenseMinor: Long, val investedMinor: Long = 0)

    suspend fun monthTotals(month: YearMonth = monthCycle.value.current()): MonthTotals {
        val list = monthRows(month).filterNot { it.excluded }
        return MonthTotals(
            incomeMinor = list.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
            expenseMinor = list.filter { it.isSpend }.sumOf { it.amountMinor },
            investedMinor = list.filter { it.isInvestment }.sumOf { it.amountMinor },
        )
    }

    /** For the Today widget: what was spent today, and the next EMI, auto debit or bill due (name, amount, date). */
    suspend fun todayGlance(): Pair<Long, Triple<String, Long?, LocalDate>?> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val spent = db.transactions().rangeOnce(start, System.currentTimeMillis())
            .filter { !it.excluded && it.isSpend }.sumOf { it.amountMinor }
        val me = ownerName.value
        val recentFrom = today.minusDays(60).atStartOfDay(zone).toInstant().toEpochMilli()
        val debits = db.transactions().rangeOnce(recentFrom, System.currentTimeMillis())
            .filter { !it.excluded && it.type == TxnType.DEBIT }
            .map { com.myfinance.core.DebitRecord(it.toLite(me), it.rawSms) }
        val notRecurring = v13.observeHidden().first().map { it.key }
            .filter { it.startsWith(com.myfinance.core.AutoDebits.NOT_RECURRING_PREFIX) }
            .mapTo(HashSet()) { it.removePrefix(com.myfinance.core.AutoDebits.NOT_RECURRING_PREFIX) }
        val autos = com.myfinance.core.AutoDebits.build(
            plans = v13.observePlans().first().map { it.toPlan() }, debits = debits, recurring = emptyList(), emiBills = emptyList(),
            hidden = emptySet(), today = today, notRecurring = notRecurring,
        ).filter { !it.nextDate.isBefore(today) }.map { Triple(it.name, it.amountMinor as Long?, it.nextDate) }
        val bills = v12.dao.bills().map { it.toBill() }.filter { !it.finished && !it.nextDue.isBefore(today) }.map { Triple(it.name, it.amountMinor, it.nextDue) }
        spent to (autos + bills).minByOrNull { it.third }
    }

    /**
     * EMIs the user added (an EMI in Auto debits & EMIs, or a loan EMI payment reminder) without an end date take
     * it from the card statements that print each instalment ("<4/6>"), so they stop once the last one is paid.
     * Only fills empty end dates, once each: one the user set or cleared by hand is theirs.
     */
    suspend fun fillEmiEndDates() = withContext(Dispatchers.IO) {
        // Each one is filled once: if the user clears it afterwards, it stays cleared.
        val filled = prefs.getStringSet(KEY_EMI_END_FILLED, emptySet()).orEmpty()
        val plans = v13.observePlans().first().filter { it.endEpochDay == null && it.id !in filled }
        val bills = v12.dao.bills().filter { it.endEpochDay == null && "bill:${it.id}" !in filled }
        if (plans.none { it.kind == com.myfinance.core.AutoDebitKind.EMI.name } &&
            bills.none { it.kind == com.myfinance.core.ReminderKind.LOAN_EMI.name && it.everyMonths != 0 }) return@withContext
        val me = ownerName.value
        val debits = db.transactions().allOnce()
            .filter { !it.excluded && it.type == TxnType.DEBIT && (com.myfinance.core.AutoDebits.instalmentOf(it.rawSms) != null || com.myfinance.core.AutoDebits.instalmentOf(it.merchant) != null) }
            .map { com.myfinance.core.DebitRecord(it.toLite(me), it.rawSms) }
        if (debits.isEmpty()) return@withContext
        val emis = com.myfinance.core.AutoDebits.instalmentEmis(debits, LocalDate.now())
        if (emis.isEmpty()) return@withContext
        val done = HashSet(filled)
        val planEnds = com.myfinance.core.RepeatEnds.forPlans(plans.map { it.toPlan() }, emis)
        for (p in plans) planEnds[p.id]?.let { v13.upsertPlan(p.copy(endEpochDay = it.toEpochDay())); done += p.id }
        val billEnds = com.myfinance.core.RepeatEnds.forBills(bills.map { it.toBill() }, emis)
        for (b in bills) billEnds[b.id]?.let { v12.dao.upsertBill(b.copy(endEpochDay = it.toEpochDay())); done += "bill:${b.id}" }
        if (done.size != filled.size) prefs.edit().putStringSet(KEY_EMI_END_FILLED, done).apply()
    }

    /** Sum of all monthly category budgets (0 when none are set). */
    suspend fun totalBudget(): Long = db.categories().allOnce().sumOf { it.monthlyBudgetMinor ?: 0L }

    /** Rows that count towards the budget month [month], using the month setting. */
    private suspend fun monthRows(month: YearMonth): List<TransactionEntity> {
        val zone = ZoneId.systemDefault()
        val cycle = monthCycle.value
        val range = cycle.loadRange(month, zone)
        return db.transactions().rangeOnce(range.first, range.last)
            .filter { cycle.periodOf(it.timestamp, it.type, it.category, zone) == month }
    }

    /** Refreshes everything that depends on the totals: budget alerts and the home screen widget. */
    suspend fun onDataChanged() {
        runCatching { checkBudgets() }
        runCatching { SpendWidget.refresh(context) }
        runCatching { com.myfinance.app.widget.TodayWidget.refresh(context) }
    }

    /** Sends a notification the first time a category passes 80% and 100% of its budget each month. */
    suspend fun checkBudgets() {
        val month = monthCycle.value.current()
        val categoryBudgets = db.categories().allOnce().mapNotNull { c -> c.monthlyBudgetMinor?.let { c.name to it } }.toMap()
        // Label budgets share the alerts, named "#Vegetables" so they never clash with a category.
        val labelBudgets = v14.labelBudgets().associate { "#${it.label}" to it.monthlyMinor }
        val budgets = categoryBudgets + labelBudgets
        if (budgets.isEmpty()) return
        val rows = monthRows(month).filter { !it.excluded }
        val spent = HashMap<String, Long>()
        rows.filter { it.type == TxnType.DEBIT }.groupBy { it.category }.forEach { (k, v) -> spent[k] = v.sumOf { it.amountMinor } }
        if (labelBudgets.isNotEmpty()) {
            val labels = v13.allLabels().groupBy({ it.uid }, { it.label })
            for (t in rows) for (l in labels[t.uid].orEmpty().distinct()) {
                // Refunds from the same label come off its spending.
                spent.merge("#$l", if (t.type == TxnType.DEBIT) t.amountMinor else -t.amountMinor, Long::plus)
            }
        }
        val sent = prefs.getStringSet(KEY_BUDGET_SENT, emptySet()).orEmpty()
        // Spending pace: e.g. 70% of the month's budget gone by day 15. Once a month, for the total and each category.
        val paceKeys = ArrayList<String>()
        val cycle = monthCycle.value
        val today = LocalDate.now()
        val paces = buildList {
            if (categoryBudgets.isNotEmpty()) add("Total" to com.myfinance.core.SpendPaces.of(rows.filter { it.isSpend }.sumOf { it.amountMinor }, categoryBudgets.filterKeys { !com.myfinance.core.Spending.isInvestmentCategory(it) }.values.sum(), today, cycle.start(month), cycle.end(month)))
            categoryBudgets.forEach { (c, b) -> add(c to com.myfinance.core.SpendPaces.of(spent[c] ?: 0, b, today, cycle.start(month), cycle.end(month))) }
        }
        for ((name, p) in paces) {
            if (p.state != com.myfinance.core.PaceState.AHEAD) continue
            val key = "$month|pace|$name"
            if (key in sent) continue
            val what = if (name == "Total") "your monthly budget" else "the $name budget"
            Notifier.reminder(
                context, key.hashCode(), "Spending fast: ${p.spentPercent}% by day ${p.day}",
                "You've used ${p.spentPercent}% of $what with ${p.daysInPeriod - p.day} days to go. At this pace: ${formatInr(p.projectedMinor)} of ${formatInr(p.budgetMinor)}.",
            )
            paceKeys += key
        }
        val due = BudgetAlerts.due(spent, budgets, sent, month)
        if (paceKeys.isNotEmpty()) prefs.edit().putStringSet(KEY_BUDGET_SENT, (sent + paceKeys).toSet()).apply()
        if (due.isEmpty()) return
        due.forEach { Notifier.budget(context, it) }
        // Keep only this month's keys so the set does not grow forever.
        val keep = sent.filter { it.startsWith("$month|") } + paceKeys + due.flatMap { BudgetAlerts.keysToRecord(it) }
        prefs.edit().putStringSet(KEY_BUDGET_SENT, keep.toSet()).apply()
    }

    /** Bills not yet paid, one per card / loan and due date, soonest first. */
    suspend fun openBills(today: LocalDate = LocalDate.now()): List<BillEntity> {
        val candidates = extras.billsFrom(today.minusDays(10).toEpochDay()).filter { !it.markedPaid }
        if (candidates.isEmpty()) return emptyList()
        val me = ownerName.value
        val txns = db.transactions().rangeOnce(
            today.minusDays(60).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            Long.MAX_VALUE,
        ).map { it.toLite(me) }
        return dedupeBills(candidates).filter { !BillParser.isPaid(it.toBill(), txns) }
    }

    suspend fun markBillPaid(id: Long, paid: Boolean = true) = extras.setBillPaid(id, paid)

    suspend fun deleteBill(id: Long) = extras.deleteBill(id)

    /**
     * Daily check (from [com.myfinance.app.notify.ReminderWorker]): bills due within 3 days and
     * recurring payments due within 2 days, each notified once.
     */
    suspend fun checkReminders(today: LocalDate = LocalDate.now()) {
        val sent = prefs.getStringSet(KEY_REMINDERS_SENT, emptySet()).orEmpty().toMutableSet()
        val newKeys = ArrayList<String>()

        for (b in openBills(today)) {
            val due = LocalDate.ofEpochDay(b.dueEpochDay)
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
            if (days !in 0..3) continue
            val key = "bill|${b.kind}|${b.accountTail}|${b.dueEpochDay}"
            if (key in sent) continue
            val bill = b.toBill()
            val whenText = when (days) { 0L -> "today"; 1L -> "tomorrow"; else -> "in $days days" }
            val min = bill.minDueMinor?.let { " (minimum ${formatInr(it)})" }.orEmpty()
            Notifier.reminder(context, key.hashCode(), "${bill.title} due $whenText", "${formatInr(bill.amountMinor)}$min due on ${due.format(dayFormat)}.")
            newKeys += key
        }

        val me = ownerName.value
        val all = db.transactions().allOnce().filterNot { it.excluded }.map { it.toLite(me) }
        for (r in RecurringDetector.detect(all, today)) {
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, r.nextDate)
            if (days !in 0..2) continue
            val key = "rec|${r.key}|${r.nextDate}"
            if (key in sent) continue
            val whenText = when (days) { 0L -> "today"; 1L -> "tomorrow"; else -> "in $days days" }
            Notifier.reminder(context, key.hashCode(), "${r.merchant} due $whenText", "${r.cadence.label} payment of about ${formatInr(r.amountMinor)} expected on ${r.nextDate.format(dayFormat)}.")
            newKeys += key
        }

        runCatching { checkStatementReminder(today) }
        runCatching { newKeys += checkCardBills(today, sent) }
        runCatching { newKeys += checkRefunds(today, sent) }
        runCatching { sendWeeklyDigest(today, all) }
        runCatching { checkBackupReminder() }

        if (newKeys.isNotEmpty()) {
            // Forget keys older than ~2 months.
            val cutoff = today.minusDays(60)
            val kept = sent.filter { k -> k.substringAfterLast('|').let { d -> parseKeyDate(d)?.isAfter(cutoff) ?: false } }
            prefs.edit().putStringSet(KEY_REMINDERS_SENT, (kept + newKeys).toSet()).apply()
        }
    }

    // ---- 1.9: weekly digest ----

    val weeklyDigestOn: Boolean get() = prefs.getBoolean(KEY_WEEKLY_DIGEST, true)

    fun setWeeklyDigest(on: Boolean) { prefs.edit().putBoolean(KEY_WEEKLY_DIGEST, on).apply() }

    /** Once a week (from Monday): last week's spending against the week before, and what is due this week. */
    private suspend fun sendWeeklyDigest(today: LocalDate, all: List<com.myfinance.core.TxnLite>) {
        if (!weeklyDigestOn) return
        val week = com.myfinance.core.Weekly.lastWeekStart(today).toEpochDay()
        if (prefs.getLong(KEY_WEEKLY_SENT, 0L) >= week) return
        if (all.none { it.type == TxnType.DEBIT }) return
        val due = ArrayList<Triple<String, Long?, LocalDate>>()
        runCatching {
            v12.dao.bills().map { it.toBill() }.filterNot { it.done }.forEach { due += Triple(it.name, it.amountMinor, it.nextDue) }
        }
        openBills(today).forEach { b -> due += Triple(b.toBill().title, b.amountMinor, LocalDate.ofEpochDay(b.dueEpochDay)) }
        val d = com.myfinance.core.Weekly.digest(all, today, due)
        Notifier.reminder(context, "weekly|$week".hashCode(), d.title, d.body.ifBlank { "Open MyFinance for the details." })
        prefs.edit().putLong(KEY_WEEKLY_SENT, week).apply()
    }

    // ---- v1.4: statement reminders, refunds, card statement bills ----

    private val _statementReminderDay = MutableStateFlow(prefs.getInt(KEY_STMT_DAY, 5))

    /** Day of the month for the "import your statements" nudge; 0 = off. */
    val statementReminderDay: StateFlow<Int> = _statementReminderDay.asStateFlow()

    fun setStatementReminderDay(day: Int) {
        val d = day.coerceIn(0, 28)
        prefs.edit().putInt(KEY_STMT_DAY, d).apply()
        _statementReminderDay.value = d
    }

    private val _refundDays = MutableStateFlow(prefs.getInt(KEY_REFUND_DAYS, 10))

    /** A refund not back after this many days is flagged. */
    val refundOverdueDays: StateFlow<Int> = _refundDays.asStateFlow()

    fun setRefundOverdueDays(days: Int) {
        val d = days.coerceIn(1, 90)
        prefs.edit().putInt(KEY_REFUND_DAYS, d).apply()
        _refundDays.value = d
    }

    /** Accounts and cards whose latest imported statement is older than last month. */
    suspend fun statementGaps(today: LocalDate = LocalDate.now()): List<com.myfinance.core.StatementGap> {
        val me = ownerName.value
        val rows = db.transactions().allOnce().filter { it.source == TxnSource.STATEMENT && it.owner == null }.map { it.toLite(me) }
        return com.myfinance.core.StatementReminders.gaps(rows, today)
    }

    private suspend fun checkStatementReminder(today: LocalDate) {
        val day = _statementReminderDay.value
        val last = prefs.getString(KEY_STMT_SENT, null)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        if (!com.myfinance.core.StatementReminders.isDue(today, day, last)) return
        val hasImported = db.transactions().allOnce().any { it.source == TxnSource.STATEMENT && it.owner == null }
        if (!hasImported) return
        val gaps = statementGaps(today)
        prefs.edit().putString(KEY_STMT_SENT, YearMonth.from(today).toString()).apply()
        if (gaps.isEmpty()) return
        val (title, text) = com.myfinance.core.StatementReminders.message(gaps, today)
        Notifier.reminder(context, "stmt|${YearMonth.from(today)}".hashCode(), title, text)
    }

    /** Notifies once for each refund that turns overdue. Returns the keys it sent. */
    private suspend fun checkRefunds(today: LocalDate, sent: Set<String>): List<String> {
        val watches = v14.observeRefunds().first()
        if (watches.isEmpty()) return emptyList()
        val me = ownerName.value
        val all = db.transactions().allOnce()
        val idOf = all.associate { it.uid to it.id }
        val lite = all.filterNot { it.excluded }.map { it.toLite(me) }
        val items = com.myfinance.core.Refunds.track(
            watches.mapNotNull { w -> idOf[w.uid]?.let { com.myfinance.core.RefundWatch(it, w.expectedMinor, w.since, w.closed) } },
            lite, today, _refundDays.value,
        )
        val out = ArrayList<String>()
        for (i in items.filter { it.state == com.myfinance.core.RefundState.OVERDUE }) {
            val key = "refund|${i.payment.id}|${today.toEpochDay()}"
            if (sent.any { it.startsWith("refund|${i.payment.id}|") }) continue
            Notifier.reminder(
                context, key.hashCode(), "Refund from ${i.payment.merchant ?: "seller"} not received",
                "${formatInr(i.pendingMinor)} still to come back after ${i.daysWaiting} days. Check the refund status with the seller.",
            )
            out += key
        }
        return out
    }

    /** Saves the bill printed on an imported credit card statement, for the card bill check. */
    suspend fun addStatementBill(bank: String?, tail: String?, totalMinor: Long, minMinor: Long?, due: LocalDate) {
        if (tail == null || totalMinor <= 0) return
        extras.insertBill(
            BillEntity(
                smsKey = "STMTBILL|$tail|${due.toEpochDay()}", kind = com.myfinance.core.BillKind.CREDIT_CARD.name,
                amountMinor = totalMinor, minDueMinor = minMinor, dueEpochDay = due.toEpochDay(), accountTail = tail, bank = bank,
                receivedAt = due.minusDays(20).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ),
        )
    }

    private fun parseKeyDate(s: String): LocalDate? =
        s.toLongOrNull()?.let { LocalDate.ofEpochDay(it) } ?: runCatching { LocalDate.parse(s) }.getOrNull()

    // ---- Categories ----

    suspend fun addCategory(name: String, budgetMinor: Long?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (budgetMinor == null && trimmed in db.categories().names()) return
        db.categories().upsert(
            CategoryEntity(
                name = trimmed,
                builtIn = DefaultCategories.all.contains(trimmed),
                color = palette[(trimmed.hashCode() and 0x7fffffff) % palette.size],
                monthlyBudgetMinor = budgetMinor,
            ),
        )
        onDataChanged()
    }

    suspend fun updateCategory(category: CategoryEntity) {
        db.categories().upsert(category)
        onDataChanged()
    }

    /** Deletes a custom category; its transactions and rules move to "Uncategorized". */
    suspend fun deleteCategory(name: String) {
        db.transactions().renameCategory(name, DefaultCategories.OTHER)
        db.merchantRules().renameCategory(name, DefaultCategories.OTHER)
        db.categories().deleteCustom(name)
    }

    // ---- Bank / card statement import ----

    /** Every stored transaction, in the shape the statement matcher compares against. */
    suspend fun existingForImport(): List<ExistingTxn> = withContext(Dispatchers.IO) {
        val stored = db.transactions().allOnce().map {
            ExistingTxn(it.amountMinor, it.type, it.timestamp, it.accountTail, it.reference, it.smsKey, it.id, it.source == TxnSource.STATEMENT, it.rawSms)
        }
        // Statement lines the user deleted count as already imported, so they stay deleted.
        val deleted = v13.keys().mapNotNull { k -> k.smsKey?.takeIf { it.startsWith("STMT|") } }
            .map { ExistingTxn(-1, TxnType.DEBIT, 0, null, null, it) }
        stored + deleted
    }

    data class ImportResult(val added: Int, val updated: Int, val skippedClosed: Int = 0, val addedUids: List<String> = emptyList())

    /**
     * Stores the statement lines the user chose to import. New lines are categorised like SMS (custom
     * rules, payee names and label rules apply). A line that is the same payment as a stored SMS or
     * typed entry updates that entry instead: the statement is the official record, so its amount,
     * date, merchant name and details win, while the user's category, labels and notes are kept. A name
     * the user gave (a payee name or an edited merchant) moves to the payment's nickname.
     */
    suspend fun addImported(
        rows: List<ImportCandidate>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        val cat = categorizer()
        val deletedUids = v13.keys().mapTo(HashSet()) { it.uid }
        // Read once, not once per line: years of statements are thousands of lines.
        val payees = runCatching { v13.payeeNames() }.getOrDefault(emptyList())
        val nicknames = payees.mapTo(HashSet()) { it.name }
        val payeeByKey = payees.associate { it.merchantKey to it.name }
        val labelRules = runCatching { v13.labelRules() }.getOrDefault(emptyList())
        var added = 0
        var updated = 0
        var skippedClosed = 0
        val closed = closedMonths()
        val zone = ZoneId.systemDefault()
        val accounts = myAccountsNow()
        val me = ownerName.value
        // Only recent payments can be the one a payment reminder is waiting for.
        val recentFrom = System.currentTimeMillis() - 45L * 24 * 60 * 60 * 1000
        fun ownerOf(tail: String?, bank: String?) = com.myfinance.core.MyAccounts.ownerOf(accounts, tail, bank)?.takeIf { it.isNotBlank() && it != me }
        fun nickFor(merchant: String?) = merchant?.let { payeeByKey[Categorizer.merchantKey(it)] }

        val addedUids = ArrayList<String>()
        val total = rows.size
        var done = 0
        onProgress(0, total)
        // In chunks, each in one database transaction: the screens refresh once per chunk, not once per line.
        for (chunk in rows.chunked(IMPORT_CHUNK)) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val nicks = ArrayList<NicknameEntity>()
            val labels = ArrayList<TxnLabelEntity>()
            val newRows = ArrayList<Pair<TransactionEntity, com.myfinance.core.RuleOutcome?>>()
            db.withTransaction {
                for (c in chunk) {
                    val r = c.row
                    // Closed months stay as they were reviewed.
                    if (closed.isNotEmpty() && monthCycle.value.periodOf(c.timestamp, r.type, DefaultCategories.OTHER, zone) in closed) { skippedClosed++; continue }
                    val nick = nickFor(c.merchant)
                    if (c.updatesExisting) {
                        val t = db.transactions().get(c.matchedId!!) ?: continue
                        val official = c.merchant ?: t.merchant
                        // The statement's merchant replaces the SMS one; the user's own name for it becomes the nickname.
                        val userName = t.merchant?.takeIf { (t.userEdited || it in nicknames) && it != official }
                        (userName ?: nick?.takeIf { it != official })?.let { nicks += NicknameEntity(t.uid, it) }
                        db.transactions().update(
                            t.copy(
                                amountMinor = r.amountMinor, type = r.type, timestamp = statementTime(r.date, t.timestamp),
                                merchant = official,
                                accountTail = StatementImport.keptTail(c.accountTail, t.accountTail, t.rawSms), bank = c.bank ?: t.bank,
                                owner = ownerOf(StatementImport.keptTail(c.accountTail, t.accountTail, t.rawSms), c.bank ?: t.bank) ?: t.owner,
                                reference = t.reference ?: r.reference, balanceMinor = r.balanceMinor ?: t.balanceMinor,
                                source = TxnSource.STATEMENT, rawSms = statementText(r.description, t.rawSms),
                                excluded = t.excluded || (!t.userEdited && (c.ownTransfer || com.myfinance.core.SelfTransfers.isCardBillPayment(r.type, official, r.description, c.cardStatement))),
                            ),
                        )
                        updated++
                        continue
                    }
                    if (c.duplicateOf == com.myfinance.core.DuplicateReason.SAME_STATEMENT && r.reference != null) {
                        // Imported before without its transaction ID (card statement reference number): add it now,
                        // so copies of one line can be told from two real payments.
                        db.transactions().bySmsKey(c.key)?.takeIf { it.reference == null }?.let { db.transactions().update(it.copy(reference = r.reference)) }
                    }
                    if (c.duplicateOf != null || c.uid in deletedUids) continue
                    val rule = v12.ruleFor(null, r.description, c.merchant, r.type, r.amountMinor)
                    if (rule?.ignore == true) continue
                    // A card bill payment (bank side or card side) is not spending or income: the card's purchases are.
                    // So is money moved between two of the user's own accounts.
                    val billPayment = c.ownTransfer || com.myfinance.core.SelfTransfers.isCardBillPayment(r.type, c.merchant, r.description, c.cardStatement)
                    val base = TransactionEntity(
                        excluded = billPayment,
                        amountMinor = r.amountMinor, type = r.type, merchant = c.merchant,
                        category = if (billPayment) DefaultCategories.TRANSFERS else cat.categorize(c.merchant, r.type, r.description),
                        accountTail = c.accountTail, bank = c.bank, timestamp = c.timestamp, source = TxnSource.STATEMENT,
                        smsKey = c.key, rawSms = r.description, reference = r.reference, balanceMinor = r.balanceMinor, uid = c.uid,
                        owner = ownerOf(c.accountTail, c.bank),
                    )
                    // The statement's merchant name stays; a payee name the user gave becomes the nickname.
                    val row = rule?.applyTo(base) ?: base
                    if (db.transactions().insert(row) != -1L) {
                        added++
                        addedUids += row.uid
                        if (nick != null && rule?.merchant == null && nick != row.merchant) nicks += NicknameEntity(row.uid, nick)
                        val names = listOfNotNull(row.merchant, c.merchant)
                        if (names.isNotEmpty()) labelRules.filter { lr -> names.any { com.myfinance.core.Labels.sameSeller(lr.merchantKey, it) } }
                            .map { it.label }.distinct().forEach { labels += TxnLabelEntity(row.uid, it) }
                        newRows += row to rule
                    }
                }
            }
            // Other databases: written once per chunk.
            runCatching { for (n in nicks) if (v17.nicknameOf(n.txnUid) == null) v17.putNickname(n) }
            if (labels.isNotEmpty()) runCatching { v13.addLabels(labels) }
            for ((row, rule) in newRows) {
                if (row.timestamp >= recentFrom || rule?.taxSection != null) runCatching { v12.onNewTransaction(row, rule) }
            }
            done += chunk.size
            onProgress(done, total)
        }
        runCatching { leaveOutCardPaymentPairs() }
        runCatching { leaveOutReversals() }
        runCatching { fillEmiEndDates() }
        runCatching { removeSameIdDuplicates() }
        onDataChanged()
        ImportResult(added, updated, skippedClosed, addedUids)
    }

    // ---- 1.10: import history and undo ----

    val importBatches: Flow<List<ImportBatchEntity>> get() = v18.observeBatches()

    suspend fun recordImport(files: List<String>, result: ImportResult, from: LocalDate?, to: LocalDate?) = withContext(Dispatchers.IO) {
        runCatching {
            v18.putBatch(
                ImportBatchEntity(
                    at = System.currentTimeMillis(), files = files.joinToString("\n"), added = result.added, updated = result.updated,
                    uids = result.addedUids.joinToString("\n"), fromDay = from?.toEpochDay() ?: 0, toDay = to?.toEpochDay() ?: 0,
                ),
            )
        }
    }

    /**
     * Takes out every entry a statement import added, so the same files can be imported again cleanly.
     * Entries it updated (an SMS that took the statement's details) stay. Returns how many were removed.
     */
    suspend fun undoImport(batchId: Long): Int = withContext(Dispatchers.IO) {
        val b = v18.batch(batchId) ?: return@withContext 0
        if (b.undone) return@withContext 0
        val uids = b.uids.split('\n').filter { it.isNotBlank() }
        var removed = 0
        db.withTransaction { for (chunk in uids.chunked(500)) removed += db.transactions().deleteByUids(chunk) }
        v18.putBatch(b.copy(undone = true))
        onDataChanged()
        removed
    }

    /**
     * "Keep only SMS" for a period (1.14): statement lines dated [from]..[to] go. A line that only came from a
     * statement moves to Recently deleted (not remembered, so a later statement can bring it back); a line joined
     * with its SMS goes back to being the SMS entry (the SMS's amount, card, payee and time), keeping the user's
     * category, note and labels. Returns (removed, turned back into SMS).
     */
    suspend fun keepOnlySms(from: java.time.LocalDate, to: java.time.LocalDate): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val lines = db.transactions().rangeOnce(start, end - 1).filter { it.source == TxnSource.STATEMENT && it.timestamp in start until end }
        val pure = lines.filter { it.smsKey == null || it.smsKey.startsWith("STMT|") }
        val joined = lines.filter { it.smsKey != null && !it.smsKey.startsWith("STMT|") }
        val back = joined.map { t ->
            val sms = t.rawSms?.takeIf { it.contains("\n\nSMS: ") }?.substringAfter("\n\nSMS: ")
            val p = sms?.let { SmsParser.parse(t.smsKey!!.substringBefore('|'), it, t.timestamp, _fxRates.value) }
            t.copy(
                source = TxnSource.SMS, rawSms = sms ?: t.rawSms,
                amountMinor = p?.amountMinor ?: t.amountMinor, type = p?.type ?: t.type,
                merchant = if (t.userEdited) t.merchant else (p?.merchant ?: t.merchant),
                accountTail = p?.accountTail ?: t.accountTail, bank = p?.bank ?: t.bank,
                reference = p?.reference ?: t.reference, balanceMinor = p?.balanceMinor,
            )
        }
        val now = System.currentTimeMillis()
        runCatching { v13Db.withTransaction { for (t in pure) v13.putTrash(DeletedTxnEntity.from(t, now)) } }
        db.withTransaction {
            if (back.isNotEmpty()) db.transactions().updateAll(back)
            pure.map { it.uid }.chunked(500).forEach { db.transactions().deleteByUids(it) }
        }
        onDataChanged()
        pure.size to back.size
    }

    // ---- 1.10: uncategorised cleanup ----

    /**
     * Gives entries [ids] the [category], and remembers it for their payee so new SMS and any statement
     * imported later (old years too) get it by themselves.
     */
    /** [keepEdited]: entries whose category the user chose stay as they are (for changes the app makes by itself). */
    suspend fun categorise(ids: List<Long>, merchant: String?, category: String, keepEdited: Boolean = false): Int = withContext(Dispatchers.IO) {
        var n = 0
        db.withTransaction {
            for (id in ids) {
                val t = db.transactions().get(id) ?: continue
                if (keepEdited && t.userEdited) continue
                if (t.category != category) { db.transactions().update(t.copy(category = category)); n++ }
            }
            merchant?.takeIf { it.isNotBlank() }?.let { learn(it, category) }
        }
        onDataChanged()
        n
    }

    // ---- 1.10: backup reminder ----

    /** The newest backup (Manage > Backup, auto-backup) or saved export, if any. */
    val lastBackupAt: Long?
        get() = listOfNotNull(v12.lastBackup, prefs.getLong(KEY_LAST_EXPORT, 0).takeIf { it > 0 }).maxOrNull()

    /** When this phone started using the app (first time this was asked), for counting days without a backup. */
    val firstUseAt: Long
        get() = prefs.getLong(KEY_FIRST_USE, 0).takeIf { it > 0 } ?: System.currentTimeMillis().also { prefs.edit().putLong(KEY_FIRST_USE, it).apply() }

    /** Card lines for the bill view: payments reach a card on its statement, or leave the bank naming its number. */
    fun cardLines(all: List<TransactionEntity>, cards: List<com.myfinance.core.MyAccount>): List<com.myfinance.core.CardLine> {
        val tails = cards.filter { it.isCard }.map { it.tail }.toSet()
        if (tails.isEmpty()) return emptyList()
        return all.mapNotNull { t ->
            val tail = t.accountTail?.filter(Char::isDigit)?.takeLast(4)
            val bill = com.myfinance.core.SelfTransfers.isCardBillPayment(t.type, t.merchant, t.rawSms, t.source == TxnSource.STATEMENT && tail in tails)
            when {
                tail in tails -> if (t.excluded && !bill) null else com.myfinance.core.CardLine(tail, t.type, t.amountMinor, t.timestamp, bill)
                // Paid from the bank: the SMS or narration names the card.
                bill && t.type == TxnType.DEBIT -> tails.firstOrNull { c -> t.rawSms?.contains(c) == true || t.merchant?.contains(c) == true }
                    ?.let { com.myfinance.core.CardLine(it, TxnType.CREDIT, t.amountMinor, t.timestamp, true) }
                else -> null
            }
        }
    }

    /** A few days before the pay-by day: the card's last bill, when no payment has reached it yet. */
    private suspend fun checkCardBills(today: LocalDate, sent: Set<String>): List<String> {
        val cards = myAccountsNow().filter { it.isCard && it.billDay != null && it.payDay != null }
        if (cards.isEmpty()) return emptyList()
        val lines = cardLines(db.transactions().rangeOnce(System.currentTimeMillis() - 70L * 24 * 60 * 60 * 1000, Long.MAX_VALUE), cards)
        val keys = ArrayList<String>()
        for (c in cards) {
            val v = com.myfinance.core.CardCycles.view(c, lines, today) ?: continue
            if (!com.myfinance.core.CardCycles.reminderDue(v, today)) continue
            val key = "cardbill|${c.tail}|${v.payBy}"
            if (key in sent) continue
            Notifier.reminder(
                context, key.hashCode(), "Pay ${c.label} bill by ${v.payBy!!.format(dayFormat)}",
                "About ${formatInr(v.billedMinor)} from the ${v.billDate.format(dayFormat)} bill" + (c.owner?.let { " ($it's card)" } ?: "") + ". Not paid yet.",
            )
            keys += key
        }
        return keys
    }

    private suspend fun checkBackupReminder() {
        val now = System.currentTimeMillis()
        val hasData = db.transactions().countAll() > 0
        val last = prefs.getLong(KEY_BACKUP_NUDGE, 0).takeIf { it > 0 }
        if (!com.myfinance.core.BackupNudge.isDue(lastBackupAt, firstUseAt, last, hasData, now)) return
        val days = com.myfinance.core.BackupNudge.daysSince(lastBackupAt, firstUseAt, now)
        Notifier.reminder(context, "backup".hashCode(), "Time for a backup", "No backup in $days days. Open Manage > Backup to save one, so a lost phone doesn't lose your history.")
        prefs.edit().putLong(KEY_BACKUP_NUDGE, now).apply()
    }

    /**
     * An SMS (new, or found by a rescan) for a payment already imported from a statement: links the
     * SMS to that statement line and keeps the statement's details, instead of adding it twice.
     * Returns true when it found the line.
     */
    /** True when [sms] is the same payment as another SMS already stored (same reference, or wallet + bank). */
    private suspend fun repeatsAnotherSms(sms: TransactionEntity): Boolean {
        if (sms.smsKey != null && db.transactions().bySmsKey(sms.smsKey) != null) return false
        val window = 3 * 24 * 60 * 60 * 1000L
        val near = db.transactions().rangeOnce(sms.timestamp - window, sms.timestamp + window)
            .filter { it.source == TxnSource.SMS && it.smsKey != sms.smsKey && it.amountMinor == sms.amountMinor && it.type == sms.type }
        if (near.isEmpty()) return false
        fun seen(t: TransactionEntity) = com.myfinance.core.SmsDedupe.Seen(t.amountMinor, t.type, t.timestamp, t.reference, t.bank)
        return com.myfinance.core.SmsDedupe.duplicateOf(seen(sms), near.map(::seen)) != null
    }

    private suspend fun linkToStatementLine(sms: TransactionEntity, nick: String? = null): Boolean {
        if (db.transactions().bySmsKey(sms.smsKey ?: return false) != null) return false
        val window = 3 * 24 * 60 * 60 * 1000L
        // A foreign spend's SMS gives an estimated rupee amount, so its line can differ a little.
        val foreign = sms.rawSms?.let { com.myfinance.core.Fx.find(it) } != null
        val lines = db.transactions().rangeOnce(sms.timestamp - window, sms.timestamp + window)
            .filter { it.source == TxnSource.STATEMENT && it.smsKey?.startsWith("STMT|") != false && (it.amountMinor == sms.amountMinor || foreign) && it.type == sms.type }
        if (lines.isEmpty()) return false
        val i = StatementImport.lineForSms(
            ExistingTxn(sms.amountMinor, sms.type, sms.timestamp, sms.accountTail, sms.reference, sms.smsKey, text = sms.rawSms),
            lines.map { ExistingTxn(it.amountMinor, it.type, it.timestamp, it.accountTail, it.reference, it.smsKey, it.id, true, it.rawSms) },
        ) ?: return false
        val line = lines[i]
        db.transactions().update(
            line.copy(
                smsKey = sms.smsKey, rawSms = statementText(line.rawSms.orEmpty(), sms.rawSms),
                // A salary SMS keeps the line in Salary, so it counts as salary (and for the salary month).
                category = com.myfinance.core.SalaryRepair.mergedCategory(line.category, sms.category, line.userEdited, false),
                reference = line.reference ?: sms.reference, accountTail = StatementImport.keptTail(line.accountTail, sms.accountTail, sms.rawSms),
                bank = line.bank ?: sms.bank,
            ),
        )
        // The statement keeps its merchant name; the user's payee name for the SMS becomes the nickname.
        if (nick != null && nick != line.merchant) runCatching { if (v17.nicknameOf(line.uid) == null) v17.putNickname(NicknameEntity(line.uid, nick)) }
        return true
    }

    /** The statement's date, keeping the SMS's time of day when there is one. */
    private fun statementTime(date: java.time.LocalDate, smsTimestamp: Long): Long {
        val zone = ZoneId.systemDefault()
        val sms = java.time.Instant.ofEpochMilli(smsTimestamp).atZone(zone)
        return if (sms.toLocalDate() == date) smsTimestamp else date.atTime(sms.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
    }

    /** "Statement: <narration>" plus the SMS text, so rules and payment-mode detection see both. */
    private fun statementText(narration: String, existing: String?): String {
        val narr = narration.removePrefix("Statement: ").substringBefore("\n\nSMS: ")
        val sms = existing?.let { e ->
            when {
                e.contains("\n\nSMS: ") -> e.substringAfter("\n\nSMS: ")
                e.startsWith("Statement: ") -> null
                e == narration -> null
                else -> e
            }
        }?.ifBlank { null }
        return "Statement: $narr" + (sms?.let { "\n\nSMS: $it" } ?: "")
    }

    companion object {
        private const val KEY_SYNC_URI = "sync_uri"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_BUDGET_SENT = "budget_alerts_sent"
        private const val KEY_REMINDERS_SENT = "reminders_sent"
        private const val KEY_BACKFILLED = "backfilled_v11"
        private const val KEY_CYCLE_START = "month_start_day"
        private const val KEY_CARD_BILL_CLEANUP = "card_bill_cleanup_1_9"
        private const val KEY_STMT_REPEAT_CLEANUP = "stmt_repeat_cleanup_1_11_1"
        private const val KEY_STMT_REPEAT_REMOVED = "stmt_repeat_removed_1_11_1"
        private const val KEY_SMS_DOUBLE_CLEANUP = "sms_double_cleanup_1_11_3"
        private const val KEY_SMS_DOUBLE_CLEANUP_2 = "sms_double_cleanup_1_14"
        private const val KEY_SMS_DOUBLE_REMOVED = "sms_double_removed_1_11_3"
        private const val KEY_MERCHANT_REFRESH = "stmt_merchant_refresh_1_15"
        private const val KEY_SALARY_RESTORE = "salary_restore_1_12_1"
        private const val KEY_INVEST_FIND = "investments_find_1_14"
        private const val KEY_EMI_END_FILLED = "emi_end_filled_1_13"
        private const val KEY_CODE_PAYEES = "code_payees_1_14"
        private const val KEY_RECEIPTS_REMOVED = "biller_receipts_1_14"
        private const val KEY_SAME_ID_REMOVED = "same_id_removed_1_15"
        const val REV_PAIR = "reversal"
        const val REV_NOT = "not_reversal"
        private const val IMPORT_CHUNK = 200
        private const val KEY_LAST_EXPORT = "last_export_at"
        private const val KEY_FIRST_USE = "first_use_at"
        private const val KEY_BACKUP_NUDGE = "backup_nudge_at"
        private const val KEY_WEEKLY_DIGEST = "weekly_digest_on"
        private const val KEY_WEEKLY_SENT = "weekly_digest_sent_week"
        private const val KEY_SALARY_FROM = "salary_next_month_from_day"
        private const val KEY_SALARY_AUTO = "salary_auto_state"
        private const val KEY_LAST_SCAN = "last_inbox_scan"
        private const val KEY_STMT_DAY = "statement_reminder_day"
        private const val KEY_STMT_SENT = "statement_reminder_sent"
        private const val KEY_REFUND_DAYS = "refund_overdue_days"

        private val dayFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM")

        /** One row per card / loan and due date; several SMS often announce the same bill. */
        fun dedupeBills(bills: List<BillEntity>): List<BillEntity> =
            bills.sortedByDescending { it.receivedAt }
                .distinctBy { Triple(it.kind, it.accountTail ?: it.bank, it.dueEpochDay) }
                .sortedBy { it.dueEpochDay }

        /** Same message body from the same bank = same transaction, however it reached us. */
        fun smsKey(sender: String, body: String): String {
            val senderCore = sender.uppercase().split('-').maxByOrNull { it.length } ?: sender
            val bodyHash = java.security.MessageDigest.getInstance("SHA-1")
                .digest(body.trim().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return "$senderCore|$bodyHash"
        }

        val palette = listOf(
            0xFFE76F51, 0xFF2A9D8F, 0xFFE9C46A, 0xFF264653, 0xFFF4A261, 0xFF6D597A, 0xFF457B9D,
            0xFFB5838D, 0xFF43AA8B, 0xFFF94144, 0xFF577590, 0xFF90BE6D, 0xFFF8961E, 0xFF4D908E,
            0xFF9C6644, 0xFF277DA1, 0xFFC77DFF, 0xFF8D99AE,
        )
    }
}
