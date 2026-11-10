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

@Dao
interface V18Dao {
    @Query("SELECT * FROM my_accounts ORDER BY owner IS NOT NULL, owner, bank, tail") fun observeAccounts(): Flow<List<MyAccountEntity>>
    @Query("SELECT * FROM my_accounts") suspend fun accounts(): List<MyAccountEntity>
    @Upsert suspend fun putAccount(a: MyAccountEntity): Long
    @Query("DELETE FROM my_accounts WHERE id = :id") suspend fun deleteAccount(id: Long)
}

@Database(entities = [MyAccountEntity::class], version = 2, exportSchema = true)
abstract class V18Database : RoomDatabase() {
    abstract fun dao(): V18Dao

    companion object {
        const val FILE = "myfinance-v18.db"

        fun create(context: Context): V18Database =
            Room.databaseBuilder(context, V18Database::class.java, FILE).addMigrations(MIGRATION_1_2).build()

        /** 1.9: a card's credit limit. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE my_accounts ADD COLUMN creditLimitMinor INTEGER")
            }
        }
    }
}
