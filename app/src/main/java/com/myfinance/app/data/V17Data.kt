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
import kotlinx.coroutines.flow.Flow

/*
 * v1.7 data, in its own database file like every earlier version:
 *  - nicknames: the user's own name for a payment's payee ("Milk man"), kept apart from the merchant name,
 *    which a bank or card statement may replace with the official one.
 */

@Entity(tableName = "nicknames")
data class NicknameEntity(@PrimaryKey val txnUid: String, val name: String)

@Dao
interface V17Dao {
    @Query("SELECT * FROM nicknames") fun observeNicknames(): Flow<List<NicknameEntity>>
    @Query("SELECT * FROM nicknames") suspend fun nicknames(): List<NicknameEntity>
    @Query("SELECT name FROM nicknames WHERE txnUid = :uid") suspend fun nicknameOf(uid: String): String?
    @Upsert suspend fun putNickname(n: NicknameEntity)
    @Upsert suspend fun putNicknames(n: List<NicknameEntity>)
    @Query("DELETE FROM nicknames WHERE txnUid = :uid") suspend fun clearNickname(uid: String)
}

@Database(entities = [NicknameEntity::class], version = 1, exportSchema = true)
abstract class V17Database : RoomDatabase() {
    abstract fun dao(): V17Dao

    companion object {
        const val FILE = "myfinance-v17.db"

        fun create(context: Context): V17Database =
            Room.databaseBuilder(context, V17Database::class.java, FILE).build()
    }
}
