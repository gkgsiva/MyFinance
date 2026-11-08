package com.myfinance.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myfinance.app.MyFinanceApp
import com.myfinance.app.data.CategoryEntity
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.accountLabel
import com.myfinance.app.data.toLite
import com.myfinance.core.FinanceSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

enum class GroupBy(val label: String) { CATEGORY("Category"), MERCHANT("Merchant"), ACCOUNT("Account"), PERSON("Person") }

data class ImportState(val running: Boolean = false, val lastAdded: Int? = null, val error: String? = null)

data class SyncState(val running: Boolean = false, val message: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as MyFinanceApp).repository

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    val query = MutableStateFlow("")
    val categoryFilter = MutableStateFlow<String?>(null)
    val groupBy = MutableStateFlow(GroupBy.CATEGORY)

    private val _import = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _import.asStateFlow()

    val ownerName: StateFlow<String> = repo.ownerName

    private val _sync = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _sync.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> =
        repo.categories.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All transactions in the selected month, newest first. */
    val monthTransactions: StateFlow<List<TransactionEntity>> = _month
        .flatMapLatest { ym ->
            val zone = ZoneId.systemDefault()
            val from = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            repo.transactionsBetween(from, to)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val summary: StateFlow<FinanceSummary> = combine(monthTransactions, repo.ownerName) { list, me ->
        FinanceSummary.of(list.filterNot { it.excluded }.map { it.toLite(me) })
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FinanceSummary.of(emptyList()))

    /** The month's transactions after applying the search box and category filter. */
    val filteredTransactions: StateFlow<List<TransactionEntity>> =
        combine(monthTransactions, query, categoryFilter) { list, q, cat ->
            val needle = q.trim().lowercase()
            list.filter { t ->
                (cat == null || t.category == cat) &&
                    (needle.isEmpty() || listOfNotNull(t.merchant, t.note, t.category, t.accountLabel, t.owner)
                        .any { it.lowercase().contains(needle) })
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun previousMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { if (_month.value < YearMonth.now()) _month.value = _month.value.plusMonths(1) }

    fun importInbox() {
        if (_import.value.running) return
        _import.value = ImportState(running = true)
        viewModelScope.launch {
            _import.value = try {
                ImportState(lastAdded = repo.importInbox())
            } catch (e: SecurityException) {
                ImportState(error = "SMS permission is needed to import messages.")
            } catch (e: Exception) {
                ImportState(error = e.message ?: "Import failed")
            }
        }
    }

    fun setOwnerName(name: String) = repo.setOwnerName(name)

    fun exportTo(uri: Uri) = runSync {
        val n = repo.exportTo(uri)
        "Exported $n transactions. Share this file with family to merge on their phone."
    }

    fun mergeFrom(uri: Uri) = runSync {
        val r = repo.mergeFrom(uri)
        buildString {
            append("Added ${r.added} transactions")
            if (r.duplicates > 0) append(", skipped ${r.duplicates} duplicates")
            if (r.unreadable > 0) append(", ${r.unreadable} rows could not be read")
            append(".")
        }
    }

    private fun runSync(block: suspend () -> String) {
        if (_sync.value.running) return
        _sync.value = SyncState(running = true)
        viewModelScope.launch {
            _sync.value = try {
                SyncState(message = block())
            } catch (e: Exception) {
                SyncState(message = "Failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    suspend fun load(id: Long): TransactionEntity? = repo.get(id)

    fun save(txn: TransactionEntity, learnRule: Boolean) = viewModelScope.launch { repo.save(txn, learnRule) }
    fun delete(txn: TransactionEntity) = viewModelScope.launch { repo.delete(txn) }

    fun addCategory(name: String, budgetMinor: Long?) = viewModelScope.launch { repo.addCategory(name, budgetMinor) }
    fun updateCategory(c: CategoryEntity) = viewModelScope.launch { repo.updateCategory(c) }
    fun deleteCategory(name: String) = viewModelScope.launch { repo.deleteCategory(name) }
}
