package com.myfinance.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.myfinance.core.AssetKind
import com.myfinance.core.BillReminder
import com.myfinance.core.CustomRule
import com.myfinance.core.Holding
import com.myfinance.core.InvestmentEvent
import com.myfinance.core.Liability
import com.myfinance.core.LiabilityKind
import com.myfinance.core.ReminderKind
import com.myfinance.core.RuleField
import com.myfinance.core.SavingsGoal
import com.myfinance.core.Settlement
import com.myfinance.core.SplitExpense
import com.myfinance.core.TaxSection
import com.myfinance.core.TxnType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/*
 * v1.2 data lives in a third database file, like v1.1 did, so upgrading never migrates the
 * existing tables and nobody's data is at risk.
 */

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val targetMinor: Long,
    val deadlineEpochDay: Long?,
    val savedMinor: Long = 0,
    val createdEpochDay: Long = LocalDate.now().toEpochDay(),
) {
    fun toGoal() = SavingsGoal(id, name, targetMinor, deadlineEpochDay?.let(LocalDate::ofEpochDay), savedMinor, LocalDate.ofEpochDay(createdEpochDay))
}

/** Holdings typed in by hand (property, gold, PPF...). */
@Entity(tableName = "holdings")
data class HoldingEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val kind: String,
    val investedMinor: Long,
    val currentMinor: Long,
) {
    fun toHolding() = Holding(name, AssetKind.entries.firstOrNull { it.name == kind } ?: AssetKind.OTHER, investedMinor, currentMinor)
}

/** Investment SMS (SIPs, MF purchases, FDs, trades), stored once per message. */
@Entity(tableName = "investment_events", indices = [Index(value = ["smsKey"], unique = true)])
data class InvestmentEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsKey: String,
    val kind: String,
    val name: String,
    val amountMinor: Long,
    val redemption: Boolean,
    val units: String?,
    val currentValueMinor: Long?,
    val timestamp: Long,
) {
    fun toEvent() = InvestmentEvent(
        AssetKind.entries.firstOrNull { it.name == kind } ?: AssetKind.OTHER, name, amountMinor, redemption, units, currentValueMinor, timestamp,
    )
}

/** The user's own "current value" for a holding built from SMS, keyed by "KIND|name". */
@Entity(tableName = "value_overrides")
data class ValueOverrideEntity(@PrimaryKey val key: String, val currentMinor: Long, val updatedAt: Long)

@Entity(tableName = "liabilities")
data class LiabilityEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val kind: String,
    val outstandingMinor: Long,
) {
    fun toLiability() = Liability(name, LiabilityKind.entries.firstOrNull { it.name == kind } ?: LiabilityKind.OTHER, outstandingMinor)
}

@Entity(tableName = "bill_reminders")
data class BillReminderEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val kind: String,
    val amountMinor: Long?,
    val nextDueEpochDay: Long,
    val everyMonths: Int,
    val remindDaysBefore: Int,
    /** Comma-separated words that identify the payment in an SMS. */
    val matchWords: String,
    val lastPaidAt: Long? = null,
) {
    fun toBill() = BillReminder(
        id, name, ReminderKind.entries.firstOrNull { it.name == kind } ?: ReminderKind.OTHER, amountMinor,
        LocalDate.ofEpochDay(nextDueEpochDay), everyMonths, remindDaysBefore,
        matchWords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )
}

@Entity(tableName = "splits")
data class SplitEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    /** The transaction this split belongs to, if any. */
    val txnUid: String?,
    val description: String,
    val paidBy: String,
    val totalMinor: Long,
    /** "Name=paise;Name=paise". */
    val shares: String,
    val timestamp: Long,
) {
    fun toSplit() = SplitExpense(id, description, paidBy, totalMinor, decodeShares(shares), timestamp)

    companion object {
        fun encodeShares(shares: Map<String, Long>) =
            shares.entries.joinToString(";") { (k, v) -> "${k.replace(";", " ").replace("=", " ")}=$v" }

        fun decodeShares(s: String): Map<String, Long> = s.split(';').mapNotNull { part ->
            val i = part.lastIndexOf('=')
            if (i <= 0) null else part.substring(0, i) to (part.substring(i + 1).toLongOrNull() ?: return@mapNotNull null)
        }.toMap()
    }
}

@Entity(tableName = "settlements")
data class SettlementEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val fromPerson: String,
    val toPerson: String,
    val amountMinor: Long,
    val timestamp: Long,
) {
    fun toSettlement() = Settlement(fromPerson, toPerson, amountMinor, timestamp)
}

@Entity(tableName = "receipts", indices = [Index("txnUid")])
data class ReceiptEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val txnUid: String,
    /** File name inside the app's private receipts folder. */
    val fileName: String,
    val addedAt: Long,
)

/** A tax section given to a transaction. [section] "NONE" means the user said it isn't tax-saving. */
@Entity(tableName = "tax_tags")
data class TaxTagEntity(@PrimaryKey val txnUid: String, val section: String) {
    val taxSection: TaxSection? get() = TaxSection.entries.firstOrNull { it.name == section }
}

