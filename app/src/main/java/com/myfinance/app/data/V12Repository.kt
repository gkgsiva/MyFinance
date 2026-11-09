package com.myfinance.app.data

import android.content.Context
import android.net.Uri
import com.myfinance.app.notify.Notifier
import com.myfinance.core.BackupCrypto
import com.myfinance.core.BillReminders
import com.myfinance.core.BillState
import com.myfinance.core.CustomRules
import com.myfinance.core.Dates
import com.myfinance.core.InvestmentSmsParser
import com.myfinance.core.NotABackupException
import com.myfinance.core.RuleInput
import com.myfinance.core.RuleOutcome
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Goals, net worth, bill reminders, splits, receipts, tax tags, custom rules and backups. */
class V12Repository(
    private val context: Context,
    private val v12: V12Database,
    private val databases: () -> List<Pair<String, androidx.room.RoomDatabase>>,
) {
    val dao: V12Dao get() = v12.dao()
    private val prefs = context.getSharedPreferences("myfinance", Context.MODE_PRIVATE)

    val goals: Flow<List<GoalEntity>> = dao.observeGoals()
    val holdings: Flow<List<HoldingEntity>> = dao.observeHoldings()
    val events: Flow<List<InvestmentEventEntity>> = dao.observeEvents()
    val overrides: Flow<List<ValueOverrideEntity>> = dao.observeOverrides()
    val liabilities: Flow<List<LiabilityEntity>> = dao.observeLiabilities()
    val bills: Flow<List<BillReminderEntity>> = dao.observeBills()
    val splits: Flow<List<SplitEntity>> = dao.observeSplits()
    val settlements: Flow<List<SettlementEntity>> = dao.observeSettlements()
    val taxTags: Flow<List<TaxTagEntity>> = dao.observeTaxTags()
    val rules: Flow<List<CustomRuleEntity>> = dao.observeRules()
    val receiptUids: Flow<List<String>> = dao.observeReceiptUids()
    fun receipts(txnUid: String): Flow<List<ReceiptEntity>> = dao.observeReceipts(txnUid)

    // ---- SMS hooks (called by FinanceRepository.ingestSms) ----

    private var cachedRules: List<com.myfinance.core.CustomRule>? = null

    private suspend fun activeRules() = cachedRules ?: dao.rules().map { it.toRule() }.also { cachedRules = it }

    fun invalidateRules() { cachedRules = null }

    /** The first custom rule that matches this SMS transaction, if any. */
    suspend fun ruleFor(sender: String?, body: String?, merchant: String?, type: TxnType, amountMinor: Long): RuleOutcome? =
        CustomRules.apply(activeRules(), RuleInput(sender, body, merchant, type, amountMinor))

    /** Records investment SMS (SIP, MF, FD, trades) for the net worth screen. */
    suspend fun onSms(smsKey: String, body: String, timestamp: Long) {
        InvestmentSmsParser.parse(body, timestamp)?.let { e ->
            dao.insertEvent(
                InvestmentEventEntity(
                    smsKey = smsKey, kind = e.kind.name, name = e.name, amountMinor = e.amountMinor,
                    redemption = e.redemption, units = e.units, currentValueMinor = e.currentValueMinor, timestamp = timestamp,
                ),
            )
        }
    }

    /** After a new SMS transaction is stored: tax tag from a rule, and mark a matching bill paid. */
    suspend fun onNewTransaction(row: TransactionEntity, outcome: RuleOutcome?) {
        outcome?.taxSection?.let { dao.upsertTaxTag(TaxTagEntity(row.uid, it.name)) }
        if (row.type != TxnType.DEBIT) return
        val paidOn = Dates.localDate(row.timestamp, ZoneId.systemDefault())
        for (b in dao.bills()) {
            val bill = b.toBill()
            if (BillReminders.matchesPayment(bill, row.merchant, row.rawSms, row.amountMinor, paidOn)) {
                dao.upsertBill(b.copy(nextDueEpochDay = BillReminders.markPaid(bill).nextDue.toEpochDay(), lastPaidAt = row.timestamp))
                break
            }
        }
    }

    // ---- Bill reminders ----

    suspend fun markBillPaid(b: BillReminderEntity) =
        dao.upsertBill(b.copy(nextDueEpochDay = BillReminders.markPaid(b.toBill()).nextDue.toEpochDay(), lastPaidAt = System.currentTimeMillis()))

    /** Notifies each bill once per due date when it gets close, and again on the day / when overdue. */
    suspend fun checkBillReminders(today: LocalDate = LocalDate.now()) {
        val sent = prefs.getStringSet(KEY_BILL_SENT, emptySet()).orEmpty()
        val fresh = ArrayList<String>()
        for (s in BillReminders.needingAttention(dao.bills().map { it.toBill() }, today)) {
            val stage = if (s.state == BillState.DUE_SOON) "soon" else "due"
            val key = "${s.bill.id}|${s.bill.nextDue}|$stage"
            if (key in sent) continue
            val amount = s.bill.amountMinor?.let { " of ${formatInr(it)}" }.orEmpty()
            val title = when (s.state) {
                BillState.OVERDUE -> "${s.bill.name} is overdue"
                BillState.DUE_TODAY -> "${s.bill.name} is due today"
                else -> "${s.bill.name} due in ${s.daysLeft} days"
            }
            Notifier.reminder(context, key.hashCode(), title, "Payment$amount due on ${s.bill.nextDue.format(DAY)}.")
            fresh += key
        }
        if (fresh.isNotEmpty()) {
            val keep = sent.filter { k -> runCatching { LocalDate.parse(k.split('|')[1]) }.getOrNull()?.isAfter(today.minusDays(60)) == true }
            prefs.edit().putStringSet(KEY_BILL_SENT, (keep + fresh).toSet()).apply()
        }
    }

    // ---- Receipts ----

    val receiptsDir: File get() = File(context.filesDir, "receipts").apply { mkdirs() }

    fun receiptFile(r: ReceiptEntity) = File(receiptsDir, r.fileName)

    /** A new empty file for the camera to write into. */
    fun newReceiptFile(): File = File(receiptsDir, "r-${System.currentTimeMillis()}.jpg")

    suspend fun addReceiptFromCamera(txnUid: String, file: File) {
        if (file.exists() && file.length() > 0) dao.insertReceipt(ReceiptEntity(txnUid = txnUid, fileName = file.name, addedAt = System.currentTimeMillis()))
        else file.delete()
    }

    /** Copies a picked photo into private storage, so it survives the original being deleted. */
    suspend fun addReceiptFromUri(txnUid: String, uri: Uri) = withContext(Dispatchers.IO) {
        val f = newReceiptFile()
        context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            ?: error("Could not read the photo")
        dao.insertReceipt(ReceiptEntity(txnUid = txnUid, fileName = f.name, addedAt = System.currentTimeMillis()))
    }

    suspend fun deleteReceipt(r: ReceiptEntity) = withContext(Dispatchers.IO) {
        receiptFile(r).delete()
        dao.deleteReceipt(r.id)
    }

    // ---- Encrypted backup & restore ----

    /**
     * Writes an encrypted backup of everything (all databases, receipt photos and this phone's name) to [uri].
     * Returns the number of files in the backup.
     */
    suspend fun backupTo(uri: Uri, password: CharArray): Int = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, ByteArray>()
        for ((name, db) in databases()) {
            // Fold the write-ahead log into the main file so one file holds everything.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val f = context.getDatabasePath(name)
            if (f.exists()) files["db/$name"] = f.readBytes()
        }
        receiptsDir.listFiles()?.forEach { f -> if (f.isFile) files["receipts/${f.name}"] = f.readBytes() }
        prefs.getString("owner", null)?.let { files["prefs/owner"] = it.toByteArray() }
        files["meta/created"] = System.currentTimeMillis().toString().toByteArray()
        val sealed = BackupCrypto.encrypt(files, password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(sealed) } ?: error("Could not open the backup file for writing")
        prefs.edit().putLong(KEY_LAST_BACKUP, System.currentTimeMillis()).apply()
        files.size
    }

    val lastBackup: Long? get() = prefs.getLong(KEY_LAST_BACKUP, 0).takeIf { it > 0 }

    /**
     * Checks the password and stages the backup. The data is swapped in the next time the app starts
     * (see [applyPendingRestore]), so nothing is replaced while the databases are open.
     */
    suspend fun stageRestore(uri: Uri, password: CharArray): Int = withContext(Dispatchers.IO) {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open the backup file")
        val files = BackupCrypto.decrypt(data, password)
        if (files.keys.none { it.startsWith("db/") }) throw NotABackupException()
        val staging = File(context.filesDir, STAGING).apply { deleteRecursively(); mkdirs() }
        for ((name, bytes) in files) {
            val safe = name.split('/').filter { it.isNotEmpty() && it != ".." && it != "." }
            if (safe.size != 2) continue
            File(File(staging, safe[0]).apply { mkdirs() }, safe[1]).writeBytes(bytes)
        }
        File(staging, "READY").writeText("1")
        files.keys.count { it.startsWith("db/") || it.startsWith("receipts/") }
    }

    companion object {
        private const val KEY_BILL_SENT = "v12_bill_reminders_sent"
        private const val KEY_LAST_BACKUP = "v12_last_backup"
        private const val STAGING = "restore-pending"
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
        private val DB_FILES = setOf("myfinance.db", "myfinance-extras.db", V12Database.FILE, V13Database.FILE)

        /** Called before any database is opened: moves a staged restore into place. */
        fun applyPendingRestore(context: Context) {
            val staging = File(context.filesDir, STAGING)
            if (!File(staging, "READY").exists()) { staging.deleteRecursively(); return }
            File(staging, "db").listFiles()?.forEach { f ->
                if (f.name !in DB_FILES) return@forEach
                val target = context.getDatabasePath(f.name)
                target.parentFile?.mkdirs()
                listOf("-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
                f.copyTo(target, overwrite = true)
            }
            val receipts = File(context.filesDir, "receipts")
            File(staging, "receipts").takeIf { it.isDirectory }?.let { src ->
                receipts.deleteRecursively()
                src.copyRecursively(receipts, overwrite = true)
            }
            File(staging, "prefs/owner").takeIf { it.exists() }?.let {
                context.getSharedPreferences("myfinance", Context.MODE_PRIVATE).edit().putString("owner", it.readText()).commit()
            }
            // The backup came from a phone that may have synced with a different family file.
            context.getSharedPreferences("myfinance", Context.MODE_PRIVATE).edit().remove("sync_uri").commit()
            staging.deleteRecursively()
        }
    }
}

/** Fills in a parsed SMS transaction with what a custom rule says. */
fun RuleOutcome.applyTo(row: TransactionEntity): TransactionEntity =
    row.copy(
        category = category ?: row.category,
        merchant = merchant ?: row.merchant,
        excluded = row.excluded || exclude,
    )
