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
import com.myfinance.core.CapitalGainEntry
import com.myfinance.core.GainAsset
import com.myfinance.core.GoalLink
import com.myfinance.core.GoalLinkKind
import com.myfinance.core.LendDirection
import com.myfinance.core.LendEntry
import com.myfinance.core.LendRepayment
import com.myfinance.core.Policy
import com.myfinance.core.PolicyKind
import com.myfinance.core.Warranty
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/*
 * v1.5 data lives in its own database file, like every earlier version, so upgrading never touches
 * the tables that already hold the family's data:
 *  - policies, warranties: the insurance and warranty vault (photos are kept with the receipts, keyed "vault:<id>").
 *  - lend_entries, lend_repayments: money lent and borrowed.
 *  - goal_links: SIPs, holdings and labels that feed a goal; goal_returns: expected return per goal.
 *  - capital_gains: sales entered for the tax-time pack.
 *  - subscription_marks: "not used" and "not a subscription" answers in the subscription audit.
 *  - sent_reminders: reminder notifications already shown, so none repeats.
 */

private fun day(d: LocalDate?) = d?.toEpochDay()
private fun date(e: Long?) = e?.let(LocalDate::ofEpochDay)

@Entity(tableName = "policies")
data class PolicyEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val kind: String,
    val insurer: String,
    val policyNumber: String?,
    val coverMinor: Long?,
    val premiumMinor: Long?,
    val renewalEpochDay: Long?,
    val everyMonths: Int,
    val members: String?,
    val note: String?,
) {
    fun toPolicy() = Policy(
        id, name, PolicyKind.entries.firstOrNull { it.name == kind } ?: PolicyKind.OTHER, insurer, policyNumber,
        coverMinor, premiumMinor, date(renewalEpochDay), everyMonths, members, note,
    )

    companion object {
        fun from(p: Policy) = PolicyEntity(
            p.id, p.name, p.kind.name, p.insurer, p.policyNumber, p.coverMinor, p.premiumMinor, day(p.renewalDate),
            p.everyMonths, p.members, p.note,
        )
    }
}

@Entity(tableName = "warranties")
data class WarrantyEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val item: String,
    val shop: String?,
    val purchaseEpochDay: Long,
    val priceMinor: Long?,
    val months: Int,
    val note: String?,
) {
    fun toWarranty() = Warranty(id, item, shop, LocalDate.ofEpochDay(purchaseEpochDay), priceMinor, months, note)

    companion object {
        fun from(w: Warranty) = WarrantyEntity(w.id, w.item, w.shop, w.purchaseDate.toEpochDay(), w.priceMinor, w.months, w.note)
    }
}

@Entity(tableName = "lend_entries")
data class LendEntryEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val person: String,
    val direction: String,
    val amountMinor: Long,
    val epochDay: Long,
    val dueEpochDay: Long?,
    val note: String?,
    val closed: Boolean = false,
) {
    fun toEntry() = LendEntry(
        id, person, LendDirection.entries.firstOrNull { it.name == direction } ?: LendDirection.LENT, amountMinor,
        LocalDate.ofEpochDay(epochDay), date(dueEpochDay), note, closed,
    )

    companion object {
        fun from(e: LendEntry) = LendEntryEntity(e.id, e.person, e.direction.name, e.amountMinor, e.date.toEpochDay(), day(e.dueDate), e.note, e.closed)
    }
}

@Entity(tableName = "lend_repayments", indices = [Index("entryId")])
data class LendRepaymentEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val entryId: String,
    val amountMinor: Long,
    val epochDay: Long,
) {
    fun toRepayment() = LendRepayment(id, entryId, amountMinor, LocalDate.ofEpochDay(epochDay))
}

@Entity(tableName = "goal_links", indices = [Index("goalId")])
data class GoalLinkEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val goalId: String,
    val kind: String,
    val ref: String,
    val sinceEpochDay: Long,
    val monthlyMinor: Long?,
) {
    fun toLink() = GoalLink(
        id, goalId, GoalLinkKind.entries.firstOrNull { it.name == kind } ?: GoalLinkKind.LABEL, ref,
        LocalDate.ofEpochDay(sinceEpochDay), monthlyMinor,
    )
}

@Entity(tableName = "goal_returns")
data class GoalReturnEntity(@PrimaryKey val goalId: String, val annualPct: Double)

@Entity(tableName = "capital_gains")
data class CapitalGainEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val asset: String,
    val buyEpochDay: Long,
    val buyMinor: Long,
    val sellEpochDay: Long,
    val sellMinor: Long,
) {
    fun toEntry() = CapitalGainEntry(
        id, name, GainAsset.entries.firstOrNull { it.name == asset } ?: GainAsset.OTHER,
        LocalDate.ofEpochDay(buyEpochDay), buyMinor, LocalDate.ofEpochDay(sellEpochDay), sellMinor,
    )
}

