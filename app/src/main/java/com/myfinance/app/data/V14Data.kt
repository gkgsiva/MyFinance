package com.myfinance.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * v1.4 data lives in its own database file, like every earlier version, so upgrading never touches
 * the tables that already hold the family's data:
 *  - label_budgets: a monthly limit per label (Vegetables, Amazon…).
 *  - label_suggestion_dismissed: "not this label" answers to auto-label suggestions.
 *  - refund_watch: payments waiting for a refund.
 *  - loan_details: interest rate per EMI plan, for the payoff calculator.
 *  - saved_reports: Explore views pinned to the Overview.
 */

@Entity(tableName = "label_budgets")
data class LabelBudgetEntity(@PrimaryKey val label: String, val monthlyMinor: Long)

@Entity(tableName = "label_suggestion_dismissed", primaryKeys = ["uid", "label"])
data class DismissedSuggestionEntity(val uid: String, val label: String)

@Entity(tableName = "refund_watch")
data class RefundWatchEntity(
    @PrimaryKey val uid: String,
    val expectedMinor: Long?,
    val since: Long,
    val closed: Boolean = false,
)

@Entity(tableName = "loan_details")
data class LoanDetailsEntity(@PrimaryKey val planId: String, val annualRatePct: Double)

@Entity(tableName = "saved_reports")
data class SavedReportEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** [com.myfinance.app.ui.ExploreKind] name. */
    val kind: String,
    val value: String,
    /** "DEBIT", "CREDIT" or null for net. */
    val type: String?,
    /** [com.myfinance.core.Granularity] name. */
    val granularity: String,
    /** "3m", "1y", "3y", "all", or "custom" with [fromEpochDay] and [toEpochDay]. */
    val range: String,
    val fromEpochDay: Long?,
    val toEpochDay: Long?,
    val createdAt: Long,
)

@Dao
interface V14Dao {
    @Query("SELECT * FROM label_budgets ORDER BY label")
    fun observeLabelBudgets(): Flow<List<LabelBudgetEntity>>

    @Query("SELECT * FROM label_budgets")
    suspend fun labelBudgets(): List<LabelBudgetEntity>

    @Upsert
    suspend fun putLabelBudget(b: LabelBudgetEntity)

    @Query("DELETE FROM label_budgets WHERE label = :label")
    suspend fun deleteLabelBudget(label: String)

    @Query("SELECT * FROM label_suggestion_dismissed")
    fun observeDismissed(): Flow<List<DismissedSuggestionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun dismiss(d: List<DismissedSuggestionEntity>)

    @Query("DELETE FROM label_suggestion_dismissed")
    suspend fun clearDismissed()

    @Query("SELECT * FROM refund_watch")
    fun observeRefunds(): Flow<List<RefundWatchEntity>>

    @Upsert
    suspend fun putRefund(r: RefundWatchEntity)

    @Query("DELETE FROM refund_watch WHERE uid = :uid")
    suspend fun deleteRefund(uid: String)

    @Query("SELECT * FROM loan_details")
    fun observeLoans(): Flow<List<LoanDetailsEntity>>

    @Upsert
    suspend fun putLoan(l: LoanDetailsEntity)

    @Query("SELECT * FROM saved_reports ORDER BY createdAt")
    fun observeReports(): Flow<List<SavedReportEntity>>

    @Upsert
    suspend fun putReport(r: SavedReportEntity)

    @Query("DELETE FROM saved_reports WHERE id = :id")
    suspend fun deleteReport(id: String)
}

@Database(
    entities = [
        LabelBudgetEntity::class, DismissedSuggestionEntity::class, RefundWatchEntity::class,
        LoanDetailsEntity::class, SavedReportEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class V14Database : RoomDatabase() {
    abstract fun dao(): V14Dao

    companion object {
        const val FILE = "myfinance-v14.db"

        fun create(context: Context): V14Database =
            Room.databaseBuilder(context, V14Database::class.java, FILE).build()
    }
}
