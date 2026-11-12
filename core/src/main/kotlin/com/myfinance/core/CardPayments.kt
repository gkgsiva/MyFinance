package com.myfinance.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/** One stored entry, as the card bill payments list sees it. */
data class PayLine(
    val id: Long,
    val type: TxnType,
    val amountMinor: Long,
    val timestamp: Long,
    /** Account or card number as stored ("XX144", "4144", "5010"). */
    val tail: String?,
    val merchant: String?,
    /** SMS or statement text. */
    val text: String?,
    val bank: String? = null,
    /** Account balance after this entry, when the SMS or statement gave one. */
    val balance: Long? = null,
)

enum class CardPayMatch(val label: String) {
    /** The bank's debit and the card's "payment received" were both found. */
    MATCHED("Matched"),
    /** Paid from the bank, but the card's side is missing (card statement or SMS not here yet). */
    BANK_ONLY("Card side not found"),
    /** The card says a payment arrived, but no debit from a bank account was found. */
    CARD_ONLY("Bank side not found"),
}

/** One card bill payment: the debit from the bank account and the credit on the card, when found. */
data class CardPayment(
    /** Last 4 digits of the card; null when the bank debit doesn't say which card. */
    val cardTail: String?,
    val bankDebit: PayLine?,
    val cardCredit: PayLine?,
    val date: LocalDate,
) {
    val amountMinor: Long get() = bankDebit?.amountMinor ?: cardCredit?.amountMinor ?: 0
    val match: CardPayMatch
        get() = when {
            bankDebit != null && cardCredit != null -> CardPayMatch.MATCHED
            bankDebit != null -> CardPayMatch.BANK_ONLY
            else -> CardPayMatch.CARD_ONLY
        }
}

enum class CardMonthStatus(val label: String) {
    PAID("Paid in full"),
    PART_PAID("Part paid"),
    NOT_PAID("Not paid"),
    DUE("Not due yet"),
    /** Payments, but no bill for the month to compare with. */
    NO_BILL("No bill found"),
}

/** One card in one month: its bill (when known) and the payments made to it. */
data class CardMonth(
    val month: YearMonth,
    val cardTail: String?,
    val billMinor: Long?,
    val billDue: LocalDate?,
    val payments: List<CardPayment>,
    val status: CardMonthStatus,
) {
    val paidMinor: Long get() = payments.sumOf { it.amountMinor }
    /** Bill minus paid; 0 when paid in full or no bill. */
    val shortMinor: Long get() = billMinor?.let { (it - paidMinor).coerceAtLeast(0) } ?: 0
    val unmatched: Int get() = payments.count { it.match != CardPayMatch.MATCHED }
    val needsAttention: Boolean
        get() = unmatched > 0 || status == CardMonthStatus.PART_PAID || status == CardMonthStatus.NOT_PAID
}

/**
 * Card bill payments made from the bank account, matched month by month: each debit from a bank account that paid
 * a card is paired with the "payment received" on that card, and each card's monthly bill is compared with what was
 * paid. These payments are not spending or income (the card's purchases are the spending); this is a separate list.
 */
object CardPayments {
    private const val DAY = 24 * 60 * 60 * 1000L

