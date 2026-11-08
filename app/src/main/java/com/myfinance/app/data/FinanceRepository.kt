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
import java.time.YearMonth
import java.time.ZoneId

class FinanceRepository(
    private val context: Context,
    private val db: FinanceDatabase,
    private val extrasDb: ExtrasDatabase,
) {

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

    /**
     * Parses one SMS: stores the transaction it describes, plus any bill due date or account balance
     * it mentions. Returns true when a new transaction was added.
     */
    suspend fun ingestSms(sender: String, body: String, timestamp: Long, categorizer: Categorizer? = null): Boolean {
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

        val parsed = SmsParser.parse(sender, body, timestamp) ?: return false
        val cat = (categorizer ?: categorizer()).categorize(parsed.merchant, parsed.type, body)
        val row = TransactionEntity(
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
        return db.transactions().insert(row) != -1L
    }

    /**
     * Reads the SMS inbox and imports every transaction message received in the last [monthsBack] months.
     * Safe to run repeatedly: already-imported messages are skipped by their dedupe key.
     */
    suspend fun importInbox(monthsBack: Int = 12): Int = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - monthsBack * 31L * 24 * 60 * 60 * 1000
        val cat = categorizer()
        var added = 0
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
                if (ingestSms(sender, body, c.getLong(iDate), cat)) added++
            }
        }
        prefs.edit().putBoolean(KEY_BACKFILLED, true).apply()
        onDataChanged()
        added
    }

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

    suspend fun delete(txn: TransactionEntity) {
        db.transactions().delete(txn)
        onDataChanged()
    }

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

    data class MergeResult(val added: Int, val duplicates: Int, val unreadable: Int)

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
        val fresh = Dedupe.newOnly(existing, incoming)
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
        return MergeResult(added = added, duplicates = incoming.size - added, unreadable = bad)
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

    suspend fun monthTotals(month: YearMonth = YearMonth.now()): MonthTotals {
        val list = monthRows(month).filterNot { it.excluded }
        return MonthTotals(
            incomeMinor = list.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor },
            expenseMinor = list.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor },
        )
    }

    /** Sum of all monthly category budgets (0 when none are set). */
    suspend fun totalBudget(): Long = db.categories().allOnce().sumOf { it.monthlyBudgetMinor ?: 0L }

    private suspend fun monthRows(month: YearMonth): List<TransactionEntity> {
        val zone = ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return db.transactions().rangeOnce(from, to)
    }

    /** Refreshes everything that depends on the totals: budget alerts and the home screen widget. */
    suspend fun onDataChanged() {
        runCatching { checkBudgets() }
        runCatching { SpendWidget.refresh(context) }
    }

    /** Sends a notification the first time a category passes 80% and 100% of its budget each month. */
    suspend fun checkBudgets() {
        val month = YearMonth.now()
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
