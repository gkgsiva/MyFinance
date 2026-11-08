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
import com.myfinance.core.BalanceReading
import com.myfinance.core.BillDue
import com.myfinance.core.BillKind
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/*
 * v1.1 data (bills, balances, payment modes of merged rows) lives in its own small database file,
 * next to the v1.0 one. That way upgrading never needs a schema migration of the transactions
 * table, so nobody's existing data is at risk.
 */

@Entity(tableName = "bills", indices = [Index(value = ["smsKey"], unique = true)])
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsKey: String,
    val kind: String,
    val amountMinor: Long,
    val minDueMinor: Long?,
    /** Due date as epoch day. */
    val dueEpochDay: Long,
    val accountTail: String?,
    val bank: String?,
    val receivedAt: Long,
    /** Set when the user taps "Mark paid". */
    val markedPaid: Boolean = false,
) {
    fun toBill() = BillDue(
        kind = BillKind.entries.firstOrNull { it.name == kind } ?: BillKind.EMI,
        amountMinor = amountMinor,
        minDueMinor = minDueMinor,
        dueDate = LocalDate.ofEpochDay(dueEpochDay),
        accountTail = accountTail,
        bank = bank,
    )
}

@Entity(tableName = "balances", indices = [Index(value = ["smsKey"], unique = true)])
data class BalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsKey: String,
    val bank: String?,
    val accountTail: String?,
    val amountMinor: Long,
    val isCardLimit: Boolean,
    val timestamp: Long,
) {
    fun toReading() = BalanceReading(bank, accountTail, amountMinor, isCardLimit, timestamp)
}

/** Payment mode of a transaction merged from another phone (its SMS is not on this phone). */
@Entity(tableName = "txn_modes")
data class TxnModeEntity(
    @PrimaryKey val uid: String,
    val mode: String,
)

@Dao
interface ExtrasDao {
    @Query("SELECT * FROM bills ORDER BY dueEpochDay DESC")
    fun observeBills(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE dueEpochDay >= :fromEpochDay ORDER BY dueEpochDay")
    suspend fun billsFrom(fromEpochDay: Long): List<BillEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBill(bill: BillEntity): Long

    @Query("UPDATE bills SET markedPaid = :paid WHERE id = :id")
    suspend fun setBillPaid(id: Long, paid: Boolean)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteBill(id: Long)

    @Query("SELECT * FROM balances ORDER BY timestamp")
    fun observeBalances(): Flow<List<BalanceEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBalance(balance: BalanceEntity): Long

    @Query("SELECT * FROM txn_modes")
    fun observeModes(): Flow<List<TxnModeEntity>>

    @Query("SELECT * FROM txn_modes")
    suspend fun modes(): List<TxnModeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModes(modes: List<TxnModeEntity>)
}

@Database(
    entities = [BillEntity::class, BalanceEntity::class, TxnModeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ExtrasDatabase : RoomDatabase() {
    abstract fun extras(): ExtrasDao

    companion object {
        fun create(context: Context): ExtrasDatabase =
            Room.databaseBuilder(context, ExtrasDatabase::class.java, "myfinance-extras.db").build()
    }
}
