package com.myfinance.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.myfinance.app.MyFinanceApp
import com.myfinance.app.data.StatementFiles
import com.myfinance.core.ExistingTxn
import com.myfinance.core.ImportCandidate
import com.myfinance.core.ParsedStatement
import com.myfinance.core.StatementImport
import com.myfinance.core.StatementKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One picked statement file and how far it has got. */
data class StatementFile(
    val uri: Uri,
    val name: String,
    val status: Status = Status.READING,
    val statement: ParsedStatement? = null,
    val bank: String? = null,
    val accountTail: String? = null,
    val kind: StatementKind? = null,
    val candidates: List<ImportCandidate> = emptyList(),
    val message: String? = null,
    val wrongPassword: Boolean = false,
    /** The registered card / account (Manage > My cards & accounts) this statement was matched to. */
    val matched: com.myfinance.core.MyAccount? = null,
    /** While reading a PDF: pages read so far and in all. */
    val pagesRead: Int = 0,
    val pages: Int = 0,
) {
    enum class Status { READING, NEEDS_PASSWORD, FAILED, READY }

    val summary get() = StatementImport.summary(candidates)
}

data class StatementImportState(
    val files: List<StatementFile> = emptyList(),
    val importing: Boolean = false,
    val result: String? = null,
    /** While importing: lines done so far and in all. */
    val importDone: Int = 0,
    val importTotal: Int = 0,
    /** After an import: what it added per financial year. */
    val years: List<com.myfinance.core.YearTotals> = emptyList(),
) {
    val ready get() = files.filter { it.status == StatementFile.Status.READY }
    val newCount get() = ready.sumOf { it.summary.newCount }
    val updateCount get() = ready.sumOf { it.summary.updateCount }
    val busy get() = importing || files.any { it.status == StatementFile.Status.READING }

    /** One preview over every ready file: period, lines, new, already there. */
    val from get() = ready.mapNotNull { it.summary.from }.minOrNull()
    val to get() = ready.mapNotNull { it.summary.to }.maxOrNull()
    val lineCount get() = ready.sumOf { it.candidates.size }
    val alreadyCount get() = ready.sumOf { it.summary.duplicateCount }
}

/**
 * Bulk import of bank and credit card statements: pick any number of files (several years at
 * once), unlock password-protected PDFs, preview what is new, then add it all in one go.
 */
class StatementImportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as MyFinanceApp).repository
    /** Reading and importing run in the app's scope: leaving the screen doesn't stop them. */
    private val scope = (app as MyFinanceApp).appScope

    private val _state = shared
    val state: StateFlow<StatementImportState> = _state.asStateFlow()

    private fun track(job: kotlinx.coroutines.Job) {
        synchronized(jobs) { jobs.removeAll { !it.isActive }; jobs += job }
    }

    /** Stops reading and importing. Lines already added stay; importing the same files again skips them. */
    fun cancel() {
        synchronized(jobs) { jobs.forEach { it.cancel() }; jobs.clear() }
        val s = _state.value
        _state.update { st ->
            st.copy(
                importing = false,
                files = st.files.map { f -> if (f.status == StatementFile.Status.READING) f.copy(status = StatementFile.Status.FAILED, message = "Stopped. Remove it and pick it again to read it.") else f },
                result = if (s.importing) "Stopped after ${s.importDone} of ${s.importTotal} lines. What was added stays; importing the same files again adds only the rest." else st.result,
            )
        }
        existing = null
    }

    fun addFiles(uris: List<Uri>) {
        val known = _state.value.files.map { it.uri }.toSet()
        val fresh = uris.distinct().filter { it !in known }
        if (fresh.isEmpty()) return
        val ctx = getApplication<Application>()
        _state.update { s -> s.copy(files = s.files + fresh.map { StatementFile(it, StatementFiles.displayName(ctx, it)) }, result = null) }
        track(scope.launch {
            for (uri in fresh) open(uri, null)
            recompute()
        })
    }

    fun submitPassword(uri: Uri, password: String) {
        val pw = password.trim()
        if (pw.isEmpty()) return
        setFile(uri) { it.copy(status = StatementFile.Status.READING, wrongPassword = false) }
        track(scope.launch {
            open(uri, pw)
            // The same password usually opens the bank's other statements too.
            if (pw in passwords) {
                _state.value.files.filter { it.status == StatementFile.Status.NEEDS_PASSWORD }.forEach { open(it.uri, pw, quietWrong = true) }
            }
            recompute()
        })
    }

    fun remove(uri: Uri) {
        _state.update { s -> s.copy(files = s.files.filterNot { it.uri == uri }) }
        scope.launch { recompute() }
    }

    /** The user corrected the bank or the account / card's last 4 digits. */
    fun setAccount(uri: Uri, bank: String?, tail: String?) {
        setFile(uri) { it.copy(bank = bank?.trim()?.ifBlank { null }, accountTail = tail?.filter(Char::isDigit)?.takeLast(4)?.ifBlank { null }, matched = null) }
        scope.launch { recompute() }
    }

    fun clear() {
        _state.value = StatementImportState()
        existing = null
    }

    fun importAll() {
        val s = _state.value
        if (s.busy) return
        val rows = s.ready.flatMap { f -> f.candidates.filter { it.duplicateOf == null || it.updatesExisting } }
        _state.update { it.copy(importing = true, importDone = 0, importTotal = rows.size) }
        track(scope.launch {
            val added = runCatching { repo.addImported(rows) { done, total -> _state.update { st -> st.copy(importDone = done, importTotal = total) } } }
            if (added.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            // Card statements: keep the bill printed on them for the card bill check.
            for (f in s.ready) {
                val st = f.statement ?: continue
                val total = st.billTotalMinor ?: continue
                val due = st.billDueDate ?: continue
                runCatching { repo.addStatementBill(f.bank ?: st.bank, f.accountTail ?: st.accountTail, total, st.billMinMinor, due) }
            }
            existing = null
            val files = s.ready.size
            added.getOrNull()?.let { r ->
                repo.recordImport(s.ready.map { it.name }, r, s.from, s.to)
            }
            // What was added, per financial year (each account kept apart: balances come only from each account's own lines).
            val addedSet = added.getOrNull()?.addedUids?.toHashSet().orEmpty()
            val years = com.myfinance.core.ImportYears.of(
                rows.filter { it.uid in addedSet }.map { c ->
                    com.myfinance.core.TxnLite(c.row.amountMinor, c.row.type, com.myfinance.core.DefaultCategories.OTHER, c.merchant, listOfNotNull(c.bank, c.accountTail?.let { "••$it" }).joinToString(" ").ifBlank { null }, c.timestamp)
                },
            )
            val from = s.ready.mapNotNull { it.summary.from }.minOrNull()
            val to = s.ready.mapNotNull { it.summary.to }.maxOrNull()
            val span = if (from != null && to != null) " covering ${from.format(dayFormat)} to ${to.format(dayFormat)}" else ""
            _state.value = StatementImportState(
                result = added.fold(
                    { r ->
                        val upd = if (r.updated > 0) " ${r.updated} entries from SMS now use the statement's details." else ""
                        val closed = if (r.skippedClosed > 0) " ${r.skippedClosed} lines in months you closed were left out; reopen the month under Plan > Monthly close to bring them in." else ""
                        "Added ${r.added} transactions from $files statement${if (files == 1) "" else "s"}$span.$upd$closed Reports now include these years."
                    },
                    { e -> "Import failed: ${e.message}" },
                ),
                years = years,
            )
            // Finished while the screen may be closed: say so.
            runCatching {
                com.myfinance.app.notify.Notifier.reminder(
                    getApplication(), "statement-import".hashCode(), "Statements imported",
                    _state.value.result.orEmpty().take(200),
                )
            }
        })
    }

    private suspend fun open(uri: Uri, password: String?, quietWrong: Boolean = false) {
        val saved = runCatching { com.myfinance.core.MyAccounts.passwords(repo.myAccounts.first()) }.getOrDefault(emptyList())
        val tryList = if (password != null) listOf(password) else (listOf<String?>(null) + passwords + saved).distinct()
        var last: StatementFiles.Result? = null
        for (pw in tryList) {
            val r = StatementFiles.read(getApplication(), uri, pw) { page, pages ->
                setFile(uri) { it.copy(pagesRead = page, pages = pages) }
            }
            last = r
            if (r is StatementFiles.Result.Ok) {
                pw?.let { passwords += it }
                // A registered card or account printed on the statement decides the account (and so the person).
                val mine = runCatching { com.myfinance.core.MyAccounts.forStatement(repo.myAccounts.first(), r.statement) }.getOrNull()
                setFile(uri) {
                    it.copy(
                        status = StatementFile.Status.READY, statement = r.statement, bank = mine?.bank ?: r.statement.bank,
                        accountTail = mine?.tail ?: r.statement.accountTail, kind = r.statement.kind, message = null, wrongPassword = false,
                        matched = mine,
                    )
                }
                return
            }
            if (r !is StatementFiles.Result.NeedsPassword) break
        }
        when (val r = last) {
            is StatementFiles.Result.NeedsPassword -> setFile(uri) {
                it.copy(status = StatementFile.Status.NEEDS_PASSWORD, wrongPassword = r.wrong && password != null && !quietWrong)
            }
            is StatementFiles.Result.Failed -> setFile(uri) { it.copy(status = StatementFile.Status.FAILED, message = r.message) }
            else -> {}
        }
    }

    /**
     * Works out what is new in each file: compared with what is stored, and with the files listed
     * before it, so overlapping statements in one batch are only counted once.
     */
    private suspend fun recompute() = lock.withLock {
        val stored = existing ?: repo.existingForImport().also { existing = it }
        val files = _state.value.files
        val updated = withContext(Dispatchers.Default) {
            val seen = ArrayList(stored)
            files.map { f ->
                val st = f.statement
                if (f.status != StatementFile.Status.READY || st == null) return@map f
                val marked = StatementImport.markDuplicates(StatementImport.candidates(st, f.bank, f.accountTail), seen)
                marked.filter { it.duplicateOf == null }.forEach { c ->
                    seen += ExistingTxn(c.row.amountMinor, c.row.type, c.timestamp, c.accountTail, c.row.reference, c.key, fromStatement = true, text = c.row.description)
                }
                f.copy(candidates = marked)
            }
        }
        _state.update { s -> s.copy(files = s.files.map { f -> updated.firstOrNull { it.uri == f.uri && it.statement === f.statement && it.bank == f.bank && it.accountTail == f.accountTail } ?: f }) }
    }

    private fun setFile(uri: Uri, change: (StatementFile) -> StatementFile) {
        _state.update { s -> s.copy(files = s.files.map { if (it.uri == uri) change(it) else it }) }
    }

    companion object {
        /** Kept for the whole app session, so coming back to the screen shows an import still running. */
        private val shared = MutableStateFlow(StatementImportState())
        /** Passwords that opened a file this session; a bank uses one password for every statement. */
        private val passwords = LinkedHashSet<String>()
        @Volatile private var existing: List<ExistingTxn>? = null
        private val lock = Mutex()
        private val jobs = ArrayList<kotlinx.coroutines.Job>()

        private val dayFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
    }
}