/** [mark] is "NOT_USED" or "IGNORE" (not a subscription). */
@Entity(tableName = "subscription_marks")
data class SubscriptionMarkEntity(@PrimaryKey val key: String, val mark: String)

@Entity(tableName = "sent_reminders")
data class SentReminderEntity(@PrimaryKey val key: String, val sentAt: Long)

@Dao
interface V15Dao {
    @Query("SELECT * FROM policies ORDER BY renewalEpochDay IS NULL, renewalEpochDay")
    fun observePolicies(): Flow<List<PolicyEntity>>

    @Query("SELECT * FROM policies")
    suspend fun policies(): List<PolicyEntity>

    @Upsert suspend fun putPolicy(p: PolicyEntity)

    @Query("DELETE FROM policies WHERE id = :id")
    suspend fun deletePolicy(id: String)

    @Query("SELECT * FROM warranties ORDER BY purchaseEpochDay DESC")
    fun observeWarranties(): Flow<List<WarrantyEntity>>

    @Query("SELECT * FROM warranties")
    suspend fun warranties(): List<WarrantyEntity>

    @Upsert suspend fun putWarranty(w: WarrantyEntity)

    @Query("DELETE FROM warranties WHERE id = :id")
    suspend fun deleteWarranty(id: String)

    @Query("SELECT * FROM lend_entries ORDER BY epochDay DESC")
    fun observeLending(): Flow<List<LendEntryEntity>>

    @Query("SELECT * FROM lend_entries")
    suspend fun lending(): List<LendEntryEntity>

    @Upsert suspend fun putLend(e: LendEntryEntity)

    @Query("DELETE FROM lend_entries WHERE id = :id")
    suspend fun deleteLend(id: String)

    @Query("SELECT * FROM lend_repayments ORDER BY epochDay")
    fun observeRepayments(): Flow<List<LendRepaymentEntity>>

    @Query("SELECT * FROM lend_repayments")
    suspend fun repayments(): List<LendRepaymentEntity>

    @Upsert suspend fun putRepayment(r: LendRepaymentEntity)

    @Query("DELETE FROM lend_repayments WHERE id = :id")
    suspend fun deleteRepayment(id: String)

    @Query("DELETE FROM lend_repayments WHERE entryId = :entryId")
    suspend fun deleteRepaymentsOf(entryId: String)

    @Query("SELECT * FROM goal_links")
    fun observeGoalLinks(): Flow<List<GoalLinkEntity>>

    @Upsert suspend fun putGoalLink(l: GoalLinkEntity)

    @Query("DELETE FROM goal_links WHERE id = :id")
    suspend fun deleteGoalLink(id: String)

    @Query("SELECT * FROM goal_returns")
    fun observeGoalReturns(): Flow<List<GoalReturnEntity>>

    @Upsert suspend fun putGoalReturn(r: GoalReturnEntity)

    @Query("SELECT * FROM capital_gains ORDER BY sellEpochDay")
    fun observeGains(): Flow<List<CapitalGainEntity>>

    @Upsert suspend fun putGain(g: CapitalGainEntity)

    @Query("DELETE FROM capital_gains WHERE id = :id")
    suspend fun deleteGain(id: String)

    @Query("SELECT * FROM subscription_marks")
    fun observeMarks(): Flow<List<SubscriptionMarkEntity>>

    @Upsert suspend fun putMark(m: SubscriptionMarkEntity)

    @Query("DELETE FROM subscription_marks WHERE `key` = :key")
    suspend fun deleteMark(key: String)

    @Query("SELECT COUNT(*) FROM sent_reminders WHERE `key` = :key")
    suspend fun wasSent(key: String): Int

    @Upsert suspend fun markSent(s: SentReminderEntity)

    @Query("DELETE FROM sent_reminders WHERE sentAt < :before")
    suspend fun purgeSent(before: Long)
}

@Database(
    entities = [
        PolicyEntity::class, WarrantyEntity::class, LendEntryEntity::class, LendRepaymentEntity::class,
        GoalLinkEntity::class, GoalReturnEntity::class, CapitalGainEntity::class, SubscriptionMarkEntity::class,
        SentReminderEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class V15Database : RoomDatabase() {
    abstract fun dao(): V15Dao

    companion object {
        const val FILE = "myfinance-v15.db"

        fun create(context: Context): V15Database =
            Room.databaseBuilder(context, V15Database::class.java, FILE).build()
    }
}