    private fun tail4(s: String?): String? = s?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.isNotEmpty() }

    /** The card a bank debit names ("CC payment XX5010", "ICICI card 5010"), when exactly one of [cards] is named. */
    fun cardNamed(line: PayLine, cards: Set<String>): String? {
        val texts = listOfNotNull(line.merchant, line.text).joinToString(" ")
        val named = cards.filter { c -> Regex("""(?<!\d)\d{0,12}$c(?!\d)""").containsMatchIn(texts) }
        return named.singleOrNull()
    }

    /**
     * Builds the monthly list. [cardTails] are the family's cards (last 4 digits); [bills] are card bills from the
     * statement SMS or imported card statements. Months run newest first; within a month, cards by number.
     */
    fun monthly(
        lines: List<PayLine>,
        cardTails: Set<String>,
        bills: List<BillDue>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        days: Int = 4,
    ): List<CardMonth> {
        if (cardTails.isEmpty()) return emptyList()
        val byId = lines.associateBy { it.id }
        // Both sides already paired by the import (same amount, within a few days, reads like a payment).
        val pairs = SelfTransfers.cardPaymentPairs(
            lines.map { SelfTransfers.PayRow(it.id, it.type, it.amountMinor, it.timestamp, it.tail, listOfNotNull(it.merchant, it.text).joinToString(" "), false) },
            cardTails,
            days,
        )
        val paired = pairs.flatMap { listOf(it.first, it.second) }.toHashSet()
        val bankSide = lines.filter {
            it.type == TxnType.DEBIT && it.amountMinor > 0 && tail4(it.tail) !in cardTails && it.id !in paired &&
                SelfTransfers.isCardPayment(it.merchant, it.text)
        }
        val cardSide = lines.filter {
            it.type == TxnType.CREDIT && it.amountMinor > 0 && tail4(it.tail) in cardTails && it.id !in paired &&
                SelfTransfers.isCardBillPayment(TxnType.CREDIT, it.merchant, it.text, cardStatement = true)
        }
        val payments = ArrayList<CardPayment>()
        for ((d, c) in pairs) {
            val debit = byId[d] ?: continue
            val credit = byId[c] ?: continue
            payments += CardPayment(tail4(credit.tail), debit, credit, Dates.localDate(debit.timestamp, zone))
        }
        // The rest: same amount within a few days, the card named in the bank text when it is, closest first.
        val candidates = ArrayList<Triple<PayLine, PayLine, Long>>()
        for (d in bankSide) for (c in cardSide) {
            if (c.amountMinor != d.amountMinor) continue
            val gap = abs(c.timestamp - d.timestamp)
            if (gap > days * DAY) continue
            val named = cardNamed(d, cardTails)
            if (named != null && named != tail4(c.tail)) continue
            candidates += Triple(d, c, gap)
        }
        val used = HashSet<Long>()
        for ((d, c, _) in candidates.sortedBy { it.third }) {
            if (d.id in used || c.id in used) continue
            used += d.id; used += c.id
            payments += CardPayment(tail4(c.tail), d, c, Dates.localDate(d.timestamp, zone))
        }
        for (d in bankSide) if (d.id !in used) payments += CardPayment(cardNamed(d, cardTails), d, null, Dates.localDate(d.timestamp, zone))
        for (c in cardSide) if (c.id !in used) payments += CardPayment(tail4(c.tail), null, c, Dates.localDate(c.timestamp, zone))

        // Card bills: one per card and due date (the SMS and the imported statement describe the same bill).
        val cardBills = bills.filter { it.kind == BillKind.CREDIT_CARD && tail4(it.accountTail) in cardTails }
            .groupBy { "${tail4(it.accountTail)}|${it.dueDate}" }.values.map { g -> g.maxByOrNull { it.amountMinor }!! }

        // A bill belongs to the month most of its payment went out in (payments from about 25 days before the
        // due date to 5 days after); with nothing paid, the month just before the due date's first days.
        val billMonths = HashMap<Pair<YearMonth, String?>, MutableList<BillDue>>()
        for (b in cardBills) {
            val t = tail4(b.accountTail)
            val window = payments.filter { it.cardTail == t && !it.date.isBefore(b.dueDate.minusDays(25)) && !it.date.isAfter(b.dueDate.plusDays(5)) }
            val month = window.groupBy { YearMonth.from(it.date) }.maxByOrNull { (_, l) -> l.sumOf { it.amountMinor } }?.key
                ?: YearMonth.from(b.dueDate.minusDays(5))
            billMonths.getOrPut(month to t) { ArrayList() } += b
        }

        val keys = (payments.map { YearMonth.from(it.date) to it.cardTail } + billMonths.keys).toSet()
        return keys.map { key ->
            val (month, tail) = key
            val list = payments.filter { YearMonth.from(it.date) == month && it.cardTail == tail }.sortedBy { it.date }
            val monthBills = billMonths[key].orEmpty()
            val bill = monthBills.takeIf { it.isNotEmpty() }?.sumOf { it.amountMinor }
            val due = monthBills.maxOfOrNull { it.dueDate }
            val paid = list.sumOf { it.amountMinor }
            val tolerance = bill?.let { maxOf(200L, it / 200) } ?: 0
            val status = when {
                bill == null -> CardMonthStatus.NO_BILL
                bill <= 0 -> CardMonthStatus.PAID
                paid + tolerance >= bill -> CardMonthStatus.PAID
                due != null && !today.isAfter(due) && paid == 0L -> CardMonthStatus.DUE
                paid == 0L -> CardMonthStatus.NOT_PAID
                else -> CardMonthStatus.PART_PAID
            }
            CardMonth(month, tail, bill, due, list, status)
        }.sortedWith(compareByDescending<CardMonth> { it.month }.thenBy { it.cardTail ?: "~" })
    }
}

