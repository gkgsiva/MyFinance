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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    v14Db: V14Database,
) {

    /** v1.2 features: goals, net worth, bill reminders, splits, receipts, tax, rules, backups. */
    val v12 = V12Repository(context, v12Db) {
        listOf("myfinance.db" to db, "myfinance-extras.db" to extrasDb, V12Database.FILE to v12Db, V13Database.FILE to v13Db, V14Database.FILE to v14Db)
    }

    /** v1.3: recently deleted bin, deleted-row records, auto debit plans. */
    val v13: V13Dao = v13Db.dao()

    /** v1.4: label budgets, label suggestion answers, refunds awaited, loan rates, pinned reports. */
    val v14: V14Dao = v14Db.dao()

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
        // A name the user gave this payee ("Vegetable seller Ravi") replaces the raw UPI id / merchant text.
        val nick = parsed.merchant?.let { m -> runCatching { payeeName(m) }.getOrNull() }
        val row = (rule?.applyTo(base) ?: base).let { r -> if (nick != null && rule?.merchant == null) r.copy(merchant = nick) else r }
        // Already imported from a bank / card statement: link the SMS to that line, never a second entry.
        if (runCatching { linkToStatementLine(row) }.getOrDefault(false)) return Ingest.ALREADY_HERE
        val added = db.transactions().insert(row) != -1L
        if (added) {
            runCatching { v12.onNewTransaction(row, rule) }
            runCatching { applyLabelRules(row, parsed.merchant) }
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

        runCatching { checkStatementReminder(today) }
        runCatching { newKeys += checkRefunds(today, sent) }

        if (newKeys.isNotEmpty()) {
            // Forget keys older than ~2 months.
            val cutoff = today.minusDays(60)
            val kept = sent.filter { k -> k.substringAfterLast('|').let { d -> parseKeyDate(d)?.isAfter(cutoff) ?: false } }
            prefs.edit().putStringSet(KEY_REMINDERS_SENT, (kept + newKeys).toSet()).apply()
        }
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
            ExistingTxn(it.amountMinor, it.type, it.timestamp, it.accountTail, it.reference, it.smsKey, it.id, it.source == TxnSource.STATEMENT)
        }
        // Statement lines the user deleted count as already imported, so they stay deleted.
        val deleted = v13.keys().mapNotNull { k -> k.smsKey?.takeIf { it.startsWith("STMT|") } }
            .map { ExistingTxn(-1, TxnType.DEBIT, 0, null, null, it) }
        stored + deleted
    }

    data class ImportResult(val added: Int, val updated: Int)

    /**
     * Stores the statement lines the user chose to import. New lines are categorised like SMS (custom
     * rules, payee names and label rules apply). A line that is the same payment as a stored SMS or
     * typed entry updates that entry instead: the statement is the official record, so its amount,
     * date and details win, while the user's category, labels, notes and payee name are kept.
     */
    suspend fun addImported(rows: List<ImportCandidate>): ImportResult = withContext(Dispatchers.IO) {
        val cat = categorizer()
        val deletedUids = v13.keys().mapTo(HashSet()) { it.uid }
        val nicknames = runCatching { v13.payeeNames().mapTo(HashSet()) { it.name } }.getOrDefault(HashSet())
        var added = 0
        var updated = 0
        for (c in rows) {
            val r = c.row
            val nick = c.merchant?.let { m -> runCatching { payeeName(m) }.getOrNull() }
            if (c.updatesExisting) {
                val t = db.transactions().get(c.matchedId!!) ?: continue
                val keepName = t.userEdited || t.merchant in nicknames
                db.transactions().update(
                    t.copy(
                        amountMinor = r.amountMinor, type = r.type, timestamp = statementTime(r.date, t.timestamp),
                        merchant = if (keepName) t.merchant else nick ?: c.merchant ?: t.merchant,
                        accountTail = c.accountTail ?: t.accountTail, bank = c.bank ?: t.bank,
                        reference = t.reference ?: r.reference, balanceMinor = r.balanceMinor ?: t.balanceMinor,
                        source = TxnSource.STATEMENT, rawSms = statementText(r.description, t.rawSms),
                    ),
                )
                updated++
                continue
            }
            if (c.duplicateOf != null || c.uid in deletedUids) continue
            val rule = v12.ruleFor(null, r.description, c.merchant, r.type, r.amountMinor)
            if (rule?.ignore == true) continue
            val base = TransactionEntity(
                amountMinor = r.amountMinor, type = r.type, merchant = c.merchant,
                category = cat.categorize(c.merchant, r.type, r.description),
                accountTail = c.accountTail, bank = c.bank, timestamp = c.timestamp, source = TxnSource.STATEMENT,
                smsKey = c.key, rawSms = r.description, reference = r.reference, balanceMinor = r.balanceMinor, uid = c.uid,
            )
            val row = (rule?.applyTo(base) ?: base).let { x -> if (nick != null && rule?.merchant == null) x.copy(merchant = nick) else x }
            if (db.transactions().insert(row) != -1L) {
                added++
                runCatching { v12.onNewTransaction(row, rule) }
                runCatching { applyLabelRules(row, c.merchant) }
            }
        }
        onDataChanged()
        ImportResult(added, updated)
    }

    /**
     * An SMS (new, or found by a rescan) for a payment already imported from a statement: links the
     * SMS to that statement line and keeps the statement's details, instead of adding it twice.
     * Returns true when it found the line.
     */
    private suspend fun linkToStatementLine(sms: TransactionEntity): Boolean {
        if (db.transactions().bySmsKey(sms.smsKey ?: return false) != null) return false
        val window = 3 * 24 * 60 * 60 * 1000L
        val lines = db.transactions().rangeOnce(sms.timestamp - window, sms.timestamp + window)
            .filter { it.source == TxnSource.STATEMENT && it.smsKey?.startsWith("STMT|") != false && it.amountMinor == sms.amountMinor && it.type == sms.type }
        if (lines.isEmpty()) return false
        val i = StatementImport.lineForSms(
            ExistingTxn(sms.amountMinor, sms.type, sms.timestamp, sms.accountTail, sms.reference, sms.smsKey),
            lines.map { ExistingTxn(it.amountMinor, it.type, it.timestamp, it.accountTail, it.reference, it.smsKey, it.id, true) },
        ) ?: return false
        val line = lines[i]
        db.transactions().update(
            line.copy(
                smsKey = sms.smsKey, rawSms = statementText(line.rawSms.orEmpty(), sms.rawSms),
                reference = line.reference ?: sms.reference, accountTail = line.accountTail ?: sms.accountTail,
                bank = line.bank ?: sms.bank,
            ),
        )
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
        private const val KEY_SALARY_FROM = "salary_next_month_from_day"
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
