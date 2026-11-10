package com.myfinance.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.myfinance.core.MyAccount
import kotlinx.coroutines.flow.Flow

/*
 * Next-release data, in its own database file like every earlier version:
 *  - my_accounts: the family's own cards and bank accounts by number (last 4, plus a card's first 6), whose
 *    each one is, and an optional PDF statement password. Used to put statements and SMS on the right card
 *    and person.
 */

@Entity(tableName = "my_accounts")
data class MyAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tail: String,
    val prefix: String? = null,
    val bank: String? = null,
    val isCard: Boolean = true,
    val name: String? = null,
    val owner: String? = null,
    val pdfPassword: String? = null,
    /** Added in version 2 of this database. */
    val creditLimitMinor: Long? = null,
) {
    fun toCore() = MyAccount(id, tail, prefix, bank, isCard, name, owner, pdfPassword, creditLimitMinor)
}

fun MyAccount.toEntity() = MyAccountEntity(id, tail, prefix, bank, isCard, name, owner, pdfPassword, creditLimitMinor)

/** One statement import: what it added, so the whole import can be undone. Added in version 3. */
@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** File names, one per line. */
    val files: String,
    val added: Int,
    val updated: Int,
    /** The uids of the entries it added, one per line. */
    val uids: String,
    val undone: Boolean = false,
    /** First and last date covered, as epoch days (0 when unknown). */
    val fromDay: Long = 0,
    val toDay: Long = 0,
)

@Dao
interface V18Dao {
    @Query("SELECT * FROM import_batches ORDER BY at DESC") fun observeBatches(): Flow<List<ImportBatchEntity>>
    @Query("SELECT * FROM import_batches WHERE id = :id") suspend fun batch(id: Long): ImportBatchEntity?
    @Upsert suspend fun putBatch(b: ImportBatchEntity): Long
    @Query("SELECT * FROM my_accounts ORDER BY owner IS NOT NULL, owner, bank, tail") fun observeAccounts(): Flow<List<MyAccountEntity>>
    @Query("SELECT * FROM my_accounts") suspend fun accounts(): List<MyAccountEntity>
    @Upsert suspend fun putAccount(a: MyAccountEntity): Long
    @Query("DELETE FROM my_accounts WHERE id = :id") suspend fun deleteAccount(id: Long)
}

@Database(entities = [MyAccountEntity::class, ImportBatchEntity::class], version = 3, exportSchema = true)
abstract class V18Database : RoomDatabase() {
    abstract fun dao(): V18Dao

    companion object {
        const val FILE = "myfinance-v18.db"

        fun create(context: Context): V18Database =
            Room.databaseBuilder(context, V18Database::class.java, FILE).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        /** 1.9: a card's credit limit. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE my_accounts ADD COLUMN creditLimitMinor INTEGER")
            }
        }

        /** 1.10: import history, so a whole import can be undone. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `import_batches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, " +
                        "`files` TEXT NOT NULL, `added` INTEGER NOT NULL, `updated` INTEGER NOT NULL, `uids` TEXT NOT NULL, " +
                        "`undone` INTEGER NOT NULL, `fromDay` INTEGER NOT NULL, `toDay` INTEGER NOT NULL)",
                )
            }
        }
    }
}
