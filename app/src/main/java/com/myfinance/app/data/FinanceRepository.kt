package com.myfinance.app.data

import android.content.Context
import android.net.Uri
import com.myfinance.core.Categorizer
import com.myfinance.core.CsvCodec
import com.myfinance.core.Dedupe
import com.myfinance.core.DefaultCategories
import com.myfinance.core.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class FinanceRepository(private val context: Context, private val db: FinanceDatabase) {

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

    /** Parses one SMS and stores it if it is a transaction. Returns true when a new row was added. */
    suspend fun ingestSms(sender: String, body: String, timestamp: Long, categorizer: Categorizer? = null): Boolean {
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
            smsKey = smsKey(sender, body),
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
        added
    }

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
    }

    private suspend fun learn(merchant: String, category: String) {
        val key = Categorizer.merchantKey(merchant)
        db.merchantRules().upsert(MerchantRuleEntity(key, category))
        val updates = db.transactions().uneditedWithMerchant()
            .filter { Categorizer.merchantKey(it.merchant!!) == key && it.category != category }
            .map { it.copy(category = category) }
        if (updates.isNotEmpty()) db.transactions().updateAll(updates)
    }

    suspend fun delete(txn: TransactionEntity) = db.transactions().delete(txn)

    /** Writes every transaction (yours and merged ones) to a CSV file the family can share. */
    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val me = ownerName.value
        val rows = db.transactions().allOnce().map { it.toShared(me) }
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(CsvCodec.encode(rows).toByteArray(Charsets.UTF_8))
        } ?: error("Could not open file for writing")
        rows.size
    }

    data class MergeResult(val added: Int, val duplicates: Int, val unreadable: Int)

    /**
     * Merges another phone's export. Rows that already exist here, such as a joint-account payment
     * whose SMS reached both phones, are skipped.
     */
    suspend fun mergeFrom(uri: Uri): MergeResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Could not open file")
        val (incoming, bad) = CsvCodec.decode(text)
        val me = ownerName.value
        val existing = db.transactions().allOnce().map { it.toShared(me) }
        val fresh = Dedupe.newOnly(existing, incoming)
        val known = db.categories().names().toSet()
        var added = 0
        for (t in fresh) {
            // Rows exported from this phone come back labelled with our own name; store them unlabelled.
            val row = t.toEntity().let { if (it.owner == me) it.copy(owner = null) else it }
            if (db.transactions().insert(row) != -1L) added++
            if (t.category !in known) addCategory(t.category, null)
        }
        MergeResult(added = added, duplicates = incoming.size - added, unreadable = bad)
    }

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
    }

    suspend fun updateCategory(category: CategoryEntity) = db.categories().upsert(category)

    /** Deletes a custom category; its transactions and rules move to "Uncategorized". */
    suspend fun deleteCategory(name: String) {
        db.transactions().renameCategory(name, DefaultCategories.OTHER)
        db.merchantRules().renameCategory(name, DefaultCategories.OTHER)
        db.categories().deleteCustom(name)
    }

    companion object {
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
