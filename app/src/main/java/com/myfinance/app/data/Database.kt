package com.myfinance.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import com.myfinance.core.TxnType
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    fun observeRange(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp")
    suspend fun allOnce(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE userEdited = 0 AND merchant IS NOT NULL")
    suspend fun uneditedWithMerchant(): List<TransactionEntity>

    @Query("SELECT MAX(timestamp) FROM transactions WHERE source = 'SMS'")
    suspend fun latestSmsTimestamp(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(txn: TransactionEntity): Long

    @Update
    suspend fun update(txn: TransactionEntity)

    @Update
    suspend fun updateAll(txns: List<TransactionEntity>)

    @Delete
    suspend fun delete(txn: TransactionEntity)

    @Query("UPDATE transactions SET category = :to WHERE category = :from")
    suspend fun renameCategory(from: String, to: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY builtIn DESC, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT name FROM categories")
    suspend fun names(): List<String>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE name = :name AND builtIn = 0")
    suspend fun deleteCustom(name: String)
}

@Dao
interface MerchantRuleDao {
    @Query("SELECT * FROM merchant_rules")
    suspend fun all(): List<MerchantRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantRuleEntity)

    @Query("UPDATE merchant_rules SET category = :to WHERE category = :from")
    suspend fun renameCategory(from: String, to: String)
}

class Converters {
    @TypeConverter fun fromType(t: TxnType): String = t.name
    @TypeConverter fun toType(s: String): TxnType = TxnType.valueOf(s)
    @TypeConverter fun fromSource(s: TxnSource): String = s.name
    @TypeConverter fun toSource(s: String): TxnSource = TxnSource.valueOf(s)
}

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, MerchantRuleEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun categories(): CategoryDao
    abstract fun merchantRules(): MerchantRuleDao

    companion object {
        fun create(context: Context): FinanceDatabase =
            Room.databaseBuilder(context, FinanceDatabase::class.java, "myfinance.db").build()
    }
}
