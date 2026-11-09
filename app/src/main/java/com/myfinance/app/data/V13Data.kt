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
import com.myfinance.core.AutoDebitKind
import com.myfinance.core.AutoDebitPlan
import com.myfinance.core.TxnType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/*
 * v1.3 data lives in its own database file, like v1.1 and v1.2, so upgrading never migrates the
 * existing tables:
 *  - deleted_transactions: the "Recently deleted" bin, a full copy of each deleted row so it can be restored.
 *  - deleted_keys: a record of every deleted row (uid and SMS key) kept even after the bin is emptied,
 *    so a rescan of the inbox or a family merge never brings it back.
 *  - auto_debits: EMIs and auto debits the user added or confirmed.
 */

@Entity(tableName = "deleted_transactions")
data class DeletedTxnEntity(
    @PrimaryKey val uid: String,
    val originalId: Long,
    val amountMinor: Long,
    val type: String,
    val merchant: String?,
    val category: String,
    val accountTail: String?,
    val bank: String?,
    val timestamp: Long,
    val note: String?,
    val source: String,
    val smsKey: String?,
    val rawSms: String?,
    val reference: String?,
    val balanceMinor: Long?,
    val userEdited: Boolean,
    val excluded: Boolean,
    val owner: String?,
    val deletedAt: Long,
) {
    fun toEntity() = TransactionEntity(
        id = originalId, amountMinor = amountMinor, type = TxnType.valueOf(type), merchant = merchant,
        category = category, accountTail = accountTail, bank = bank, timestamp = timestamp, note = note,
        source = TxnSource.entries.firstOrNull { it.name == source } ?: TxnSource.MANUAL,
        smsKey = smsKey, rawSms = rawSms, reference = reference, balanceMinor = balanceMinor,
        userEdited = userEdited, excluded = excluded, uid = uid, owner = owner,
    )

    companion object {
        fun from(t: TransactionEntity, now: Long = System.currentTimeMillis()) = DeletedTxnEntity(
            uid = t.uid, originalId = t.id, amountMinor = t.amountMinor, type = t.type.name, merchant = t.merchant,
            category = t.category, accountTail = t.accountTail, bank = t.bank, timestamp = t.timestamp, note = t.note,
            source = t.source.name, smsKey = t.smsKey, rawSms = t.rawSms, reference = t.reference,
            balanceMinor = t.balanceMinor, userEdited = t.userEdited, excluded = t.excluded, owner = t.owner,
            deletedAt = now,
        )
    }
}

@Entity(tableName = "deleted_keys", indices = [Index("smsKey")])
data class DeletedKeyEntity(
    @PrimaryKey val uid: String,
    val smsKey: String?,
    val deletedAt: Long,
)

@Entity(tableName = "auto_debits")
data class AutoDebitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val amountMinor: Long,
    val firstDueEpochDay: Long,
    val everyMonths: Int,
    val endEpochDay: Long?,
    /** Comma-separated. */
    val matchWords: String,
    val account: String?,
) {
    fun toPlan() = AutoDebitPlan(
        id = id, name = name,
        kind = AutoDebitKind.entries.firstOrNull { it.name == kind } ?: AutoDebitKind.OTHER,
        amountMinor = amountMinor, firstDue = LocalDate.ofEpochDay(firstDueEpochDay), everyMonths = everyMonths,
        endDate = endEpochDay?.let(LocalDate::ofEpochDay),
        matchWords = matchWords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        account = account,
    )

    companion object {
        fun from(p: AutoDebitPlan) = AutoDebitEntity(
            id = p.id, name = p.name, kind = p.kind.name, amountMinor = p.amountMinor,
            firstDueEpochDay = p.firstDue.toEpochDay(), everyMonths = p.everyMonths,
            endEpochDay = p.endDate?.toEpochDay(), matchWords = p.matchWords.joinToString(","), account = p.account,
        )
    }
}

/** Detected rows the user chose to hide from the auto debit list. */
@Entity(tableName = "auto_debit_hidden")
data class HiddenAutoDebitEntity(@PrimaryKey val key: String)

@Dao
interface V13Dao {
    @Query("SELECT * FROM deleted_transactions ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<DeletedTxnEntity>>

    @Query("SELECT * FROM deleted_transactions")
    suspend fun trash(): List<DeletedTxnEntity>

    @Query("SELECT * FROM deleted_transactions WHERE uid = :uid")
    suspend fun trashed(uid: String): DeletedTxnEntity?

    @Upsert
    suspend fun putTrash(t: DeletedTxnEntity)

    @Query("DELETE FROM deleted_transactions WHERE uid = :uid")
    suspend fun removeTrash(uid: String)

    @Query("DELETE FROM deleted_transactions")
    suspend fun emptyTrash()

    @Query("DELETE FROM deleted_transactions WHERE deletedAt < :before")
    suspend fun purgeTrashBefore(before: Long)

    @Upsert
    suspend fun putKey(k: DeletedKeyEntity)

    @Query("DELETE FROM deleted_keys WHERE uid = :uid")
    suspend fun removeKey(uid: String)

    @Query("SELECT * FROM deleted_keys")
    suspend fun keys(): List<DeletedKeyEntity>

    @Query("SELECT COUNT(*) FROM deleted_keys WHERE smsKey = :smsKey")
    suspend fun isSmsDeleted(smsKey: String): Int

    @Query("DELETE FROM deleted_keys WHERE smsKey IS NOT NULL")
    suspend fun forgetDeletedSms()

    @Query("SELECT * FROM auto_debits ORDER BY name")
    fun observePlans(): Flow<List<AutoDebitEntity>>

    @Upsert
    suspend fun upsertPlan(p: AutoDebitEntity)

    @Query("DELETE FROM auto_debits WHERE id = :id")
    suspend fun deletePlan(id: String)

    @Query("SELECT * FROM auto_debit_hidden")
    fun observeHidden(): Flow<List<HiddenAutoDebitEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hide(h: HiddenAutoDebitEntity)

    @Query("DELETE FROM auto_debit_hidden")
    suspend fun unhideAll()
}

@Database(
    entities = [DeletedTxnEntity::class, DeletedKeyEntity::class, AutoDebitEntity::class, HiddenAutoDebitEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class V13Database : RoomDatabase() {
    abstract fun dao(): V13Dao

    companion object {
        const val FILE = "myfinance-v13.db"

        fun create(context: Context): V13Database =
            Room.databaseBuilder(context, V13Database::class.java, FILE).build()
    }
}
