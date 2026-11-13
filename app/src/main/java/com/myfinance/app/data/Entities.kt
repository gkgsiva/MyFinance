package com.myfinance.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myfinance.core.PaymentMode
import com.myfinance.core.SharedTxn
import com.myfinance.core.TxnLite
import com.myfinance.core.TxnType

/** Where a transaction came from: an SMS alert, typed in, or a downloaded bank / card statement. */
enum class TxnSource { SMS, MANUAL, STATEMENT }

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["smsKey"], unique = true),
        Index(value = ["uid"], unique = true),
        Index("timestamp"),
        Index("category"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val type: TxnType,
    val merchant: String?,
    val category: String,
    val accountTail: String?,
    val bank: String?,
    val timestamp: Long,
    val note: String? = null,
    val source: TxnSource,
    /** Dedupe key: sender + body hash for SMS, "STMT|…" for statement lines. Null for manual entries. */
    val smsKey: String? = null,
    val rawSms: String? = null,
    val reference: String? = null,
    val balanceMinor: Long? = null,
    /** True once the user has changed anything, so re-categorisation never overwrites their edits. */
    val userEdited: Boolean = false,
    /** Excluded transactions (e.g. transfers between your own accounts) are kept but left out of totals. */
    val excluded: Boolean = false,
    /** Stable id shared across family exports, so a merged file never duplicates rows. */
    val uid: String = java.util.UUID.randomUUID().toString(),
    /** Whose phone this came from (blank = this phone's owner). */
    val owner: String? = null,
)

val TransactionEntity.accountLabel: String?
    get() = when {
        bank != null && accountTail != null -> "$bank ••$accountTail"
        accountTail != null -> "••$accountTail"
        else -> bank
    }

/** [mergedModes] holds the payment modes of rows merged from other phones, keyed by uid. */
fun TransactionEntity.paymentMode(mergedModes: Map<String, String> = emptyMap()): PaymentMode =
    PaymentMode.fromName(mergedModes[uid]) ?: ModeMemo.detect(this)

/**
 * Payment modes read from the SMS text, remembered per entry: working them out runs several patterns over the
 * whole text, and every screen asks again for every entry each time anything changes.
 */
private object ModeMemo {
    private class Seen(val rawSms: String?, val manual: Boolean, val category: String, val mode: PaymentMode)
    private val seen = java.util.concurrent.ConcurrentHashMap<Long, Seen>()

    fun detect(t: TransactionEntity): PaymentMode {
        val manual = t.source == TxnSource.MANUAL
        seen[t.id]?.let { if (it.manual == manual && it.category == t.category && it.rawSms == t.rawSms) return it.mode }
        val mode = PaymentMode.detect(t.rawSms, manual, t.category)
        if (t.id != 0L) seen[t.id] = Seen(t.rawSms, manual, t.category, mode)
        return mode
    }
}

fun TransactionEntity.toLite(defaultOwner: String?, mergedModes: Map<String, String> = emptyMap()) =
    TxnLite(
        amountMinor, type, category, merchant, accountLabel, timestamp, owner ?: defaultOwner,
        id = id, mode = paymentMode(mergedModes),
    )

fun TransactionEntity.toShared(defaultOwner: String?, mergedModes: Map<String, String> = emptyMap()) = SharedTxn(
    uid = uid, amountMinor = amountMinor, type = type, merchant = merchant, category = category,
    accountTail = accountTail, bank = bank, timestamp = timestamp, note = note, owner = owner ?: defaultOwner,
    source = source.name, smsKey = smsKey, reference = reference, excluded = excluded,
    mode = paymentMode(mergedModes).name,
)

fun SharedTxn.toEntity() = TransactionEntity(
    amountMinor = amountMinor, type = type, merchant = merchant, category = category, accountTail = accountTail,
    bank = bank, timestamp = timestamp, note = note,
    source = TxnSource.entries.firstOrNull { it.name == source } ?: TxnSource.MANUAL,
    // Keep the other phone's SMS key so the same message arriving here later is recognised.
    smsKey = smsKey, reference = reference, excluded = excluded, uid = uid, owner = owner,
    userEdited = true,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val name: String,
    val builtIn: Boolean = false,
    /** ARGB colour used in charts. */
    val color: Long,
    val monthlyBudgetMinor: Long? = null,
)

/** A learnt "this merchant belongs to that category" rule, created whenever the user re-categorises. */
@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey val merchantKey: String,
    val category: String,
)
