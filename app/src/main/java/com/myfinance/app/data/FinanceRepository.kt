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
import com.myfinance.core.DefaultCategories
import com.myfinance.core.RecurringDetector
import com.myfinance.core.SmsParser
import com.myfinance.core.StatementExport
import com.myfinance.core.TxnType
import com.myfinance.core.XlsxWriter
import com.myfinance.core.formatInr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    v13Db: V13Database,
) {

    /** v1.2 features: goals, net worth, bill reminders, splits, receipts, tax, rules, backups. */
    val v12 = V12Repository(context, v12Db) {
        listOf("myfinance.db" to db, "myfinance-extras.db" to extrasDb, V12Database.FILE to v12Db, V13Database.FILE to v13Db)
    }

    /** v1.3: recently deleted bin, deleted-row records, auto debit plans. */
    val v13: V13Dao = v13Db.dao()

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

    fun setMonthCycle(cycle: MonthCycle) {
        prefs.edit()
            .putInt(KEY_CYCLE_START, cycle.startDay)
            .putInt(KEY_SALARY_FROM, cycle.salaryFromDay ?: 0)
            .apply()
        _monthCycle.value = cycle
    }

    val categories: Flow<List<CategoryEntity>> = db.categories().observeAll()

    fun transactionsBetween(from: Long, to: Long): Flow<List<TransactionEntity>> =
        db.transactions().observeRange(from, to)

    fun allTransactions(): Flow<List<TransactionEntity>> = db.transactions().observeAll()

    val bills: Flow<List<BillEntity>> = extras.observeBills()

    /** Latest balance per account. */
    val balances: Flow<List<BalanceReading>> = extras.observeBalances().map { list -> Balances.latest(list.map { it.toReading() }) }

    /** Payment modes of rows merged from other phones, by uid. */
    val mergedModes: Flow<Map<String, String>> = extras.observeModes().map { list -> list.associate { it.uid to it.mode } }

    suspend fun get(id: Long): TransactionEntity? = db.transactions().get(id)

    suspend fun ensureDefaults() {
        if (db.categories().count() > 0) return
        db.categories().insertAll(
            DefaultCategories.all.mapIndexed { i, name ->
                CategoryEntity(name = name, builtIn = true, color = palette[i % palette.size])
            },
        )
    }

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

        val parsed = SmsParser.parse(sender, body, timestamp) ?: return Ingest.NOT_A_TRANSACTION
        if (v13.isSmsDeleted(key) > 0) return Ingest.DELETED_BEFORE
        // The user's own rules ("any SMS with SWIGGY goes to Food") come before everything else.
        val rule = v12.ruleFor(sender, body, parsed.merchant, parsed.type, parsed.amountMinor)
        if (rule?.ignore == true) return Ingest.NOT_A_TRANSACTION
        val cat = (categorizer ?: categorizer()).categorize(parsed.merchant, parsed.type, body)
        val base = TransactionEntity(
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
        )
        val row = rule?.applyTo(base) ?: base
        val added = db.transactions().insert(row) != -1L
        if (added) runCatching { v12.onNewTransaction(row, rule) }
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
            if (t.userEdited || t.source != TxnSource.SMS) continue
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
        val rows = db.transactions().allOnce().map { it.toShared(me, modes) }
        return CsvCodec.encode(rows) to rows.size
    }

    /** Writes every transaction (yours and merged ones) to a CSV file the family can share. */
    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val (csv, n) = exportCsv()
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(csv.toByteArray(Charsets.UTF_8))
        } ?: error("Could not open file for writing")
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

    data class MonthTotals(val incomeMinor: Long, val expenseMinor: Long)

    suspend fun monthTotals(month: YearMonth = monthCycle.value.current()): MonthTotals {
        val list = monthRows(month).filterNot { it.excluded }
        return MonthTotals(
            incomeMinor = list.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
            expenseMinor = list.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor },
        )
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
    }

    /** Sends a notification the first time a category passes 80% and 100% of its budget each month. */
    suspend fun checkBudgets() {
        val month = monthCycle.value.current()
        val budgets = db.categories().allOnce().mapNotNull { c -> c.monthlyBudgetMinor?.let { c.name to it } }.toMap()
        if (budgets.isEmpty()) return
        val spent = monthRows(month).filter { !it.excluded && it.type == TxnType.DEBIT }
            .groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountMinor } }
        val sent = prefs.getStringSet(KEY_BUDGET_SENT, emptySet()).orEmpty()
        val due = BudgetAlerts.due(spent, budgets, sent, month)
        if (due.isEmpty()) return
        due.forEach { Notifier.budget(context, it) }
        // Keep only this month's keys so the set does not grow forever.
        val keep = sent.filter { it.startsWith("$month|") } + due.flatMap { BudgetAlerts.keysToRecord(it) }
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

        if (newKeys.isNotEmpty()) {
            // Forget keys older than ~2 months.
            val cutoff = today.minusDays(60)
            val kept = sent.filter { k -> k.substringAfterLast('|').let { d -> parseKeyDate(d)?.isAfter(cutoff) ?: false } }
            prefs.edit().putStringSet(KEY_REMINDERS_SENT, (kept + newKeys).toSet()).apply()
        }
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

    companion object {
        private const val KEY_SYNC_URI = "sync_uri"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_BUDGET_SENT = "budget_alerts_sent"
        private const val KEY_REMINDERS_SENT = "reminders_sent"
        private const val KEY_BACKFILLED = "backfilled_v11"
        private const val KEY_CYCLE_START = "month_start_day"
        private const val KEY_SALARY_FROM = "salary_next_month_from_day"
        private const val KEY_LAST_SCAN = "last_inbox_scan"

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