/** One card statement cycle (e.g. 15 Oct to 14 Nov): what the card's own entries add up to against the statement. */
data class CardCycle(
    val cardTail: String,
    /** First and last day of the cycle; the statement is made on [to]. */
    val from: LocalDate,
    val to: LocalDate,
    val spendsMinor: Long,
    val refundsMinor: Long,
    val paymentsMinor: Long,
    val entries: Int,
    /** Total due on the statement for this cycle; null when no bill was found for it. */
    val statementMinor: Long?,
    /** The previous statement's total due, when known. */
    val previousMinor: Long?,
) {
    /** Previous bill − payments + spends − refunds; without a previous bill, spends − refunds. */
    val computedMinor: Long get() = (previousMinor?.let { it - paymentsMinor } ?: 0) + spendsMinor - refundsMinor
    val differenceMinor: Long? get() = statementMinor?.let { computedMinor - it }
    /** Within ₹10 counts as matching (interest and GST lines are rounded). */
    val matches: Boolean? get() = differenceMinor?.let { abs(it) <= 1_000 }
}

/** A day where the bank balance doesn't follow from the entries stored: something is missing or misread. */
data class BankGap(val date: LocalDate, val previousDate: LocalDate?, val expectedMinor: Long, val balanceMinor: Long) {
    /** Positive: money came in that isn't stored; negative: money went out that isn't stored. */
    val missingMinor: Long get() = balanceMinor - expectedMinor
}

/** One bank account in one month: entries stored, and whether the running balance adds up. */
data class BankMonth(val accountTail: String, val month: YearMonth, val entries: Int, val withBalance: Int, val gaps: List<BankGap>) {
    /** False when too few entries carry a balance to tell. */
    val checkable: Boolean get() = withBalance > 0
}

