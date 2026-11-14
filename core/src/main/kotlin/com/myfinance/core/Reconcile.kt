package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Finds where a bank account's recorded entries stop agreeing with the balance the bank gave (in its SMS or
 * on its statement), so a gap in the PDF's bank reconciliation or the import health check can point at the
 * entry that is missing or counted twice.
 *
 * Walks the account's entries in order, carrying the balance forward. When an entry's own balance differs
 * from the carried one, the gap sits between it and the last entry whose balance agreed:
 * - an entry in that stretch whose amount is the gap, the wrong way round, is probably counted twice
 *   (the bank never moved that money, or it is a second copy of another entry);
 * - otherwise an entry of that amount is missing in that stretch.
 */
enum class SuspectKind { MISSING, DOUBLED }

data class Suspect(
    val account: String,
    val kind: SuspectKind,
    /** Size of the gap: the missing amount, or the doubled entry's amount. */
    val amountMinor: Long,
    /** The direction of the missing entry (CREDIT = money in is missing), or of the doubled one. */
    val type: TxnType,
    /** The last entry whose balance agreed (null at the start of what was checked). */
    val afterId: Long?,
    /** The first entry whose balance disagreed. */
    val beforeId: Long,
    val from: LocalDate?,
    val to: LocalDate,
    /** For [SuspectKind.DOUBLED]: the entry that looks counted twice. */
    val entryId: Long? = null,
    /** For [SuspectKind.DOUBLED]: another entry with the same amount on the same day, if there is one. */
    val twinId: Long? = null,
) {
    val month: YearMonth get() = YearMonth.from(to)

    val message: String
        get() {
            val dir = if (type == TxnType.CREDIT) "money in" else "money out"
            val span = if (from == null || from == to) "on ${Reconcile.day(to)}" else "between ${Reconcile.day(from)} and ${Reconcile.day(to)}"
            return when (kind) {
                SuspectKind.MISSING -> "${formatInr(amountMinor)} $dir is missing $span"
                SuspectKind.DOUBLED -> "${formatInr(amountMinor)} $dir $span looks counted twice" + if (twinId != null) " (same amount, same day)" else ""
            }
        }
}

object Reconcile {
    private val dm = java.time.format.DateTimeFormatter.ofPattern("d MMM")
    internal fun day(d: LocalDate): String = d.format(dm)

    private fun signed(t: TxnLite) = if (t.type == TxnType.CREDIT) t.amountMinor else -t.amountMinor

    /**
     * [rows] in date order, with same-day entries put in the order their balances chain (statements listed
     * newest first, or SMS read out of order, would otherwise look broken). [start] is the balance before
     * the first row, when known.
     */
    fun order(rows: List<TxnLite>, balanceAfter: Map<Long, Long>, zone: ZoneId = ZoneId.systemDefault(), start: Long? = null): List<TxnLite> {
        val byDay = rows.sortedWith(compareBy<TxnLite>({ it.timestamp }, { it.id })).groupBy { Dates.localDate(it.timestamp, zone) }
        val out = ArrayList<TxnLite>(rows.size)
        var bal: Long? = start
        for ((_, day) in byDay.toSortedMap()) {
            if (day.size == 1 || day.none { balanceAfter.containsKey(it.id) }) {
                out += day
            } else {
                val left = day.toMutableList()
                var carried = bal
                if (carried == null) {
                    // The day's first entry is the one whose balance no other entry of the day leads to.
                    val first = left.firstOrNull { a ->
                        val ab = balanceAfter[a.id] ?: return@firstOrNull false
                        left.none { b -> b !== a && balanceAfter[b.id]?.let { it + signed(a) == ab } == true }
                    }
                    if (first != null) { out += first; left.remove(first); carried = balanceAfter[first.id] }
                }
                while (left.isNotEmpty()) {
                    val c = carried
                    val next = (if (c != null) left.firstOrNull { balanceAfter[it.id] == c + signed(it) } else null)
                        // Entries without a balance of their own keep their place relative to the rest.
                        ?: left.firstOrNull { !balanceAfter.containsKey(it.id) }
                        ?: left.first()
                    out += next
                    left.remove(next)
                    carried = balanceAfter[next.id] ?: c?.plus(signed(next))
                }
            }
            bal = out.lastOrNull()?.let { balanceAfter[it.id] } ?: bal?.let { b -> b + day.sumOf { signed(it) } }
        }
        return out
    }

