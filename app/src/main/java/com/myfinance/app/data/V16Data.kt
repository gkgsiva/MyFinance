package com.myfinance.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * v1.6 data, in its own database file like every earlier version:
 *  - split_parts: extra parts of a split payment (the rest stays on the payment itself).
 *  - family_people: who made a family UPI payment, when the user set it; family_limits: monthly limit per person.
 *  - closed_months: months the user reviewed and closed; late_arrivals: entries added to a closed month afterwards.
 *  - review_answers: answers in the review inbox, transfers and health check ("looks right", "not a duplicate").
 */

@Entity(tableName = "split_parts", indices = [Index("txnUid")])
data class SplitPartEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val txnUid: String,
    val category: String,
    val amountMinor: Long,
    val note: String?,
)

@Entity(tableName = "family_people")
data class FamilyPersonEntity(@PrimaryKey val txnUid: String, val person: String)

@Entity(tableName = "family_limits")
data class FamilyLimitEntity(@PrimaryKey val person: String, val monthlyMinor: Long)

@Entity(tableName = "closed_months")
data class ClosedMonthEntity(@PrimaryKey val month: String, val closedAt: Long)

@Entity(tableName = "late_arrivals")
data class LateArrivalEntity(@PrimaryKey val txnUid: String, val month: String, val addedAt: Long)

@Entity(tableName = "review_answers")
data class ReviewAnswerEntity(@PrimaryKey val key: String, val answer: String, val at: Long = System.currentTimeMillis())

@Dao
interface V16Dao {
    @Query("SELECT * FROM split_parts") fun observeParts(): Flow<List<SplitPartEntity>>
    @Query("SELECT * FROM split_parts WHERE txnUid = :uid") suspend fun partsOf(uid: String): List<SplitPartEntity>
    @Upsert suspend fun putParts(parts: List<SplitPartEntity>)
    @Query("DELETE FROM split_parts WHERE txnUid = :uid") suspend fun clearParts(uid: String)

    @Query("SELECT * FROM family_people") fun observePeople(): Flow<List<FamilyPersonEntity>>
    @Upsert suspend fun putPerson(p: FamilyPersonEntity)
    @Query("DELETE FROM family_people WHERE txnUid = :uid") suspend fun clearPerson(uid: String)

    @Query("SELECT * FROM family_limits") fun observeLimits(): Flow<List<FamilyLimitEntity>>
    @Query("SELECT * FROM family_limits") suspend fun limits(): List<FamilyLimitEntity>
    @Upsert suspend fun putLimit(l: FamilyLimitEntity)
    @Query("DELETE FROM family_limits WHERE person = :person") suspend fun deleteLimit(person: String)

    @Query("SELECT * FROM closed_months") fun observeClosed(): Flow<List<ClosedMonthEntity>>
    @Query("SELECT * FROM closed_months") suspend fun closed(): List<ClosedMonthEntity>
    @Upsert suspend fun close(m: ClosedMonthEntity)
    @Query("DELETE FROM closed_months WHERE month = :month") suspend fun reopen(month: String)

    @Query("SELECT * FROM late_arrivals") fun observeLate(): Flow<List<LateArrivalEntity>>
    @Upsert suspend fun putLate(l: LateArrivalEntity)
    @Query("DELETE FROM late_arrivals WHERE txnUid = :uid") suspend fun clearLate(uid: String)
    @Query("DELETE FROM late_arrivals WHERE month = :month") suspend fun clearLateMonth(month: String)

    @Query("SELECT * FROM review_answers") fun observeAnswers(): Flow<List<ReviewAnswerEntity>>
    @Upsert suspend fun answer(a: ReviewAnswerEntity)
    @Query("DELETE FROM review_answers WHERE `key` = :key") suspend fun forget(key: String)
}

@Database(
    entities = [
        SplitPartEntity::class, FamilyPersonEntity::class, FamilyLimitEntity::class, ClosedMonthEntity::class,
        LateArrivalEntity::class, ReviewAnswerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class V16Database : RoomDatabase() {
    abstract fun dao(): V16Dao

    companion object {
        const val FILE = "myfinance-v16.db"

        fun create(context: Context): V16Database =
            Room.databaseBuilder(context, V16Database::class.java, FILE).build()
    }
}