object CardReconcile {
    private fun tail4(s: String?): String? = s?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.isNotEmpty() }

    private fun statementDay(ym: YearMonth, day: Int): LocalDate = ym.atDay(day.coerceIn(1, ym.lengthOfMonth()))

    /**
     * Every statement cycle of each card from its first entry to today. [billDays] gives a card's statement day
     * (14 for Siva's ICICI cards); [defaultDay] is used otherwise. A bill belongs to the cycle whose statement
     * date comes 10 to 30 days before its due date.
     */
    fun cycles(
        lines: List<PayLine>,
        cardTails: Set<String>,
        bills: List<BillDue>,
        billDays: Map<String, Int>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        defaultDay: Int = 14,
    ): List<CardCycle> {
        val out = ArrayList<CardCycle>()
        val cardBills = bills.filter { it.kind == BillKind.CREDIT_CARD }.groupBy { tail4(it.accountTail) }
        for ((tail, list) in lines.filter { tail4(it.tail) in cardTails }.groupBy { tail4(it.tail)!! }) {
            val day = billDays[tail] ?: defaultDay
            val dated = list.map { it to Dates.localDate(it.timestamp, zone) }
            val first = dated.minOf { it.second }
            var ym = YearMonth.from(first).let { if (first.dayOfMonth > day) it.plusMonths(1) else it }
            val myBills = cardBills[tail].orEmpty()
            fun billFor(statement: LocalDate) = myBills.filter {
                !it.dueDate.isBefore(statement.plusDays(10)) && !it.dueDate.isAfter(statement.plusDays(30))
            }.maxByOrNull { it.amountMinor }
            while (true) {
                val to = statementDay(ym, day)
                val from = statementDay(ym.minusMonths(1), day).plusDays(1)
                if (from.isAfter(today)) break
                val inCycle = dated.filter { (_, d) -> !d.isBefore(from) && !d.isAfter(to) }.map { it.first }
                var spends = 0L
                var refunds = 0L
                var payments = 0L
                for (l in inCycle) {
                    if (l.type == TxnType.DEBIT) spends += l.amountMinor
                    else if (SelfTransfers.isCardBillPayment(TxnType.CREDIT, l.merchant, l.text, cardStatement = true)) payments += l.amountMinor
                    else refunds += l.amountMinor
                }
                val bill = billFor(to)
                val prev = billFor(statementDay(ym.minusMonths(1), day))
                if (inCycle.isNotEmpty() || bill != null) {
                    out += CardCycle(tail, from, to, spends, refunds, payments, inCycle.size, bill?.amountMinor, prev?.amountMinor)
                }
                ym = ym.plusMonths(1)
            }
        }
        return out.sortedWith(compareByDescending<CardCycle> { it.to }.thenBy { it.cardTail })
    }

    /**
     * Checks each bank account's entries against the running balance they carry, day by day: the day's last balance
     * should equal the previous balance plus the day's money in minus money out. A day where it doesn't means an
     * entry is missing (or was read wrongly). [bankTails] are the family's bank accounts; an entry whose number ends
     * with the same digits ("XX144" and "4144") is put on that account.
     */
    fun bankMonths(lines: List<PayLine>, cardTails: Set<String>, bankTails: Set<String>, zone: ZoneId = ZoneId.systemDefault()): List<BankMonth> {
        fun account(t: String?): String? {
            val d = t?.filter(Char::isDigit)?.takeIf { it.length >= 3 } ?: return null
            if (d.takeLast(4) in cardTails) return null
            return bankTails.firstOrNull { b -> b.endsWith(d.takeLast(3)) && (d.length < 4 || b.length < 4 || b.takeLast(4) == d.takeLast(4)) } ?: d.takeLast(4)
        }
        val out = ArrayList<BankMonth>()
        for ((acct, list) in lines.groupBy { account(it.tail) }) {
            if (acct == null || list.none { it.balance != null }) continue
            val byDay = list.groupBy { Dates.localDate(it.timestamp, zone) }.toSortedMap()
            var close: Long? = null
            var closeDay: LocalDate? = null
            val gaps = ArrayList<BankGap>()
            for ((d, dayLines) in byDay) {
                val net = dayLines.sumOf { if (it.type == TxnType.CREDIT) it.amountMinor else -it.amountMinor }
                val balances = dayLines.sortedWith(compareBy({ it.timestamp }, { it.id })).mapNotNull { it.balance }
                if (balances.isEmpty()) { close = close?.plus(net); continue }
                val expected = close?.plus(net)
                close = when {
                    expected == null -> balances.last()
                    balances.any { abs(it - expected) <= 100 } -> balances.first { abs(it - expected) <= 100 }
                    else -> {
                        // The entries of a day may be stored out of order; any balance that follows works.
                        val last = balances.last()
                        gaps += BankGap(d, closeDay, expected, last)
                        last
                    }
                }
                closeDay = d
            }
            for ((ym, l) in list.groupBy { Dates.yearMonth(it.timestamp, zone) }) {
                out += BankMonth(acct, ym, l.size, l.count { it.balance != null }, gaps.filter { YearMonth.from(it.date) == ym })
            }
        }
        return out.sortedWith(compareByDescending<BankMonth> { it.month }.thenBy { it.accountTail })
    }
}
