package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything the printable monthly report (PDF) shows for one budget month, worked out here so the
 * Android side only draws it.
 *
 * The rules are the app's usual ones: investments are "Invested", not spending; rows left out of totals
 * (own-account transfers, card bill payments) are listed on their own and change no total; hidden rows
 * still count everywhere and are listed too, marked hidden, because a printed record should be complete.
 */
data class MonthReport(
    val period: YearMonth,
    /** "1 Nov – 30 Nov 2026" or the salary-month range. */
    val rangeLabel: String,
    /** Extra words about what the month covers (salary month), or null. */
    val cycleNote: String?,
    val incomeMinor: Long,
    val spentMinor: Long,
    val investedMinor: Long,
    /** Spent with credit cards (to be paid with the card bill). Part of [spentMinor]. */
    val cardSpentMinor: Long,
    val rows: List<Row>,
    /** Left out of totals: own-account transfers, card bill payments and the like. */
    val notCounted: List<Row>,
    val categories: List<Share>,
    /** Credit card, bank and UPI, cash. */
    val paidWith: List<Share>,
    val modes: List<Share>,
    val accounts: List<Share>,
    val people: List<Share>,
    val labels: List<Share>,
    val merchants: List<MerchantStat>,
    val incomeSources: List<Share>,
    val investments: List<Share>,
    /** Spending on every day of the month, oldest first (days with nothing are 0). */
    val daily: List<Pair<LocalDate, Long>>,
    /** Received and spent in this month and the five before it, oldest first. */
    val trend: List<SavingsPoint>,
    val budgets: List<BudgetLine>,
    val repeating: List<RepeatLine>,
    val balances: List<BalanceLine>,
    /** Money received by category (Salary, Other income ...), largest first. */
    val incomeCategories: List<Share> = emptyList(),
    /** Every bank account and card used this month, with what went in and out. */
    val accountLines: List<AccountLine> = emptyList(),
    /** What each family member received, spent and invested. */
    val personLines: List<PersonLine> = emptyList(),
    /** Loan EMIs paid this month, by lender. */
    val emis: List<Share> = emptyList(),
    val biggest: List<Row>,
    val insights: List<String>,
) {
    /** Income minus spending minus investing. */
    val leftMinor: Long get() = incomeMinor - spentMinor - investedMinor

    /** Share of income not spent (investments count as saved), or null with no income. */
    val savingsRatePercent: Int?
        get() = if (incomeMinor <= 0) null else (((incomeMinor - spentMinor) * 100) / incomeMinor).toInt()

    val hiddenCount: Int get() = rows.count { it.hidden }

    data class Row(
        val txn: TxnLite,
        val date: LocalDate,
        val labels: List<String> = emptyList(),
        val note: String? = null,
        /** The user's own name for this payment, if they gave one. */
        val nickname: String? = null,
        val hidden: Boolean = false,
        val isCard: Boolean = false,
        /** Money in minus money out so far this month, up to this row. 0 for rows not counted. */
        val runningMinor: Long = 0,
    ) {
        val isCredit: Boolean get() = txn.type == TxnType.CREDIT
        private val merchantName: String get() = txn.merchant?.takeIf { it.isNotBlank() } ?: if (isCredit) "Money received" else "Payment"

        /** The user's name for it first, with the payee in brackets when that differs. */
        val payee: String
            get() = nickname?.takeIf { it.isNotBlank() }?.let { n ->
                if (txn.merchant.isNullOrBlank() || n.equals(txn.merchant, ignoreCase = true)) n else "$n (${txn.merchant})"
            } ?: merchantName
    }

    data class Share(val key: String, val totalMinor: Long, val count: Int, val percent: Int)

    data class BudgetLine(val category: String, val budgetMinor: Long, val spentMinor: Long) {
        val leftMinor: Long get() = budgetMinor - spentMinor
        val percent: Int get() = if (budgetMinor <= 0) 0 else ((spentMinor * 100) / budgetMinor).toInt()
    }

    /** A repeating payment (EMI, subscription, rent ...) paid this month, and when it is next due. */
    data class RepeatLine(val name: String, val category: String, val paidMinor: Long, val nextDate: LocalDate?, val cadence: String)

    /**
     * A bank account's balance from its messages / statement: before the month's first entry with a balance and
     * after its last. [entriesNetMinor] is what the recorded entries between them add up to; when the two
     * disagree ([differenceMinor] is not 0) an entry is missing or counted twice.
     */
    data class BalanceLine(
        val account: String,
        val openingMinor: Long,
        val closingMinor: Long,
        val entriesNetMinor: Long = closingMinor - openingMinor,
    ) {
        val differenceMinor: Long get() = closingMinor - openingMinor - entriesNetMinor
    }

    /** One account or card for the month. "Moved" is money that is not counted (own transfers, card bill payments). */
    data class AccountLine(
        val account: String,
        val isCard: Boolean,
        val inMinor: Long,
        val outMinor: Long,
        val movedInMinor: Long,
        val movedOutMinor: Long,
        val count: Int,
    ) {
        val netMinor: Long get() = inMinor + movedInMinor - outMinor - movedOutMinor
    }

    data class PersonLine(val person: String, val receivedMinor: Long, val spentMinor: Long, val investedMinor: Long, val count: Int)

    companion object {
        private val dayMonth = DateTimeFormatter.ofPattern("d MMM")
        private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy")
        private val tailRegex = Regex("""••\s*(\d{3,})""")

        /** The last 4 digits in an account label like "ICICI ••5010". */
        fun tailOf(account: String?): String? = account?.let { tailRegex.find(it)?.groupValues?.get(1)?.takeLast(4) }

        /**
         * @param txns every transaction that counts in totals (all months, for the trend and last month).
         * @param notCounted rows of any month that are left out of totals; only this month's are used.
         * @param cardTails last 4 digits of the user's credit cards (My cards & accounts).
         * @param budgets monthly budget per category.
         * @param recurring repeating payments the app found.
         * @param balanceAfter the account balance an SMS or statement line gave after a transaction, by id.
         */
        fun build(
            txns: List<TxnLite>,
            period: YearMonth,
            cycle: MonthCycle = MonthCycle.CALENDAR,
            zone: ZoneId = ZoneId.systemDefault(),
            notCounted: List<TxnLite> = emptyList(),
            labelsById: Map<Long, List<String>> = emptyMap(),
            hiddenIds: Set<Long> = emptySet(),
            notesById: Map<Long, String> = emptyMap(),
            nicknamesById: Map<Long, String> = emptyMap(),
            cardTails: Set<String> = emptySet(),
            budgets: Map<String, Long> = emptyMap(),
            recurring: List<RecurringPayment> = emptyList(),
            balanceAfter: Map<Long, Long> = emptyMap(),
        ): MonthReport {
            fun isCard(t: TxnLite): Boolean {
                val tail = tailOf(t.account)
                return if (tail != null && cardTails.isNotEmpty()) tail in cardTails else t.mode == PaymentMode.CARD
            }
            fun row(t: TxnLite, running: Long = 0) = Row(
                txn = t,
                date = Dates.localDate(t.timestamp, zone),
                labels = labelsById[t.id].orEmpty(),
                note = notesById[t.id]?.takeIf { it.isNotBlank() },
                nickname = nicknamesById[t.id]?.takeIf { it.isNotBlank() },
                hidden = t.id != 0L && t.id in hiddenIds,
                isCard = isCard(t),
                runningMinor = running,
            )

            val byPeriod = txns.groupBy { cycle.periodOf(it, zone) }
            val inMonth = byPeriod[period].orEmpty().sortedWith(compareBy<TxnLite>({ it.timestamp }, { it.id }))
            var running = 0L
            val rows = inMonth.map { t ->
                running += if (t.type == TxnType.CREDIT) t.amountMinor else -t.amountMinor
                row(t, running)
            }
            val spends = rows.filter { it.txn.isSpend }
            val spent = spends.sumOf { it.txn.amountMinor }
            val income = rows.filter { it.isCredit }.sumOf { it.txn.amountMinor }
            val invested = rows.filter { it.txn.isInvestment }.sumOf { it.txn.amountMinor }
            val cardSpent = spends.filter { it.isCard }.sumOf { it.txn.amountMinor }

            fun shares(list: List<Row>, total: Long = list.sumOf { it.txn.amountMinor }, key: (Row) -> String?): List<Share> =
                list.groupBy { key(it)?.takeIf { k -> k.isNotBlank() } ?: "Not set" }
                    .map { (k, v) ->
                        val sum = v.sumOf { it.txn.amountMinor }
                        Share(k, sum, v.size, if (total > 0) ((sum * 100 + total / 2) / total).toInt() else 0)
                    }
                    .sortedByDescending { it.totalMinor }

            val categories = shares(spends) { it.txn.category }
            val paidWith = shares(spends) {
                when {
                    it.isCard -> "Credit card"
                    it.txn.mode == PaymentMode.CASH -> "Cash"
                    else -> "Bank account / UPI"
                }
            }
            val modes = shares(spends) { it.txn.mode.label }
            val accounts = shares(spends) { it.txn.account ?: if (it.txn.mode == PaymentMode.CASH) "Cash" else null }
            val people = shares(spends) { it.txn.owner }
            // A payment with two labels shows under both, so these can add up to more than the total.
            val labels = spends.flatMap { r -> r.labels.ifEmpty { listOf("No label") }.map { it to r } }
                .groupBy({ it.first }, { it.second })
                .map { (k, v) ->
                    val sum = v.sumOf { it.txn.amountMinor }
                    Share(k, sum, v.size, if (spent > 0) ((sum * 100 + spent / 2) / spent).toInt() else 0)
                }
                .sortedByDescending { it.totalMinor }
                .takeIf { l -> l.any { it.key != "No label" } }.orEmpty()
            val incomeSources = shares(rows.filter { it.isCredit }) { it.txn.merchant ?: it.txn.category }
            val investments = shares(rows.filter { it.txn.isInvestment }) { it.txn.merchant ?: it.txn.category }
            val merchants = Reports.topMerchants(inMonth, 10).first

            val start = cycle.start(period)
            val end = cycle.end(period)
            val perDay = spends.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.txn.amountMinor } }
            val daily = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
                .map { it to (perDay[it] ?: 0L) }.toList()

            val trend = (5 downTo 0).map { period.minusMonths(it.toLong()) }.map { m ->
                val l = byPeriod[m].orEmpty()
                SavingsPoint(m, l.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor }, l.filter { it.isSpend }.sumOf { it.amountMinor })
            }

            val spentByCategory = categories.associate { it.key to it.totalMinor }
            val budgetLines = budgets.filterValues { it > 0 }
                .map { (c, b) -> BudgetLine(c, b, spentByCategory[c] ?: 0) }
                .sortedByDescending { it.percent }

            val repeatByKey = recurring.groupBy { Categorizer.merchantKey(it.merchant) }
            val repeating = spends.filter { r -> r.txn.merchant?.let { Categorizer.merchantKey(it) in repeatByKey } == true }
                .groupBy { Categorizer.merchantKey(it.txn.merchant!!) }
                .map { (k, v) ->
                    val p = repeatByKey.getValue(k).maxBy { it.lastDate }
                    RepeatLine(
                        name = v.first().txn.merchant!!,
                        category = v.first().txn.category,
                        paidMinor = v.sumOf { it.txn.amountMinor },
                        nextDate = p.nextDate.takeIf { it.isAfter(v.maxOf { r -> r.date }) },
                        cadence = p.cadence.label,
                    )
                }
                .sortedByDescending { it.paidMinor }

            val notCountedRows = notCounted.filter { cycle.periodOf(it, zone) == period }
                .sortedWith(compareBy<TxnLite>({ it.timestamp }, { it.id }))
                .map { row(it) }

            // Bank balances, reconciled: the balance before the month's first entry that gives one, after the last,
            // and what every entry in between (counted or not) adds up to.
            val balances = (rows + notCountedRows)
                .filter { !it.isCard && it.txn.account != null }
                .groupBy { it.txn.account!! }
                .mapNotNull { (acct, l) ->
                    val sorted = l.sortedWith(compareBy<Row>({ it.txn.timestamp }, { it.txn.id }))
                    val f = sorted.indexOfFirst { balanceAfter.containsKey(it.txn.id) }
                    val e = sorted.indexOfLast { balanceAfter.containsKey(it.txn.id) }
                    if (f < 0) return@mapNotNull null
                    fun signed(r: Row) = if (r.isCredit) r.txn.amountMinor else -r.txn.amountMinor
                    val opening = balanceAfter.getValue(sorted[f].txn.id) - signed(sorted[f])
                    BalanceLine(acct, opening, balanceAfter.getValue(sorted[e].txn.id), sorted.subList(f, e + 1).sumOf { signed(it) })
                }

            val incomeCategories = shares(rows.filter { it.isCredit }) { it.txn.category }
            val accountLines = (rows.map { it to true } + notCountedRows.map { it to false })
                .groupBy { (r, _) -> r.txn.account ?: if (r.txn.mode == PaymentMode.CASH) "Cash" else "Not set" }
                .map { (acct, l) ->
                    fun sum(counted: Boolean, credit: Boolean) =
                        l.filter { (r, c) -> c == counted && r.isCredit == credit }.sumOf { it.first.txn.amountMinor }
                    AccountLine(acct, l.any { it.first.isCard }, sum(true, true), sum(true, false), sum(false, true), sum(false, false), l.size)
                }
                .sortedWith(compareBy<AccountLine>({ it.isCard }, { -(it.outMinor + it.inMinor) }))
            val personLines = rows.groupBy { it.txn.owner?.takeIf { o -> o.isNotBlank() } ?: "Not set" }
                .map { (p, l) ->
                    PersonLine(
                        p,
                        l.filter { it.isCredit }.sumOf { it.txn.amountMinor },
                        l.filter { it.txn.isSpend }.sumOf { it.txn.amountMinor },
                        l.filter { it.txn.isInvestment }.sumOf { it.txn.amountMinor },
                        l.size,
                    )
                }
                .sortedByDescending { it.spentMinor + it.receivedMinor }
            val emis = shares(spends.filter { it.txn.category == DefaultCategories.EMI }) { it.txn.merchant ?: it.txn.category }

            val biggest = spends.sortedByDescending { it.txn.amountMinor }.take(5)

            val insights = insights(
                period, rows, spends, spent, income, invested, cardSpent, categories, budgetLines,
                byPeriod[period.minusMonths(1)].orEmpty(), repeatByKey.keys, daily,
            )

            val rangeLabel = "${start.format(dayMonth)} – ${end.format(dayMonthYear)}"
            return MonthReport(
                period = period,
                rangeLabel = rangeLabel,
                cycleNote = cycle.note(period),
                incomeMinor = income,
                spentMinor = spent,
                investedMinor = invested,
                cardSpentMinor = cardSpent,
                rows = rows,
                notCounted = notCountedRows,
                categories = categories,
                paidWith = paidWith,
                modes = modes,
                accounts = accounts,
                people = people,
                labels = labels,
                merchants = merchants,
                incomeSources = incomeSources,
                investments = investments,
                daily = daily,
                trend = trend,
                budgets = budgetLines,
                repeating = repeating,
                balances = balances,
                biggest = biggest,
                insights = insights,
                incomeCategories = incomeCategories,
                accountLines = accountLines,
                personLines = personLines,
                emis = emis,
            )
        }

        private fun pctChange(now: Long, before: Long): Int? =
            if (before <= 0) null else (((now - before) * 100) / before).toInt()

        private fun insights(
            period: YearMonth,
            rows: List<Row>,
            spends: List<Row>,
            spent: Long,
            income: Long,
            invested: Long,
            cardSpent: Long,
            categories: List<Share>,
            budgets: List<BudgetLine>,
            lastMonth: List<TxnLite>,
            repeatingKeys: Set<String>,
            daily: List<Pair<LocalDate, Long>>,
        ): List<String> {
            val out = ArrayList<String>()
            if (rows.isEmpty()) return listOf("Nothing was recorded this month.")
            val lastSpent = lastMonth.filter { it.isSpend }.sumOf { it.amountMinor }
            pctChange(spent, lastSpent)?.let { p ->
                val word = if (p >= 0) "more" else "less"
                out += "Spent ${formatInr(spent)}, ${kotlin.math.abs(p)}% $word than last month (${formatInr(lastSpent)})."
            }
            if (income > 0) {
                val rate = ((income - spent) * 100 / income).toInt()
                out += if (spent > income) "Spending was ${formatInr(spent - income)} more than the money received."
                else "Kept $rate% of the money received after spending" +
                    (if (invested > 0) ", ${formatInr(invested)} of it invested." else ".")
            }
            categories.firstOrNull()?.let { out += "Biggest category: ${it.key}, ${formatInr(it.totalMinor)} (${it.percent}% of spending)." }
            // The category that grew the most against last month.
            val lastByCat = lastMonth.filter { it.isSpend }.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountMinor } }
            categories.map { it to (it.totalMinor - (lastByCat[it.key] ?: 0)) }
                .filter { it.second >= 100_000 } // ₹1,000 or more
                .maxByOrNull { it.second }
                ?.let { (c, up) -> out += "${c.key} went up by ${formatInr(up)} from last month." }
            if (spent > 0) {
                val fixed = spends.filter { r ->
                    r.txn.category in FixedSpend.fixedCategories ||
                        r.txn.merchant?.let { Categorizer.merchantKey(it) in repeatingKeys } == true
                }.sumOf { it.txn.amountMinor }
                if (fixed > 0) out += "${(fixed * 100 / spent)}% went on fixed costs (rent, EMIs, bills, fees, repeating payments); the rest can be cut."
                if (cardSpent > 0) out += "${formatInr(cardSpent)} (${(cardSpent * 100 / spent)}%) was spent on credit cards and is paid with the card bill."
                val days = daily.size.coerceAtLeast(1)
                out += "Average spend per day: ${formatInr(spent / days)}."
                daily.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.let { (d, amt) ->
                    out += "Busiest day: ${d.format(DateTimeFormatter.ofPattern("EEE d MMM"))}, ${formatInr(amt)}."
                }
            }
            budgets.filter { it.spentMinor > it.budgetMinor }.let { over ->
                if (over.isNotEmpty()) out += "Over budget: " + over.joinToString(", ") { "${it.category} by ${formatInr(-it.leftMinor)}" } + "."
            }
            val unsorted = spends.count { it.txn.category == DefaultCategories.OTHER }
            if (unsorted > 0) out += "$unsorted payment${if (unsorted == 1) " is" else "s are"} still ${DefaultCategories.OTHER}; give them a category to make this report sharper."
            return out
        }
    }
}