@Entity(tableName = "custom_rules")
data class CustomRuleEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val pattern: String,
    val field: String = RuleField.ANY.name,
    val isRegex: Boolean = false,
    val onlyType: String? = null,
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val setCategory: String? = null,
    val renameMerchant: String? = null,
    val exclude: Boolean = false,
    val ignore: Boolean = false,
    val taxSection: String? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
) {
    fun toRule() = CustomRule(
        id, pattern, RuleField.entries.firstOrNull { it.name == field } ?: RuleField.ANY, isRegex,
        onlyType?.let { t -> TxnType.entries.firstOrNull { it.name == t } }, minAmountMinor, maxAmountMinor,
        setCategory, renameMerchant, exclude, ignore, taxSection?.let { s -> TaxSection.entries.firstOrNull { it.name == s } },
        priority, enabled,
    )

    companion object {
        fun from(r: CustomRule) = CustomRuleEntity(
            r.id, r.pattern, r.field.name, r.isRegex, r.onlyType?.name, r.minAmountMinor, r.maxAmountMinor,
            r.setCategory, r.renameMerchant, r.exclude, r.ignore, r.taxSection?.name, r.priority, r.enabled,
        )
    }
}

@Dao
interface V12Dao {
    // Goals
    @Query("SELECT * FROM goals ORDER BY createdEpochDay") fun observeGoals(): Flow<List<GoalEntity>>
    @Upsert suspend fun upsertGoal(g: GoalEntity)
    @Query("DELETE FROM goals WHERE id = :id") suspend fun deleteGoal(id: String)

    // Net worth
    @Query("SELECT * FROM holdings ORDER BY name") fun observeHoldings(): Flow<List<HoldingEntity>>
    @Upsert suspend fun upsertHolding(h: HoldingEntity)
    @Query("DELETE FROM holdings WHERE id = :id") suspend fun deleteHolding(id: String)
    @Query("SELECT * FROM investment_events ORDER BY timestamp") fun observeEvents(): Flow<List<InvestmentEventEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(e: InvestmentEventEntity): Long
    @Query("DELETE FROM investment_events WHERE kind = :kind AND name = :name COLLATE NOCASE") suspend fun deleteEvents(kind: String, name: String)
    @Query("SELECT * FROM value_overrides") fun observeOverrides(): Flow<List<ValueOverrideEntity>>
    @Upsert suspend fun upsertOverride(o: ValueOverrideEntity)
    @Query("DELETE FROM value_overrides WHERE `key` = :key") suspend fun deleteOverride(key: String)
    @Query("SELECT * FROM liabilities ORDER BY name") fun observeLiabilities(): Flow<List<LiabilityEntity>>
    @Upsert suspend fun upsertLiability(l: LiabilityEntity)
    @Query("DELETE FROM liabilities WHERE id = :id") suspend fun deleteLiability(id: String)

    // Bills
    @Query("SELECT * FROM bill_reminders ORDER BY nextDueEpochDay") fun observeBills(): Flow<List<BillReminderEntity>>
    @Query("SELECT * FROM bill_reminders") suspend fun bills(): List<BillReminderEntity>
    @Upsert suspend fun upsertBill(b: BillReminderEntity)
    @Query("DELETE FROM bill_reminders WHERE id = :id") suspend fun deleteBill(id: String)

    // Splits
    @Query("SELECT * FROM splits ORDER BY timestamp DESC") fun observeSplits(): Flow<List<SplitEntity>>
    @Query("SELECT * FROM splits WHERE txnUid = :uid LIMIT 1") suspend fun splitFor(uid: String): SplitEntity?
    @Upsert suspend fun upsertSplit(s: SplitEntity)
    @Query("DELETE FROM splits WHERE id = :id") suspend fun deleteSplit(id: String)
    @Query("SELECT * FROM settlements ORDER BY timestamp DESC") fun observeSettlements(): Flow<List<SettlementEntity>>
    @Insert suspend fun insertSettlement(s: SettlementEntity)
    @Query("DELETE FROM settlements WHERE id = :id") suspend fun deleteSettlement(id: String)

    // Receipts
    @Query("SELECT * FROM receipts WHERE txnUid = :uid ORDER BY addedAt") fun observeReceipts(uid: String): Flow<List<ReceiptEntity>>
    @Query("SELECT * FROM receipts") suspend fun allReceipts(): List<ReceiptEntity>
    @Query("SELECT txnUid FROM receipts") fun observeReceiptUids(): Flow<List<String>>
    @Insert suspend fun insertReceipt(r: ReceiptEntity)
    @Query("DELETE FROM receipts WHERE id = :id") suspend fun deleteReceipt(id: String)

    // Tax
    @Query("SELECT * FROM tax_tags") fun observeTaxTags(): Flow<List<TaxTagEntity>>
    @Query("SELECT * FROM tax_tags WHERE txnUid = :uid") suspend fun taxTag(uid: String): TaxTagEntity?
    @Upsert suspend fun upsertTaxTag(t: TaxTagEntity)
    @Query("DELETE FROM tax_tags WHERE txnUid = :uid") suspend fun deleteTaxTag(uid: String)

    // Rules
    @Query("SELECT * FROM custom_rules ORDER BY priority, pattern") fun observeRules(): Flow<List<CustomRuleEntity>>
    @Query("SELECT * FROM custom_rules") suspend fun rules(): List<CustomRuleEntity>
    @Upsert suspend fun upsertRule(r: CustomRuleEntity)
    @Query("DELETE FROM custom_rules WHERE id = :id") suspend fun deleteRule(id: String)
}

@Database(
    entities = [
        GoalEntity::class, HoldingEntity::class, InvestmentEventEntity::class, ValueOverrideEntity::class,
        LiabilityEntity::class, BillReminderEntity::class, SplitEntity::class, SettlementEntity::class,
        ReceiptEntity::class, TaxTagEntity::class, CustomRuleEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class V12Database : RoomDatabase() {
    abstract fun dao(): V12Dao

    companion object {
        const val FILE = "myfinance-v12.db"
        fun create(context: Context): V12Database =
            Room.databaseBuilder(context, V12Database::class.java, FILE).build()
    }
}