    /** Where [rows] of one bank account stop matching the bank's balances. */
    fun suspects(account: String, rows: List<TxnLite>, balanceAfter: Map<Long, Long>, zone: ZoneId = ZoneId.systemDefault()): List<Suspect> {
        val ordered = order(rows, balanceAfter, zone)
        val out = ArrayList<Suspect>()
        var carried: Long? = null
        var lastGood: TxnLite? = null
        // Entries since the last one whose balance agreed: where the gap must be.
        val stretch = ArrayList<TxnLite>()
        for (t in ordered) {
            stretch += t
            val b = balanceAfter[t.id]
            val c = carried
            if (b == null) {
                carried = c?.plus(signed(t))
                continue
            }
            if (c != null && c + signed(t) != b) {
                val gap = b - (c + signed(t)) // positive: the bank has more than the entries say
                val from = lastGood?.let { Dates.localDate(it.timestamp, zone) }
                val to = Dates.localDate(t.timestamp, zone)
                // An entry in the stretch that, taken out, closes the gap exactly.
                val doubled = stretch.lastOrNull { -signed(it) == gap }
                out += if (doubled != null) {
                    val d = Dates.localDate(doubled.timestamp, zone)
                    val twin = rows.firstOrNull {
                        it.id != doubled.id && it.amountMinor == doubled.amountMinor && it.type == doubled.type && Dates.localDate(it.timestamp, zone) == d
                    }
                    Suspect(account, SuspectKind.DOUBLED, doubled.amountMinor, doubled.type, lastGood?.id, t.id, d, d, doubled.id, twin?.id)
                } else {
                    Suspect(account, SuspectKind.MISSING, kotlin.math.abs(gap), if (gap > 0) TxnType.CREDIT else TxnType.DEBIT, lastGood?.id, t.id, from, to)
                }
            }
            carried = b
            lastGood = t
            stretch.clear()
        }
        return out
    }
}

// ---- Import health check ----

enum class HealthKind { MISSING_STATEMENT, UNMATCHED, OK }

/** One month of one account in the import health check. */
data class MonthHealth(
    val account: String,
    val month: YearMonth,
    val kind: HealthKind,
    /** For [HealthKind.UNMATCHED]: closing balance minus (opening + entries). */
    val differenceMinor: Long = 0,
    val suspects: List<Suspect> = emptyList(),
)

data class AccountHealth(
    val account: String,
    val isCard: Boolean,
    val first: YearMonth,
    val last: YearMonth,
    val missing: List<YearMonth>,
    val unmatched: List<MonthHealth>,
    /** Months whose balances add up. */
    val matchedMonths: Int,
) {
    val problems: Int get() = missing.size + unmatched.size

    /** Missing and unmatched months together, newest first. */
    val months: List<MonthHealth>
        get() = (missing.map { MonthHealth(account, it, HealthKind.MISSING_STATEMENT) } + unmatched).sortedByDescending { it.month }
}

object ImportHealth {
    /**
     * @param statementRows entries that came from statements (for missing months).
     * @param bankRows every entry of the bank accounts, counted or not (own transfers move the balance too).
     * @param balanceAfter the balance the bank gave after an entry, by id.
     * @param cardTails the user's cards; their entries have no running balance to check.
     */
    fun build(
        statementRows: List<TxnLite>,
        bankRows: List<TxnLite>,
        balanceAfter: Map<Long, Long>,
        cardTails: Set<String>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AccountHealth> {
        fun isCard(account: String) = MonthReport.tailOf(account)?.let { it in cardTails } ?: false
        val coverage = MissingStatements.find(statementRows, today, zone).associateBy { it.account }
        val unmatchedBy = HashMap<String, MutableList<MonthHealth>>()
        val matchedBy = HashMap<String, Int>()
        val months = HashMap<String, MutableSet<YearMonth>>()
        bankRows.filter { !it.account.isNullOrBlank() && !isCard(it.account!!) }.groupBy { it.account!! }.forEach { (acct, rows) ->
            val suspects = Reconcile.suspects(acct, rows, balanceAfter, zone).groupBy { it.month }
            val ordered = Reconcile.order(rows, balanceAfter, zone)
            ordered.groupBy { YearMonth.from(Dates.localDate(it.timestamp, zone)) }.forEach { (m, l) ->
                months.getOrPut(acct) { HashSet() } += m
                val diff = difference(l, balanceAfter) ?: return@forEach
                if (diff == 0L && suspects[m].isNullOrEmpty()) matchedBy.merge(acct, 1, Int::plus)
                else unmatchedBy.getOrPut(acct) { ArrayList() } += MonthHealth(acct, m, HealthKind.UNMATCHED, diff, suspects[m].orEmpty())
            }
        }
        val accounts = (coverage.keys + unmatchedBy.keys + matchedBy.keys)
        return accounts.map { acct ->
            val c = coverage[acct]
            val seen = months[acct].orEmpty()
            AccountHealth(
                account = acct,
                isCard = isCard(acct),
                first = c?.first ?: seen.min(),
                last = c?.last ?: seen.max(),
                missing = c?.missing.orEmpty(),
                unmatched = unmatchedBy[acct].orEmpty().sortedByDescending { it.month },
                matchedMonths = matchedBy[acct] ?: 0,
            )
        }.sortedWith(compareByDescending<AccountHealth> { it.problems }.thenBy { it.account })
    }

    /** Closing minus opening minus the entries in between, for rows already in order; null with under one balance. */
    fun difference(ordered: List<TxnLite>, balanceAfter: Map<Long, Long>): Long? {
        val f = ordered.indexOfFirst { balanceAfter.containsKey(it.id) }
        val e = ordered.indexOfLast { balanceAfter.containsKey(it.id) }
        if (f < 0) return null
        fun signed(t: TxnLite) = if (t.type == TxnType.CREDIT) t.amountMinor else -t.amountMinor
        val opening = balanceAfter.getValue(ordered[f].id) - signed(ordered[f])
        val closing = balanceAfter.getValue(ordered[e].id)
        return closing - opening - ordered.subList(f, e + 1).sumOf { signed(it) }
    }
}
