package com.myfinance.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myfinance.core.SharedTxn
import com.myfinance.core.TxnLite
import com.myfinance.core.TxnType

enum class TxnSource { SMS, MANUAL }

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
    /** Dedupe key for SMS imports: sender + timestamp + body hash. Null for manual entries. */
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

fun TransactionEntity.toLite(defaultOwner: String?) =
    TxnLite(amountMinor, type, category, merchant, accountLabel, timestamp, owner ?: defaultOwner)

fun TransactionEntity.toShared(defaultOwner: String?) = SharedTxn(
    uid = uid, amountMinor = amountMinor, type = type, merchant = merchant, category = category,
    accountTail = accountTail, bank = bank, timestamp = timestamp, note = note, owner = owner ?: defaultOwner,
    source = source.name, smsKey = smsKey, reference = reference, excluded = excluded,
)

fun SharedTxn.toEntity() = TransactionEntity(
    amountMinor = amountMinor, type = type, merchant = merchant, category = category, accountTail = accountTail,
    bank = bank, timestamp = timestamp, note = note,
    source = if (source == "SMS") TxnSource.SMS else TxnSource.MANUAL,
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
