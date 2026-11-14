package com.myfinance.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import kotlin.math.abs

/** Bank account statement (has a running balance) or credit card bill. */
enum class StatementKind(val label: String) { BANK("Bank statement"), CARD("Credit card statement") }

/** One line of a downloaded bank or card statement. */
data class StatementRow(
    val date: LocalDate,
    val time: LocalTime?,
    /** The narration / description exactly as the bank printed it. */
    val description: String,
    val amountMinor: Long,
    val type: TxnType,
    val balanceMinor: Long? = null,
    val reference: String? = null,
    /**
     * Statements covering several cards (add-on, RuPay UPI card) or accounts (savings and current): the last
     * 4 digits of this line's card or account, from the heading printed above its table.
     */
    val cardTail: String? = null,
    /** The serial / reference number printed at the start of a card statement line, when there is one. */
    val serial: String? = null,
    /** Card statements: the amount in the currency spent ("USD 23.60") for a foreign spend, when printed. */
    val foreign: String? = null,
)

data class ParsedStatement(
    val rows: List<StatementRow>,
    val kind: StatementKind,
    val bank: String?,
    /** Last 4 digits of the account or card the statement is for, when it says. */
    val accountTail: String?,
    /** Card statements: the bill printed at the top (total due, minimum due, due date), when it could be read. */
    val billTotalMinor: Long? = null,
    val billMinMinor: Long? = null,
    val billDueDate: LocalDate? = null,
    /** Whether the lines read add up to the statement's own totals; null when the statement doesn't say. */
    val check: StatementCheck? = null,
    /** Card / account numbers printed near the top, as (first digits when shown, last 4). */
    val numbersSeen: List<Pair<String?, String>> = emptyList(),
) {
    val from: LocalDate? get() = rows.minOfOrNull { it.date }
    val to: LocalDate? get() = rows.maxOfOrNull { it.date }
}

/**
 * Reads Indian bank and credit card statements: tables (CSV / Excel) by their column headings, and
 * PDF text line by line. Dates are always day-first, as Indian banks print them.
 */
object StatementParser {

    // ---------------------------------------------------------------- tables (CSV, Excel)

    private enum class Col { DATE, VALUE_DATE, DESC, DEBIT, CREDIT, AMOUNT, DRCR, BALANCE, REF, TIME }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z/]+"""), " ").trim()

    private fun classify(header: String): Col? {
        val h = norm(header)
        if (h.isEmpty()) return null
        return when {
            h.contains("value") && (h.contains("dat") || h.contains(" dt")) -> Col.VALUE_DATE
            h == "time" || h == "txn time" || h == "transaction time" -> Col.TIME
            h.contains("balance") -> Col.BALANCE
            h.contains("withdraw") || h.contains("debit") && !h.contains("credit") || h == "dr" || h == "dr amount" ||
                h.contains("paid out") || h.contains("money out") -> Col.DEBIT
            h.contains("deposit") || h.contains("credit") && !h.contains("debit") && !h.contains("card") || h == "cr" ||
                h == "cr amount" || h.contains("paid in") || h.contains("money in") -> Col.CREDIT
            h == "dr/cr" || h == "cr/dr" || h == "dr / cr" || h == "debit/credit" || h == "credit/debit" ||
                h == "type" || h == "txn type" || h == "transaction type" || h == "d/c" || h == "c/d" -> Col.DRCR
            h.contains("narration") || h.contains("description") || h.contains("particular") ||
                h.contains("detail") || h.contains("remark") || h == "merchant" || h.contains("merchant name") ||
                h == "transaction" -> Col.DESC
            h.contains("chq") || h.contains("cheque") || h.contains("ref") || h.contains("utr") -> Col.REF
            h.contains("amount") || h == "amt" || h.contains("amount inr") -> Col.AMOUNT
            h.contains("date") || h == "txn dt" || h == "tran dt" -> Col.DATE
            else -> null
        }
    }

    /** Finds the heading row: it names a date column and some amount column. */
    private fun findHeader(table: List<List<String>>): Pair<Int, Map<Col, Int>>? {
        for ((i, row) in table.withIndex().take(80)) {
            val map = HashMap<Col, Int>()
            row.forEachIndexed { c, cell ->
                if (cell.length > 40) return@forEachIndexed
                val col = classify(cell) ?: return@forEachIndexed
                // "Amount (Dr/Cr)" style: a single amount column with the sign in the cell.
                if (col !in map) map[col] = c
            }
            val hasDate = Col.DATE in map || Col.VALUE_DATE in map
            val hasAmount = (Col.DEBIT in map && Col.CREDIT in map) || Col.AMOUNT in map || Col.DEBIT in map
            if (hasDate && hasAmount && map.size >= 3) return i to map
        }
        return null
    }

    /** Parses a spreadsheet: any heading lines before the table (account number, bank) are used as hints. */
    fun fromTable(table: List<List<String>>): ParsedStatement? {
        val (headerIdx, cols) = findHeader(table) ?: return null
        val preamble = table.take(headerIdx).joinToString("\n") { it.joinToString(" ") }
        val dateCol = cols[Col.DATE] ?: cols[Col.VALUE_DATE]!!
        val rows = ArrayList<StatementRow>()
        val pendingDirection = ArrayList<Int>() // rows whose direction comes from the balance
        for (rec in table.drop(headerIdx + 1)) {
            fun cell(c: Col): String? = cols[c]?.let { rec.getOrNull(it) }?.trim()?.ifBlank { null }
            val dateText = cell(Col.DATE) ?: cell(Col.VALUE_DATE)
            val desc = cell(Col.DESC).orEmpty()
            val parsedDate = dateText?.let { parseDateTime(it) }
            if (parsedDate == null) {
                // Some banks wrap long narrations onto the next row without a date.
                if (dateText == null && desc.isNotEmpty() && rows.isNotEmpty() && amountsIn(rec, cols).isEmpty() &&
                    !isFooter(desc)
                ) {
                    val last = rows.removeAt(rows.lastIndex)
                    rows += last.copy(description = (last.description + " " + desc).trim())
                }
                continue
            }
            if (isSummaryLine(desc)) continue
            val (date, time0) = parsedDate
            val time = time0 ?: cell(Col.TIME)?.let { parseTime(it) }
            val balance = cell(Col.BALANCE)?.let { parseMoney(it)?.first }
            val ref = cell(Col.REF)?.takeIf { it.any(Char::isDigit) && it.trim('0').isNotEmpty() }
            val debit = cell(Col.DEBIT)?.let { parseMoney(it) }?.takeIf { it.first != 0L }
            val credit = cell(Col.CREDIT)?.let { parseMoney(it) }?.takeIf { it.first != 0L }
            val amountCell = cell(Col.AMOUNT)
            val amount = amountCell?.let { parseMoney(it) }?.takeIf { it.first != 0L }
            val drcr = cell(Col.DRCR)?.let { markerType(it) }

            val (value, type) = when {
                debit != null && credit == null -> abs(debit.first) to TxnType.DEBIT
                credit != null && debit == null -> abs(credit.first) to TxnType.CREDIT
                amount != null -> {
                    val t = drcr ?: amount.second ?: if (amount.first < 0) TxnType.DEBIT else null
                    if (t == null) pendingDirection += rows.size
                    abs(amount.first) to (t ?: TxnType.DEBIT)
                }
                else -> continue
            }
            rows += StatementRow(date, time, cleanDesc(desc), value, type, balance, referenceIn(desc) ?: ref)
        }
        if (rows.isEmpty()) return null
        val hasBalance = rows.count { it.balanceMinor != null } >= rows.size / 2
        val kind = if (hasBalance && !looksLikeCard(preamble)) StatementKind.BANK else StatementKind.CARD
        val fixed = if (pendingDirection.isEmpty()) rows else fixDirections(rows, pendingDirection.toSet(), kind)
        return ParsedStatement(fixed, kind, bankIn(preamble), accountTailIn(preamble), check = if (kind == StatementKind.BANK) StatementCheck.bank(fixed) else null, numbersSeen = numbersIn(preamble))
    }

    private fun amountsIn(rec: List<String>, cols: Map<Col, Int>): List<Long> =
        listOf(Col.DEBIT, Col.CREDIT, Col.AMOUNT).mapNotNull { c -> cols[c]?.let { rec.getOrNull(it) }?.let { parseMoney(it)?.first } }

    /** Rows with only an amount (no Dr/Cr): use the running balance, or descriptions on a card bill. */
    private fun fixDirections(rows: List<StatementRow>, unknown: Set<Int>, kind: StatementKind): List<StatementRow> {
        val out = rows.toMutableList()
        val dirs = if (kind == StatementKind.BANK) directionsFromBalance(rows.map { it.amountMinor to it.balanceMinor }) else null
        for (i in unknown) {
            val t = dirs?.get(i) ?: guessType(rows[i].description, kind)
            out[i] = out[i].copy(type = t)
        }
        return out
    }

    // ---------------------------------------------------------------- PDF text

    private const val DATE_RE =
        """(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}|\d{1,2}[\s\-/]?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*[\s\-/,]*\d{2,4}|\d{4}-\d{2}-\d{2})"""
    /** A date further into a line, before a long serial / reference number ("69% 18/09/2026 14196250597 ..."). */
    private val midStart = Regex("""(?<=^|\s)$DATE_RE\s+(?=\d{8,}\s)""", RegexOption.IGNORE_CASE)
    // HDFC's newer card statements print "12/03/2025| 14:32 SWIGGY ... C 450.00" (idea from Card-Statement-Analyser, MIT).
    private val lineStart = Regex("""^\s*(?:\d{1,4}\s+)?$DATE_RE(?:\s*\|?\s*(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[ap]m)?))?\s*\|?\s*""", RegexOption.IGNORE_CASE)
    private val dateAnywhere = Regex("""(?<![\d/])$DATE_RE(?![\d/])""", RegexOption.IGNORE_CASE)
    // Amounts in statements always carry paise: 1,234.56 / 450.00 / -12.00 / 1,234.56 Cr
    // ... or, after "Rs." / "₹", whole rupees (CSB / Edge card statements print "Rs. 1,234").
    private val moneyRe = Regex(
        """(?<![\w.,])(?:(?:rs\.?|inr|₹)\s*)?(\(?[+\-]?(?:\d{1,3}(?:,\d{2,3})+|\d+)\.\d{2}\)?)(?:\s*(cr|dr|c|d)\b)?(?![\w.]|,\d)|(?<![\w.,])(?:rs\.?|inr|₹)\s*((?:\d{1,3}(?:,\d{2,3})+|\d+))(?:\s*(cr|dr)\b)?(?![\w.]|,\d)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses statement text taken from a PDF, one transaction per line. Lines that start with a date
     * and contain amounts are transactions; lines without either that follow a transaction continue
     * its description.
     */
    fun fromText(text: String): ParsedStatement? {
        cardWebView(text)?.let { return it }
        val numbered = numberedBank(text) ?: return fromLines(text)
        // A file that joins years in two layouts (older pages in the column layout, newer ones numbered): the rest is
        // read the usual way and both parts are kept.
        val all = text.lines()
        val rest = fromLines(all.filterIndexed { i, _ -> i !in numbered.second }.joinToString("\n"))
        val n = numbered.first
        if (rest == null || rest.rows.isEmpty()) return n
        val joined = rest.rows + n.rows.map { it.copy(cardTail = it.cardTail ?: n.accountTail) }
        // The file's account: the one most of its lines are on.
        val tail = joined.mapNotNull { it.cardTail }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: n.accountTail ?: rest.accountTail
        return ParsedStatement(
            joined, StatementKind.BANK, rest.bank ?: n.bank, tail,
            numbersSeen = rest.numbersSeen + n.numbersSeen,
        )
    }

    private fun fromLines(text: String): ParsedStatement? {
        data class Line(
            val date: LocalDate, val time: LocalTime?, val desc: StringBuilder, val amounts: List<Pair<Long, TxnType?>>, val raw: String,
            /** The description without card-statement columns (serial number before it, reward points after it). */
            val cardDesc: StringBuilder, val card: String?,
            /** Bank statements: the account heading above this line. */
            val acct: String? = null,
            /** Which table it was read from: a new heading (card / account number) starts a new one. */
            val section: Int = 0,
            val serial: String? = null,
            val foreign: String? = null,
        )

        val lines = text.lines()
        // ICICI prints a long narration over three lines, centred on the date line: the first above it, the last
        // below it with a "0.00" in the balance column. Such statements show many "text 0.00" lines.
        val zeroMarked = lines.count { zeroTail.matches(it.trim()) && lineStart.find(it.trim()) == null } >= 3
        var prefix: String? = null
        fun nextIsTxn(from: Int): Boolean {
            for (k in from until lines.size) {
                val t = lines[k].replace('\u00A0', ' ').trim()
                if (t.isEmpty()) continue
                val st = lineStart.find(t) ?: return false
                return parseDate(st.groupValues[1]) != null && moneyRe.find(t.substring(st.range.last + 1)) != null
            }
            return false
        }
        val parsed = ArrayList<Line>()
        var openingBalance: Long? = null
        var lastWasTxn = false
        var continuation = 0
        var headerText = StringBuilder()
        var card: String? = null
        var acct: String? = null
        var section = 0
        // Each account's opening ("B/F") balance, when a statement holds several.
        val openings = HashMap<String?, Long>()
        for ((lineNo, raw) in lines.withIndex()) {
            var line = raw.replace(' ', ' ').trim()
            if (line.isEmpty()) { lastWasTxn = false; continue }
            if (parsed.isEmpty() && headerText.length < 4000) headerText.append(line).append('\n')
            val low = line.lowercase()
            if (!openings.containsKey(acct) && (low.contains("opening balance") || low.contains("balance b/f") || low.contains("brought forward") || Regex("""\sb/f\s""").containsMatchIn(" $low "))) {
                moneyRe.findAll(line).lastOrNull()?.let { m -> parseMoney(m.value)?.first?.let { openings[acct] = it; if (openingBalance == null) openingBalance = it } }
            }
            var start = lineStart.find(line)
            var date = if (start != null) parseDate(start.groupValues[1]) else null
            if (start == null || date == null) {
                // Side panels printed beside the table ("Others-69%") come before the date on the same line.
                val m = midStart.find(line)
                if (m != null) {
                    val cut = line.substring(m.range.first).trim()
                    val s2 = lineStart.find(cut)
                    val d2 = if (s2 != null) parseDate(s2.groupValues[1]) else null
                    if (s2 != null && d2 != null) { line = cut; start = s2; date = d2 }
                }
            }
            if (start == null || date == null) {
                // "Card Number : 4375 XXXX XXXX 4007" / "4375XXXXXXXX5010": the lines below are this card's.
                // "Statement of transactions in Savings Account XXXXXXXX4144" / "Account No : 012301234567": the
                // lines below are that account's (a statement can hold savings and current accounts).
                if (moneyRe.find(line) == null) {
                    val cardHit = cardNumberRe.find(line)
                    val acctHit = accountTailOfHeading(line)
                    if (cardHit != null || acctHit != null) {
                        cardHit?.let { card = it.groupValues[1] }
                        acctHit?.let { acct = it }
                        section++
                        lastWasTxn = false
                        continue
                    }
                }
                val extra = line.replace(percentRe, " ").trim()
                if (extra.isEmpty()) continue
                if (zeroMarked && parsed.isNotEmpty()) {
                    // "s Bank Ltd 0.00": the end of the line above.
                    val z = zeroTail.matchEntire(extra)
                    if (z != null && moneyRe.find(z.groupValues[1]) == null && !isFooter(extra)) {
                        parsed.last().desc.append(' ').append(z.groupValues[1])
                        parsed.last().cardDesc.append(' ').append(z.groupValues[1])
                        continue
                    }
                    // "NEFT-…-RENAULT NISSAN" right above a date line: the start of that line's narration.
                    if (moneyRe.find(line) == null && extra.count(Char::isLetter) >= 3 && !isFooter(line) &&
                        !notDescription.containsMatchIn(extra) && !headingWords.containsMatchIn(extra) && nextIsTxn(lineNo + 1)
                    ) {
                        prefix = listOfNotNull(prefix, extra).joinToString(" ")
                        lastWasTxn = false
                        continue
                    }
                }
                if (lastWasTxn && continuation < 2 && moneyRe.find(line) == null && !isFooter(line) && !notDescription.containsMatchIn(extra) && parsed.isNotEmpty()) {
                    parsed.last().desc.append(' ').append(extra)
                    parsed.last().cardDesc.append(' ').append(extra)
                    continuation++
                } else lastWasTxn = false
                continue
            }
            val rest = line.substring(start.range.last + 1)
            val amounts = moneyRe.findAll(rest).toList()
            if (amounts.isEmpty() || isSummaryLine(rest)) { lastWasTxn = false; continue }
            val time = start.groupValues[2].ifBlank { null }?.let { parseTime(it) }
            // The description is what comes before the first amount, without a second (value) date.
            // A currency column ("USD 23.60") sits between the description and the rupee amount.
            val beforeAmount = rest.substring(0, amounts.first().range.first).replace(dateAnywhere, " ").trim()
            val descText = beforeAmount.replace(currencyColumn, "").trim()
            // "USD 23.60 2,038.32": the foreign amount, so a foreign spend's SMS (in dollars) finds this line.
            val foreign = currencyColumn.find(beforeAmount)?.groupValues?.get(1)?.takeIf { amounts.size >= 2 }
                ?.let { code -> "$code ${amounts[amounts.size - 2].value.replace(",", "").trim()}" }
            val values = amounts.mapNotNull { m -> parseMoney(m.value) }
            val cardText = descText.replace(serialFirst, "").replace(pointsLast, "").trim()
            val serial = serialFirst.find(descText)?.value?.trim()?.takeIf { it.length >= 10 }
            val before = prefix?.let { "$it " }.orEmpty()
            prefix = null
            parsed += Line(date, time, StringBuilder(before + descText), values, line, StringBuilder(before + cardText), card, acct, section, serial, foreign)
            lastWasTxn = true
            continuation = 0
        }
        if (parsed.isEmpty()) return null

        // A bank statement line ends "... amount balance"; check whether the balances chain up.
        val multi = parsed.count { it.amounts.size >= 2 }
        val asBank = parsed.map { (if (it.amounts.size >= 2) it.amounts[it.amounts.size - 2].first else it.amounts.last().first) to (if (it.amounts.size >= 2) it.amounts.last().first else null) }
        // Each account's balance runs on its own: savings and current in one statement are chained separately.
        val bankDirs = if (multi >= parsed.size * 0.6) {
            val out = ArrayList<TxnType?>(parsed.size)
            var i = 0
            while (i < parsed.size) {
                var j = i
                while (j < parsed.size && parsed[j].acct == parsed[i].acct) j++
                out += directionsFromBalance(asBank.subList(i, j), openings[parsed[i].acct] ?: if (i == 0) openingBalance else null)
                i = j
            }
            out
        } else null
        val chained = bankDirs?.count { it != null } ?: 0
        val isBank = bankDirs != null && chained >= (parsed.size - 1) * 0.6 && !looksLikeCard(headerText.toString())

        val cardMarksCredits = parsed.count { it.amounts.last().second == TxnType.CREDIT } >= 1 &&
            parsed.none { it.amounts.last().second == TxnType.DEBIT }
        val rows = parsed.mapIndexed { i, l ->
            val desc = cleanDesc(l.desc.toString())
            if (isBank) {
                val (amt, bal) = asBank[i]
                val marker = l.amounts.getOrNull(l.amounts.size - 2)?.second
                StatementRow(l.date, l.time, desc, abs(amt), bankDirs!![i] ?: marker ?: guessType(desc, StatementKind.BANK), bal?.let { abs(it) }, referenceIn(l.raw), cardTail = l.acct)
            } else {
                // Card bills: the transaction amount is the last money value; "Cr" marks payments and refunds.
                val (amt, marker) = l.amounts.last()
                // HDFC: "+ C 1,000.00" is a credit; the "C" is how the ₹ sign comes out of the PDF.
                val rawCard = l.cardDesc.toString()
                val plus = hdfcCredit.containsMatchIn(rawCard)
                val cardDesc = cleanDesc(rawCard.replace(hdfcCredit, "").replace(rupeeGlyph, "").replace(convertWord, " ")).ifEmpty { desc }
                // A statement that marks its credits ("1,170.00 CR") makes every unmarked line a charge, even one
                // whose words sound like money in ("Refund Amt Transfer to Bank Acc": a card refund paid out to the bank).
                val type = marker ?: if (plus || amt < 0) TxnType.CREDIT else if (cardMarksCredits) TxnType.DEBIT else guessType(cardDesc, StatementKind.CARD)
                StatementRow(l.date, l.time, cardDesc, abs(amt), type, null, referenceIn(l.raw) ?: l.serial, cardTail = l.card, serial = l.serial, foreign = l.foreign)
            }
        }.let { all -> withoutRepeats(all, parsed.map { it.section }, isBank) }.filter { it.amountMinor > 0 }
        if (rows.isEmpty()) return null
        val head = headerText.toString()
        val kind = if (isBank) StatementKind.BANK else StatementKind.CARD
        val bill = if (kind == StatementKind.CARD) runCatching { CardBills.summaryFromText(text, rows.maxOf { it.date }) }.getOrNull()
            // A due date far from the statement's last line is a misread.
            ?.takeIf { b -> rows.maxOf { it.date }.let { last -> !b.third.isBefore(last.minusDays(3)) && !b.third.isAfter(last.plusDays(60)) } }
        else null
        val accountsInFile = rows.mapNotNullTo(LinkedHashSet()) { it.cardTail }
        val check = runCatching {
            if (kind == StatementKind.BANK && accountsInFile.size > 1) {
                // Each account against its own balances, added up.
                val parts = rows.groupBy { it.cardTail }.values.map { StatementCheck.bank(it, null) }
                if (parts.any { it == null }) null else StatementCheck(parts.sumOf { it!!.expectedMinor }, parts.sumOf { it!!.computedMinor })
            } else if (kind == StatementKind.BANK) StatementCheck.bank(rows, openingBalance)
            else bill?.let { b -> previousBalanceIn(text)?.let { prev -> StatementCheck.card(rows, prev, b.first) } }
        }.getOrNull()
        return ParsedStatement(
            rows, kind, bankIn(head) ?: bankIn(text.take(6000)), accountTailIn(head) ?: accountTailIn(text.take(6000)),
            billTotalMinor = bill?.first, billMinMinor = bill?.second, billDueDate = bill?.third, check = check,
            numbersSeen = numbersIn(text.take(8000)) + parsed.mapNotNull { it.card }.distinct().map { null to it },
        )
    }

    /**
     * For each row, the direction implied by the change in running balance from the row before (or
     * after, for statements listed newest first). Null where the balances do not tell.
     */
    fun directionsFromBalance(rows: List<Pair<Long, Long?>>, opening: Long? = null): List<TxnType?> {
        fun dir(prev: Long?, amt: Long, bal: Long?): TxnType? {
            if (prev == null || bal == null) return null
            val a = abs(amt)
            return when (bal - prev) {
                a -> TxnType.CREDIT
                -a -> TxnType.DEBIT
                else -> null
            }
        }
        val asc = rows.mapIndexed { i, (amt, bal) -> dir(if (i == 0) opening else rows[i - 1].second, amt, bal) }
        // Newest first: the previous balance is on the next line.
        val desc = rows.mapIndexed { i, (amt, bal) -> dir(rows.getOrNull(i + 1)?.second, amt, bal) }
        return if (desc.count { it != null } > asc.count { it != null }) desc else asc
    }

    private val creditWords = Regex(
        """\b(neft\s*cr|imps\s*cr|upi\s*/?\s*cr|by\s+transfer|by\s+clg|deposit|salary|sal\b|refund|reversal|cashback|interest\s+(?:paid|credit)|int\.?pd|credit\s+interest|payment\s+received|thank\s*you|received|\brev\b)""",
        RegexOption.IGNORE_CASE,
    )

    private fun guessType(desc: String, kind: StatementKind): TxnType =
        if (creditWords.containsMatchIn(desc)) TxnType.CREDIT else TxnType.DEBIT

    // ---------------------------------------------------------------- values

    private fun markerType(s: String): TxnType? = when (s.trim().lowercase().trimEnd('.')) {
        "cr", "c", "credit", "+", "deposit" -> TxnType.CREDIT
        "dr", "d", "debit", "-", "withdrawal" -> TxnType.DEBIT
        else -> null
    }

    /**
     * Reads "1,234.56", "1,234.56 Cr", "-450.00", "(450.00)", "₹ 450" or "INR 1,200" into paise, with
     * the Dr / Cr marker when there is one. Returns null for text that is not an amount.
     */
    fun parseMoney(text: String): Pair<Long, TxnType?>? {
        var s = text.trim().replace(' ', ' ')
        if (s.isEmpty()) return null
        var marker: TxnType? = null
        Regex("""\s*\(?\b(cr|dr|c|d)\b\)?\.?$""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            marker = markerType(m.groupValues[1]); s = s.substring(0, m.range.first)
        }
        s = s.replace(Regex("""(?i)^(rs\.?|inr|₹)\s*"""), "").replace(Regex("""(?i)\s*(rs\.?|inr|₹)$"""), "").trim()
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
        if (s.startsWith("-")) { negative = true; s = s.substring(1) } else if (s.startsWith("+")) s = s.substring(1)
        if (s.endsWith("-")) { negative = true; s = s.dropLast(1) }
        s = s.replace(",", "").replace(" ", "")
        if (!Regex("""\d+(\.\d+)?|\.\d+""").matches(s)) return null
        val bd = s.toBigDecimalOrNull() ?: return null
        val minor = bd.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        return (if (negative) -minor else minor) to marker
    }

    private val months = mapOf(
        "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "apr" to Month.APRIL,
        "may" to Month.MAY, "jun" to Month.JUNE, "jul" to Month.JULY, "aug" to Month.AUGUST,
        "sep" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
    )
    private val numericDate = Regex("""^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})$""")
    private val isoDate = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val textDate = Regex("""^(\d{1,2})(?:st|nd|rd|th)?[\s\-/]?([a-z]{3,9})\.?[\s\-/,]*(\d{2,4})$""", RegexOption.IGNORE_CASE)
    private val textDateUs = Regex("""^([a-z]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})$""", RegexOption.IGNORE_CASE)

    /** A whole cell holding a date, optionally followed by a time ("05/04/2025 14:32:10"). */
    fun parseDateTime(cell: String): Pair<LocalDate, LocalTime?>? {
        val s = cell.trim().replace(' ', ' ')
        // Excel stores dates as day counts since 1899-12-30.
        s.toDoubleOrNull()?.let { n ->
            if (n in 30000.0..80000.0) {
                val days = kotlin.math.floor(n).toLong()
                val secs = ((n - days) * 86400).toLong()
                return LocalDate.of(1899, 12, 30).plusDays(days) to (if (secs > 0) LocalTime.ofSecondOfDay(secs.coerceAtMost(86399)) else null)
            }
            return null
        }
        val m = Regex("""^(.+?)(?:[\sT]+(\d{1,2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:\s*[ap]\.?m\.?)?))?$""", RegexOption.IGNORE_CASE).find(s) ?: return null
        val date = parseDate(m.groupValues[1].trim()) ?: return null
        return date to m.groupValues[2].ifBlank { null }?.let { parseTime(it) }
    }

    fun parseDate(text: String): LocalDate? {
        val s = text.trim().trimEnd(',')
        numericDate.find(s)?.let { m ->
            return safe(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        isoDate.find(s)?.let { m -> return safe(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        textDate.find(s)?.let { m ->
            val month = months[m.groupValues[2].lowercase().take(3)] ?: return null
            return safe(year(m.groupValues[3]), month.value, m.groupValues[1].toInt())
        }
        textDateUs.find(s)?.let { m ->
            val month = months[m.groupValues[1].lowercase().take(3)] ?: return null
            return safe(m.groupValues[3].toInt(), month.value, m.groupValues[2].toInt())
        }
        return null
    }

    private fun parseTime(text: String): LocalTime? {
        val m = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?\s*([ap])?""", RegexOption.IGNORE_CASE).find(text) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val sec = m.groupValues[3].ifBlank { "0" }.toInt()
        when (m.groupValues[4].lowercase()) {
            "p" -> if (h < 12) h += 12
            "a" -> if (h == 12) h = 0
        }
        return runCatching { LocalTime.of(h, min, sec) }.getOrNull()
    }

    private fun year(s: String): Int = s.toInt().let { if (s.length <= 2) 2000 + it else it }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? =
        if (y !in 1990..2100) null else runCatching { LocalDate.of(y, m, d) }.getOrNull()

    // ---------------------------------------------------------------- text hints

    private val summaryWords = Regex(
        """^\W*(opening|closing)\s+balance|^\W*total\b|balance\s+(b/f|c/f|brought|carried)|^\W*(b/f|c/f|brought\s+forward|carried\s+forward)(\W|$)|^\W*(previous|last)\s+(statement\s+)?balance|^\W*(minimum|total)\s+(amount\s+)?due|^\W*statement\s+(date|period)|^\W*payment\s+due\s+date""",
        RegexOption.IGNORE_CASE,
    )

    private fun isSummaryLine(desc: String) = summaryWords.containsMatchIn(desc.trim())

    private val footerWords = Regex(
        """\bpage\s+\d|\bpage\b.*\bof\b|statement\s+of\s+account|computer\s+generated|does\s+not\s+require|registered\s+office|customer\s+care|call\s+us|www\.|https?://|\*{3,}|^-{3,}|^date\b.*\b(narration|description|particulars)""",
        RegexOption.IGNORE_CASE,
    )

    private fun isFooter(line: String) = footerWords.containsMatchIn(line)

    /** ICICI's savings statement (2022 on): "12 05.04.2022 209579555629", the remarks with the amount and balance below. */
    private val numberedStart = Regex("""^(\d{1,5})\s+(\d{2}[./-]\d{2}[./-]\d{4})\b\s*(.*)$""")
    private val amountAndBalance = Regex("""^(.*?)\s*(-?[\d,]+\.\d{2})\s+(-?[\d,]+\.\d{2})\s*$""")
    /** "Statement of Transactions in Saving Account no. 602801514144": the account each year's table is for. */
    private val numberedAccount = Regex("""statement\s+of\s+transactions\s+in\s+\w+\s+account\s+(?:no\.?\s*)?[Xx*\d]*?(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val numberedHeaderLine = Regex("""^(s\s*no\.?\s+transaction.*|date\s+cheque\s+number.*|amount\s+\(inr\).*)$""", RegexOption.IGNORE_CASE)
    private val numberedHeader = Regex("""S\s*No\.?\s+Transaction""", RegexOption.IGNORE_CASE)
    private val pageNoise = Regex(
        """^(\d{1,4}|www\.\S+.*|please\s+call.*|never\s+share.*|s\s*no\.?\s+transaction.*|date\s+cheque.*|amount\s+\(inr\).*|.*legends?\s+used.*|.*this\s+is\s+a(n)?\s+(system|computer).*|.*end\s+of\s+statement.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Bank statements where each transaction is numbered: a line with the number, the date and the cheque / UPI
     * reference column, then the remarks over a few lines, one of which ends with the amount and the balance.
     * Withdrawal and deposit share no marker in the text, so the direction comes from the change in balance.
     */
    fun numberedBank(text: String): Pair<ParsedStatement, Set<Int>>? {
        if (!numberedHeader.containsMatchIn(text)) return null
        val used = HashSet<Int>()
        data class N(val date: LocalDate, val refCol: String, val desc: StringBuilder, val acct: String?, var amount: Long? = null, var balance: Long? = null, var open: Boolean = true)
        var acct: String? = null
        val out = ArrayList<N>()
        var expectNo = 1
        for ((idx, raw) in text.lines().withIndex()) {
            val t = raw.replace('\u00A0', ' ').trim()
            if (t.isEmpty()) continue
            numberedAccount.find(t)?.let { acct = it.groupValues[1] }
            val st = numberedStart.find(t)
            // Numbering starts again at 1 for each year in a file that joins several.
            if (st != null && (st.groupValues[1].toInt() == expectNo || (st.groupValues[1] == "1" && out.isNotEmpty()))) {
                if (st.groupValues[1] == "1") expectNo = 1
                used += idx
                val date = parseDate(st.groupValues[2].replace('.', '/')) ?: continue
                expectNo++
                val rest = st.groupValues[3]
                val n = N(date, rest, StringBuilder(), acct)
                amountAndBalance.find(rest)?.let { m ->
                    n.amount = parseMoney(m.groupValues[2])?.first; n.balance = parseMoney(m.groupValues[3])?.first
                }
                out += n
                continue
            }
            if (numberedHeaderLine.matches(t)) { used += idx; continue }
            val n = out.lastOrNull() ?: continue
            if (!n.open) continue
            used += idx
            if (pageNoise.matches(t)) { if (n.amount != null) n.open = false; continue }
            if (n.amount == null) {
                val m = amountAndBalance.find(t)
                if (m != null) {
                    n.amount = parseMoney(m.groupValues[2])?.first
                    n.balance = parseMoney(m.groupValues[3])?.first
                    n.desc.append(' ').append(m.groupValues[1])
                    continue
                }
            }
            // A long UPI narration runs over 3-4 lines; anything further is page text.
            if (n.desc.length < 240) n.desc.append(' ').append(t) else n.open = false
        }
        val done = out.filter { it.amount != null && it.amount!! != 0L }
        if (done.size < 2 || done.size < out.size * 0.9) return null
        val dirs = directionsFromBalance(done.map { abs(it.amount!!) to it.balance })
        val rows = done.mapIndexed { i, n ->
            // UPI remarks wrap mid-word ("...Payment for\nJEn/RAZORPAY..."): spaces kept, as the bank's other layouts.
            val desc = cleanDesc(n.desc.toString())
            val refCol = n.refCol.trim().takeIf { it.matches(Regex("""\d{6,}""")) }
            val type = dirs[i] ?: when {
                n.refCol.contains("debit", ignoreCase = true) -> TxnType.DEBIT
                else -> guessType(desc, StatementKind.BANK)
            }
            StatementRow(n.date, null, desc, abs(n.amount!!), type, n.balance?.let { abs(it) }, referenceIn(desc) ?: refCol, cardTail = n.acct)
        }
        // The heading above the first table: further down, UPI remarks name other banks (HDFC, Axis ...).
        val head = numberedHeader.find(text)?.let { text.substring(0, it.range.first) }?.ifBlank { null } ?: text.take(3000)
        return ParsedStatement(
            rows, StatementKind.BANK, bankIn(head) ?: bankIn(text.take(6000)), accountTailIn(head) ?: accountTailIn(text.take(6000)),
            check = runCatching { StatementCheck.bank(rows, null) }.getOrNull(),
            numbersSeen = numbersIn(text.take(8000)),
        ) to used
    }

    /** ICICI's "View last statement" page: "11-10-2026 UPI-150843051192- 1397 Dr. 27 14333158270". */
    private val webViewLine = Regex("""^(\d{2}-\d{2}-\d{4})\s+(.*?)\s+(\d[\d,]*(?:\.\d{1,2})?)\s+(Dr|Cr)\.?\s+(-?\d+)\s+(\d{9,16})\s*$""", RegexOption.IGNORE_CASE)
    private val webViewHeader = Regex("""reward\s+points\s+reference\s+number""", RegexOption.IGNORE_CASE)
    private val webViewEnd = Regex("""^(page\s+\d|note|important|disclaimer|\*|for\s+any|this\s+is\s+a)""", RegexOption.IGNORE_CASE)

    private fun inr(label: String, text: String): Long? =
        Regex("""$label\s+INR\s+(-?[\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.let { parseMoney(it)?.first }

    /**
     * The card statement as ICICI's website shows it ("View last statement", printed to PDF): amounts without
     * paise ("1397 Dr."), reward points, and the bank's reference number at the end of the line, with the UPI
     * narration running over the lines below. The reference number is kept as the line's transaction ID.
     */
    fun cardWebView(text: String): ParsedStatement? {
        if (!webViewHeader.containsMatchIn(text)) return null
        val dateFmt = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
        data class W(val date: LocalDate, val desc: StringBuilder, val amount: Long, val type: TxnType, val ref: String)
        val out = ArrayList<W>()
        var open = false
        for (raw in text.lines()) {
            val t = raw.replace('\u00A0', ' ').trim()
            if (t.isEmpty()) continue
            val m = webViewLine.find(t)
            if (m != null) {
                val date = runCatching { LocalDate.parse(m.groupValues[1], dateFmt) }.getOrNull() ?: continue
                val amount = parseMoney(m.groupValues[3])?.first ?: continue
                val type = if (m.groupValues[4].equals("cr", ignoreCase = true)) TxnType.CREDIT else TxnType.DEBIT
                out += W(date, StringBuilder(m.groupValues[2]), kotlin.math.abs(amount), type, m.groupValues[6])
                open = true
            } else if (open) {
                if (webViewEnd.containsMatchIn(t) || lineStart.find(t) != null) { open = false; continue }
                out.last().desc.append(' ').append(t)
            }
        }
        if (out.size < 2) return null
        val rows = out.map { w ->
            // The UPI narration is broken over lines ("..._UPI-150843\n051192-SAMCO"): joined without the breaks.
            val desc = cleanDesc(w.desc.toString())
            // The UPI reference (which the SMS quotes too) when there is one, else the bank's reference number.
            StatementRow(w.date, null, desc, w.amount, w.type, null, referenceIn(desc) ?: w.ref, serial = w.ref)
        }.filter { it.amountMinor > 0 }
        val due = inr("Total Amount Due", text)
        val previous = inr("Previous Balance", text)
        val dueDate = Regex("""Payment\s+Due\s+Date\s+(\d{2}-\d{2}-\d{4})""", RegexOption.IGNORE_CASE).find(text)
            ?.let { runCatching { LocalDate.parse(it.groupValues[1], dateFmt) }.getOrNull() }
        val check = if (due != null && previous != null) StatementCheck.card(rows, previous, due) else null
        return ParsedStatement(
            rows, StatementKind.CARD, bankIn(text.take(6000)) ?: "ICICI Bank", accountTailIn(text.take(6000)),
            billTotalMinor = due, billMinMinor = inr("Minimum Amount Due", text), billDueDate = dueDate, check = check,
            numbersSeen = numbersIn(text.take(8000)),
        )
    }

    private fun cleanDesc(s: String): String = s.replace(Regex("""\s+"""), " ").trim().trim('-', '|', ',').trim()

    // "4375 XXXX XXXX 4007", "4375XXXXXXXX5010", "4375 47XX XXXX 5002", "XXXX XXXX XXXX 5002", "4375-XXXX-XXXX-5002"
    private val cardNumberRe = Regex("""(?<![\w*])(?:\d{4}|[Xx*]{4})[\s-]?(?:\d{2}[Xx*]{2}|[Xx*]{4})[\s-]?[Xx*]{4}[\s-]?(\d{4})\b""")

    private val accountWord = Regex("""\b(?:a/c|acct|account)\b""", RegexOption.IGNORE_CASE)
    /** How an account heading reads: "Savings Account …", "Account No …", "Current A/c Number …". */
    private val accountHeading = Regex(
        """\b(?:savings?|current|salary|overdraft|nre|nro)\s+(?:a/c|acct|account)\b|\b(?:a/c|acct|account)\s*(?:no|number|num)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** A narration that mentions an account ("NEFT TO A/C 1234…") is not a heading. */
    private val narrationCodes = Regex("""\b(?:neft|imps|rtgs|upi|trf|inft|mmt|transfer|transferred|credited|debited|bil)\b""", RegexOption.IGNORE_CASE)
    private val accountNumber = Regex("""(?<![\w/])(?:[Xx*•]{2,}[\s-]?(?:[Xx*•]+[\s-]?)*\d{3,8}|\d{9,18})(?![\w/])""")

    /** The last 4 digits of the account a heading line names ("Savings Account XXXXXXXX4144", "A/c No: 012301234567"). */
    fun accountTailOfHeading(line: String): String? {
        if (!accountHeading.containsMatchIn(line) || narrationCodes.containsMatchIn(line)) return null
        val w = accountWord.find(line) ?: return null
        if (cardNumberRe.containsMatchIn(line) && line.contains("card", ignoreCase = true)) return null
        val n = accountNumber.find(line, w.range.last + 1)?.takeIf { it.range.first - w.range.last < 40 } ?: return null
        return n.value.filter(Char::isDigit).takeLast(4).takeIf { it.length == 4 }
    }

    /**
     * Consolidated statements can print the same statement (or month) twice. A card line's serial number is
     * never reused, so a table whose numbered lines were all read already is a repeat and is left out, as is
     * any other numbered line seen before. A bank line with the same amount and running balance is a repeat too.
     */
    private fun withoutRepeats(rows: List<StatementRow>, sections: List<Int>, bank: Boolean): List<StatementRow> {
        if (bank) {
            // The same day, amount and balance with the same narration (or part of it: a page repeated in a joined
            // file can be read with less of the narration) is one line printed twice. A debit and its reversal
            // repeated bring back the same balances with other reference numbers: those stay.
            val kept = HashMap<List<Any?>, MutableList<String>>()
            return rows.filter { r ->
                if (r.balanceMinor == null) return@filter true
                val k = listOf(r.cardTail, r.date, r.amountMinor, r.type, r.balanceMinor)
                val d = r.description.lowercase().replace(Regex("""\s+"""), " ").trim()
                val list = kept.getOrPut(k) { ArrayList() }
                if (list.any { it == d || (d.length >= 24 && it.contains(d)) || (it.length >= 24 && d.contains(it)) }) false else { list += d; true }
            }
        }
        // A serial number belongs to one transaction, whichever card's table it is printed under.
        fun key(r: StatementRow) = listOf(r.serial, r.date, r.amountMinor, r.type)
        fun plain(r: StatementRow) = listOf(r.date, r.amountMinor, r.type, r.description.lowercase())
        val seenBefore = HashSet<List<Any?>>()
        val plainBefore = HashSet<List<Any?>>()
        val bySection = rows.indices.groupBy { sections[it] }
        val keep = BooleanArray(rows.size) { true }
        for ((_, idx) in bySection.entries.sortedBy { it.key }) {
            val numbered = idx.filter { rows[it].serial != null }
            val repeats = numbered.filter { key(rows[it]) in seenBefore }
            if (numbered.isNotEmpty() && repeats.size == numbered.size) idx.forEach { keep[it] = false }
            else {
                repeats.forEach { keep[it] = false }
                // A table that repeats numbered lines also repeats the fee and interest lines beside them.
                if (repeats.isNotEmpty()) idx.filter { rows[it].serial == null && plain(rows[it]) in plainBefore }.forEach { keep[it] = false }
            }
            numbered.forEach { seenBefore += key(rows[it]) }
            idx.forEach { plainBefore += plain(rows[it]) }
        }
        return rows.filterIndexed { i, _ -> keep[i] }
    }
    private val percentRe = Regex("""\S*\d+(?:\.\d+)?%""")
    /** Lines below a transaction that are not part of its description: headings, legal text, reward panels. */
    /** A narration's last line in ICICI's layout: "s Bank Ltd 0.00". */
    private val zeroTail = Regex("""^(.*[A-Za-z0-9].*?)\s+0\.00$""")
    /** Table headings repeated on each page, never part of a narration. */
    private val headingWords = Regex("""\b(PARTICULARS|WITHDRAWALS|DEPOSITS|BALANCE|NARRATION|DESCRIPTION)\b|^DATE\b""", RegexOption.IGNORE_CASE)

    private val notDescription = Regex(
        """^#|^\d{1,2}:\d{2}(?::\d{2})?\s*(?:am|pm)?$|transaction\s+details|card\s+(number|account)|category\s+of\s+service|registration\s*no|\brewards?\b|\bpoints\b|invoice\s+no|\bcin\b|^(date|serno|sl\.?\s*no)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val hdfcCredit = Regex("""(?:^|\s)\+\s*(?:C|₹)?\s*$""")
    private val rupeeGlyph = Regex("""\s+C$""")
    /** IDFC FIRST prints a "Convert" (to EMI) button text inside the description. */
    private val convertWord = Regex("""\bConvert\b""")
    private val prevBalanceLabel = Regex("""\b(?:previous|last|opening)\s+(?:statement\s+)?(?:balance|dues|outstanding)\b""", RegexOption.IGNORE_CASE)

    /** A card statement's previous balance ("Previous Balance ₹12,345.00"), when printed with its label. */
    fun previousBalanceIn(text: String): Long? {
        val flat = text.replace(' ', ' ').take(8000)
        // ICICI: "Total Amount due  Previous Balance Purchases ..." over "= + + -", then the due, then the other figures.
        iciciSummary.find(flat)?.let { m -> return parseMoney(m.groupValues[1])?.first }
        val at = prevBalanceLabel.find(flat) ?: return null
        val m = moneyRe.find(flat, at.range.last + 1)?.takeIf { it.range.first - at.range.last < 60 } ?: return null
        return parseMoney(m.value)?.let { (v, marker) -> if (marker == TxnType.CREDIT) -abs(v) else v }
    }

    private val iciciSummary = Regex("""=\s*\+\s*\+\s*-\s*\n\s*\S?\s*[\d,]+\.\d{2}\s*\n\s*\S?\s*([\d,]+\.\d{2})""")
    private val currencyColumn = Regex("""\s+(USD|EUR|GBP|AED|SGD|MYR|THB|JPY|AUD|CAD|CHF|HKD|CNY|LKR|NPR|SAR|QAR|NZD|IDR|VND|KRW|ZAR)$""")
    /** Card statements: the serial / reference number printed before the description. */
    private val serialFirst = Regex("""^\d{9,24}\s+""")
    /** Card statements: the reward points column printed after the description. */
    private val pointsLast = Regex("""\s+-?\d{1,6}$""")

    private val upiRef = Regex("""(?<!\d)(\d{12})(?!\d)""")

    /** The 12-digit UPI / IMPS reference in a narration; SMS alerts quote the same number. */
    fun referenceIn(text: String): String? = upiRef.find(text)?.groupValues?.get(1)

    private val cardHints = Regex("""credit\s*card|card\s*statement|card\s*no|card\s*number|minimum\s+amount\s+due|total\s+amount\s+due|payment\s+due\s+date|credit\s+limit""", RegexOption.IGNORE_CASE)

    private fun looksLikeCard(text: String) = cardHints.containsMatchIn(text)

    private val tailRe = Regex(
        """\b(?:a/?c|acc(?:ount|t)?|card)\s*(?:no|number|num)?\.?\s*[:#\-]?\s*([0-9Xx*•\s\-]{6,24}\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val maskedRe = Regex("""\b[0-9]{4,6}[Xx*•]{4,}[0-9Xx*•]*?(\d{4})\b|\b[Xx*•]{4,}\s?(\d{4})\b|\b\d{4}\s[Xx*]{4}\s[Xx*]{4}\s(\d{4})\b""")

    private val numberRe = Regex("""(?<![\d])(\d{4,6})?[\sXx*•-]*[Xx*•]{2,}[\sXx*•-]*(\d{4})(?!\d)|(?<![\d])(\d{9,18})(?!\d)""")

    /** Every masked or full card / account number in [text], as (first digits when printed, last 4). */
    fun numbersIn(text: String): List<Pair<String?, String>> =
        numberRe.findAll(text).map { m ->
            if (m.groupValues[2].isNotEmpty()) m.groupValues[1].ifEmpty { null } to m.groupValues[2]
            else m.groupValues[3].let { d -> (if (d.length >= 15) d.take(6) else null) to d.takeLast(4) }
        }.distinct().toList()

    fun accountTailIn(text: String): String? {
        tailRe.find(text)?.let { m -> return m.groupValues[1].filter(Char::isDigit).takeLast(4) }
        maskedRe.find(text)?.let { m -> return m.groupValues.drop(1).first { it.isNotEmpty() } }
        return null
    }

    private val banks = listOf(
        "HDFC Bank" to Regex("""\bhdfc\b""", RegexOption.IGNORE_CASE),
        "ICICI Bank" to Regex("""\bicici\b""", RegexOption.IGNORE_CASE),
        "SBI" to Regex("""\bstate bank of india\b|\bsbi\s*card\b|\bsbi\b""", RegexOption.IGNORE_CASE),
        "Axis Bank" to Regex("""\baxis\s*bank\b""", RegexOption.IGNORE_CASE),
        "Kotak" to Regex("""\bkotak\b""", RegexOption.IGNORE_CASE),
        "Yes Bank" to Regex("""\byes\s*bank\b""", RegexOption.IGNORE_CASE),
        "IndusInd" to Regex("""\bindusind\b""", RegexOption.IGNORE_CASE),
        "IDFC First" to Regex("""\bidfc\b""", RegexOption.IGNORE_CASE),
        "Canara Bank" to Regex("""\bcanara\b""", RegexOption.IGNORE_CASE),
        "Bank of Baroda" to Regex("""\bbank of baroda\b""", RegexOption.IGNORE_CASE),
        "PNB" to Regex("""\bpunjab national\b""", RegexOption.IGNORE_CASE),
        "Union Bank" to Regex("""\bunion bank\b""", RegexOption.IGNORE_CASE),
        "AU Bank" to Regex("""\bau small finance\b""", RegexOption.IGNORE_CASE),
        "Federal Bank" to Regex("""\bfederal bank\b""", RegexOption.IGNORE_CASE),
        "American Express" to Regex("""\bamerican express\b""", RegexOption.IGNORE_CASE),
        "RBL Bank" to Regex("""\brbl\b""", RegexOption.IGNORE_CASE),
    )

    /** The bank that most often names itself in [text]; the first mention wins ties. */
    fun bankIn(text: String): String? =
        banks.map { (name, re) -> name to re.findAll(text).toList() }
            .filter { it.second.isNotEmpty() }
            .maxWithOrNull(compareBy<Pair<String, List<MatchResult>>> { it.second.size }.thenBy { -it.second.first().range.first })
            ?.first
}

/** Turns a bank narration ("UPI-SWIGGY-swiggy@icici-...") into a readable merchant / person name. */
object Narration {
    private val ifsc = Regex("""^[A-Z]{4}0[A-Z0-9]{6}$""")
    private val bankCode = Regex("""^[A-Z]{4}$|^[A-Z]{4}\d{1,7}$""")
    private val noise = setOf(
        "UPI", "DR", "CR", "P2M", "P2A", "PAYMENT", "PAY", "PAID", "VIA", "NEFT", "IMPS", "RTGS", "MMT", "POS", "ECOM", "ACH", "NACH",
        "TRF", "TRANSFER", "TO", "BY", "FROM", "INB", "IB", "MOB", "MB", "BIL", "BILLPAY", "ONL", "UPIINTENT", "COLLECT", "REQ",
        "SENT", "USING", "THE", "NA", "NULL", "OTHERS", "REF", "NO", "TXN", "INR", "IN", "IND", "INDIA", "PVT", "LTD",
        "RVSL", "REV", "AUTOPAY", "MANDATE", "EXECUTE", "OK", "YBL", "IBL", "AXL", "PAYTM", "APL", "OKAXIS", "OKICICI",
        "OKHDFCBANK", "OKSBI",
    )

    /**
     * The merchant or person a statement line is about, read from its narration. Never empty for a narration
     * with any text: when no name can be picked out, a short cleaned form of the narration itself.
     */
    fun merchant(description: String): String? {
        val d = description.removePrefix("Statement: ").substringBefore("\n\nSMS: ").replace(Regex("""\s+"""), " ").trim()
        if (d.isEmpty()) return null
        // A UPI code for a payee ("yblec123879a52a") gives way to the brand the narration names.
        val name = PayeeNames.better(named(d) ?: fallback(d), d) ?: return null
        // One name for one business, however the bank spells it ("SWIGGY8@ybl", "Swiggy Ltd", "upiswiggy").
        brandOf(name)?.let { return it }
        if (weak(name)) brandOf(d.replace(codeToken, " "))?.let { return it }
        return name
    }

    /** Bank codes in a narration (transaction hashes), which can spell anything. */
    private val codeToken = Regex("""\b[A-Za-z]{2,4}[0-9a-fA-F]{16,}\b|\b[0-9a-f]{16,}\b""")

    private fun weak(name: String): Boolean = upiFiller.matches(name.uppercase()) || name.length <= 4 ||
        name.startsWith("Upi ", ignoreCase = true) || name.startsWith("Paytm", ignoreCase = true) || name == "CMS collection"

    /** Businesses by the many ways banks print them. Checked in order: the more specific first. */
    private val brands: List<Pair<Regex, String>> = listOf(
        """\bSWIGGY|UPISWIGGY""" to "Swiggy",
        """\bZOMATO""" to "Zomato",
        """\bZEPTO""" to "Zepto",
        """\bBLINKIT|\bGROFERS""" to "Blinkit",
        """\bBBDAIL|\bBB\s?DAILY|\bBBNOW|\bBIG\s?BASKET|SUPERMARKET\s+GROCERY\s+SUPP""" to "BigBasket",
        """AMAZONCHANNELS|PRIME\s?VIDEO""" to "Amazon Prime Video",
        """\bAMAZON""" to "Amazon",
        """\bFLIPKART""" to "Flipkart",
        """\bMYNTRA""" to "Myntra",
        """\bIRCTC""" to "IRCTC",
        """\bHUNGER\s?BO\s?X""" to "Hungerbox",
        """\bSMARTQ""" to "SmartQ",
        """SHAREKHAN""" to "Sharekhan",
        """\bLIC\s?HOUSING|LICHOUSING""" to "LIC Housing Finance",
        """\bHDFC\s?LTD|\bHDFCLTD""" to "HDFC Ltd",
        """\bBOB\s+LOAN""" to "Bank of Baroda loan",
        """\bBAJAJ\s?FIN|\bBAJAJ_AUTO|\bBAJAJ\s+AUTO\s+FIN""" to "Bajaj Finance",
        """ICICI\s+BANK\s+CREDIT\s+CA""" to "Credit card bill",
        """\bCRED(CC|CLUB)?@|\bPAYMENT\s+ON\s+CRED\b|^CRED$""" to "CRED",
        """\bRENTOMOJO""" to "RentoMojo",
        """\bDUNZO""" to "Dunzo",
        """PLAYSTORE|\bGOOGLE\s?PLAY""" to "Google Play",
        """GOOGLECLOUD|\bGOOGLE\s?CLOUD""" to "Google Cloud",
        """\bOPENAI|\bCHATGPT""" to "OpenAI ChatGPT",
        """\bURBAN\s?CO(MPANY)?\b|\bURBANCOM|\bURBANCLAP""" to "Urban Company",
        """\bBOTTLE\s?L(AB)?\b""" to "Bottle Lab",
        """\bVENDOLIT""" to "Vendolite",
        """\bPVR\b|PVRCINEMAS""" to "PVR",
        """\bTENDERCUTS""" to "TenderCuts",
        """\bLICIOUS""" to "Licious",
        """\bTIMESPRIME|\bTIMES\s+PRIME""" to "Times Prime",
        """\bVIACOM\s?18""" to "Viacom18",
        """\bCROMA\b""" to "Croma",
        """\bREL(IANCE)?\s+RETAIL""" to "Reliance Retail",
        """\bSARAVANA\s+ST""" to "Saravana Stores",
        """\bZUDIO""" to "Zudio",
        """\bNANDO\s?S\b""" to "Nando's",
        """\bUBER\b""" to "Uber",
        """\bOLA\s?CABS|\bOLACABS""" to "Ola",
        """\bRAPIDO""" to "Rapido",
        """\bMAKEMYTRIP""" to "MakeMyTrip",
        """\bDMART|AVENUE\s+SUPERMARTS""" to "DMart",
        """\bNETFLIX""" to "Netflix",
        """\bSPOTIFY""" to "Spotify",
        """\bHOTSTAR""" to "Disney+ Hotstar",
        """\bYOUTUBE""" to "YouTube",
        """\bMAX\s+HYPERMARKET""" to "Max Hypermarket",
        """\bLIFE\s?STYLE\s+INTERNATION""" to "Lifestyle",
        """PHONEPEMERCHANT|\bPHONEPE\s+REVERSA""" to "PhonePe",
    ).map { Regex(it.first, RegexOption.IGNORE_CASE) to it.second }

    /** The business [text] names, when it is one of the known ones. */
    fun brandOf(text: String): String? = brands.firstOrNull { it.first.containsMatchIn(text) }?.second

    /** ICICI's mode column ("NET BANKING", "DEBIT CARD") and cheque numbers printed before the narration. */
    private val modePrefix = Regex(
        """^(?:(?:NET BANKING|MOBILE BANKING|INTERNET BANKING|DEBIT CARD|CREDIT CARD|CMS TRANSACTION|OTHER ATMS|ICICI ATM|ATM|BRANCH|CASH|CLEARING|TRANSFER|CHEQUE\s+\d+|CHQ\s+\d+)\s+)+""",
        RegexOption.IGNORE_CASE,
    )
    /** Remarks a UPI app fills in by itself, which say nothing about the payee. */
    private val upiFiller = Regex(
        """^(PAYMENT(\s+(FROM|TO|FOR|ON|FO|FR|F|T)\b.*)?|PAY\s+TO\b.*|SENT\s+(FROM|USING|VIA)\b.*|UPI(\s+.*)?|NA|NO\s+REMARKS?|TEST|PAY|PAID|COLLECT.*|REQUEST.*|""" +
            """PHONEPE.*|GOOGLE\s*PAY.*|PAYTM.*|YOU\s+ARE\s+PAYING.*|ON\s+TAPPING\s+PAY.*|PAY\s*VIA.*|MANDATE\s*EXE.*|DELE\b.*|.*\bREVERSA?L?|[A-Z]{1,2})$""",
    )
    /** Payment gateways: the business is the one named with them ("easebuzz.bbdaily", "adda622525.rzp"). */
    private val gateway = Regex("""^(EASEBUZZ|RAZORPAY|RAZORPA|PAYU|CCAVENUES?|BILLDESK|CASHFREE|PAYTM|PHONEPE)$""")
    /** Card statements end the merchant with its country: "OPENAI.C US*", "PENANG MY*", "LEVALLOIS FR*". */
    private val countryEnd = Regex("""\s+[A-Z]{2}\*$|\s+(IN|IND|INDIA)$""")

    private val cardBill = Regex("""^(?:[A-Z]{3}\s+)?CREDIT\s*(CARD|BILL|CARD\s*PAY)?\b|CREDIT\s+(BILL|CARD\s+PAY)""")

    /** Payees printed in a short form that is known. */
    private fun known(field: String): String? {
        val f = field.trim().uppercase()
        return when {
            f.startsWith("ADD-MONEY@PAYTM") -> "Paytm wallet"
            f.startsWith("GOOG-PAYMENT") -> "Google Pay"
            f == "HDFCLTD" || f == "HDFC LTD" -> "HDFC Ltd"
            else -> null
        }
    }

    /** Formats whose fields are known: the payee sits at a fixed place. */
    private fun named(d0: String): String? {
        val d1 = d0.replace(modePrefix, "").trim()
        // Words before the narration proper, often the end of the line above ("Bank Ltd/ ACH/…", "/2UL4qoA0 BIL/NEFT/…"):
        // start at the known prefix. ICICI's mode column ("MOBILE BANKING") can land inside a field: left out.
        val d = (Regex("""(?:^|[\s/])(BIL|UPI|MMT|NEFT|ACH|NACH|DECS|VPS|MPS|IPS|PCD|CMS|CLG|NFS)/""").find(d1)
            ?.let { d1.substring(it.groups[1]!!.range.first) } ?: d1)
            .replace(Regex("""\b(MOBILE|NET|INTERNET)\s+BANKING\b\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^ACH/TP\s+ACH\s+""", RegexOption.IGNORE_CASE), "ACH/").trim()
        val up = d.uppercase()
        // The savings account's sweep to and from a linked overdraft / FD, and its interest.
        if (Regex("""REV\s+SWEEP\s+FROM|SWEEP\s+FROM""").containsMatchIn(up)) return "Sweep from linked account"
        if (Regex("""^SWEEP\s+(?:TRF\s+)?TO""").containsMatchIn(up)) return "Sweep to linked account"
        if (Regex("""^\d{9,}:\s*INT\.?\s*PD""").containsMatchIn(up)) return "Savings interest"
        if (Regex("""^\d{9,}:\s*INT\.?\s*COLL""").containsMatchIn(up)) return "Overdraft interest"
        if (Regex("""^FUEL\s+SURCHARGE""").containsMatchIn(up)) return "Fuel surcharge"
        if (Regex("""^PERSONAL\s+LOAN\b.*\bEMI""").containsMatchIn(up)) return "Personal loan EMI"
        val parts = up.split('/').map { it.trim() }
        fun ok(s: String?) = s?.trim('.', ',', '*', '-', ' ')?.takeIf { it.count(Char::isLetter) >= 3 && it.count(Char::isDigit) <= it.count(Char::isLetter) }
        when {
            Regex("""^(SAL|ICSP|SALARY)\b""").containsMatchIn(up) || Regex("""\bSALARY\b""").containsMatchIn(up) -> return "Salary"
            Regex("""^[SCI]GST\d|^[SCI]GST\b|^IGST-""").containsMatchIn(up) -> return "GST"
            // "Principal Amount Amortization - <1/3>ALLEN CAREER INSTITUTE": an instalment of the purchase named.
            up.startsWith("PRINCIPAL AMOUNT AMORTIZATION") || up.startsWith("INTEREST AMOUNT AMORTIZATION") -> {
                val what = d.substringAfter('>', "").trim().takeIf { it.count(Char::isLetter) >= 3 }?.let { generic(it) }
                val interest = up.startsWith("INTEREST")
                return when {
                    what == null -> if (interest) "EMI interest" else "EMI principal"
                    interest -> "$what EMI interest"
                    else -> "$what EMI"
                }
            }
            Regex("""^(ANNUAL FEES?|ANNUAL FEE REVERSAL)""").containsMatchIn(up) -> return "Card annual fee"
            up.startsWith("PROCESSING FEE") -> return "Processing fee"
            // "DECS/HDFC LTD EMI DEBITS/0003050564788", "ACH/HDFCLTD/226250799": the lender.
            (parts[0] == "DECS" || parts[0] == "ACH" || parts[0] == "NACH") && parts.size >= 2 && known(parts[1]) != null -> return known(parts[1])
            parts[0] == "DECS" || parts[0] == "ACH" || parts[0] == "NACH" -> ok(parts.getOrNull(1)?.replace(Regex("""\bDEBITS?\b"""), ""))?.let { return title(it.replace(Regex("""\s+"""), " ").trim()) }
            // Debit card: "VPS/S J RETAIL /20180404132111/0", "MPS/SARAVANA ST/201903291324/826075/CHENNAI".
            parts[0] in setOf("VPS", "IPS", "MPS", "PCD", "POS", "VIN") && parts.size >= 2 -> ok(parts[1])?.let { return title(it) }
            // ICICI prints UPI lines three ways: "UPI/<ref>/<remark>/<upi id>/<bank>" (to 2024),
            // "UPI/<upi id>/<remark>/<bank>/<ref>" (2024) and "UPI/<name>/<upi id>/<remark>/<bank>/<ref>" (2025 on).
            // The name or the remark when it says something, else the UPI id; never the bank or a code.
            parts[0] == "UPI" && parts.size >= 3 -> {
                // Paytm: "UPI/904137972327/Oid7315154168@O/add-money@paytm/P aytm Payments/": an order id, then the payee.
                if (parts[1].any(Char::isDigit) && Regex("""^OID""").containsMatchIn(parts[2]) && parts.size >= 4) {
                    val payee = parts[3]
                    known(payee)?.let { return it }
                    ok(payee.substringBefore('@').replace(Regex("""^PAYTM-"""), "").replace(Regex("""[._\-\d]+"""), " ").trim())?.let { return title(it) }
                    return "Paytm"
                }
                parts.drop(1).firstNotNullOfOrNull { known(it) }?.let { return it }
                upiPayee(d.split('/').map { it.trim() })?.let { return it }
            }
            // "MMT/IMPS/825516410251/Muthulaxmi/SHAFEENA A/CORP00": the person paid, after the remark.
            parts[0] == "MMT" -> {
                if (parts.getOrNull(1) == "IMPS") {
                    ok(parts.getOrNull(4))?.takeIf { !Regex("""\bBANK\b""").containsMatchIn(it) }?.let { return title(it) }
                    ok(parts.getOrNull(3))?.let { return title(it) }
                }
                return "IMPS transfer"
            }
            // "BIL/001422771321/ChgRs10 GSTRs1.80 IRCTC/10000121", "BIL/ONL/001673518545/SHAREKHAN /5533655".
            // "BIL/INFT/001504233803/From Siva G/S.NIRMALKU/ICICI Bank", "BIL/NEFT/…/muthulaxmi/NITHYA R/CNRB0003": the person paid.
            parts[0] == "BIL" && parts.getOrNull(1) in setOf("INFT", "NEFT", "IMPS", "RTGS") -> {
                val remark = parts.getOrNull(3)
                if (remark != null && cardBill.containsMatchIn(remark)) return "Credit card bill"
                val who = parts.getOrNull(4)
                if (who == "SELF" || remark == "SELF") return "Own account transfer"
                ok(who)?.takeIf { !Regex("""\bBANK\b""").containsMatchIn(it) && !ifsc.matches(it.replace(" ", "")) }?.let { return title(it) }
                ok(remark)?.takeIf { !upiFiller.matches(it) }?.let { return title(it) }
                return if (parts[1] == "INFT") "Transfer" else "${parts[1]} transfer"
            }
            parts[0] == "BIL" && (parts.any { cardBill.containsMatchIn(it) } ||
                parts.any { it.contains("CREDIT") } && parts.any { Regex("""^\d{15,16}$""").matches(it) }) -> return "Credit card bill"
            // "CLG/LIC Housing Finance Limit/HDF 12925": a cheque cleared, to the payee named.
            parts[0] == "CLG" && parts.size >= 2 -> ok(parts[1])?.let { return title(it) }
            parts[0] == "BIL" -> {
                val fields = parts.drop(1).filter { it != "ONL" && it != "PAVC" && !Regex("""^\d+$""").matches(it) }
                    .map { it.replace(Regex("""CHGRS\s*[\d.]+|GSTRS\s*[\d.]+"""), "").trim() }
                fields.firstNotNullOfOrNull { ok(it)?.takeIf { f -> !upiFiller.matches(f) } }?.let { return title(it) }
            }
            parts[0] == "CMS" -> return "CMS collection"
            // ICICI card statements: "UPI-324565732386-K PANNEE RSELVAM IN", the name broken by a space after 8 letters.
            Regex("""^UPI-\d{9,}-""").containsMatchIn(up) -> {
                val raw = d.substringAfter('-').substringAfter('-').replace(Regex("""\s+(IN|IND|INDIA)$""", RegexOption.IGNORE_CASE), "").trim()
                val joined = if (raw.length > 9 && raw[8] == ' ' && raw[7] != ' ' && raw[9] != ' ') raw.removeRange(8, 9) else raw
                ok(joined.uppercase())?.let { return title(joined) }
            }
            Regex("""^IRCTC\s+RFND""").containsMatchIn(up) -> return "IRCTC refund"
        }
        return generic(d)
    }

    /** A field that is a UPI id: "swiggy8@ybl", or one cut short by the bank ("tendercuts.payu", "phonepemerchant"). */
    private fun isVpa(f: String): Boolean = f.contains('@') || Regex("""^[a-z0-9][a-z0-9._\-]{4,}$""").matches(f) && f.any(Char::isLetter)

    private fun isBankField(f: String): Boolean = Regex(
        """\bBANK\b|\bBANK\s?L|\bLTD\b|PAYMENTS?\b|PAYME$|^NSDL|^YES\s?BANK|^STATE\s+BANK|BANK$|^[A-Z]{4}0[A-Z0-9]{6}$""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(f)

    private fun isCodeField(f: String): Boolean = codeToken.matches(f.replace(" ", "")) || PayeeNames.isCode(f.replace(" ", "")) ||
        Regex("""^[\d\s]{6,}$""").matches(f) || Regex("""^[A-Z]{3}[0-9A-Fa-f]{10,}""").containsMatchIn(f.replace(" ", ""))

    /** The person or business a UPI line paid, from its fields in any of ICICI's orders. */
    private fun upiPayee(fields: List<String>): String? {
        fun ok(s: String?) = s?.trim('.', ',', '*', '-', ' ')?.takeIf { it.count(Char::isLetter) >= 3 && it.count(Char::isDigit) <= it.count(Char::isLetter) }
        val rest = fields.drop(1).filter { it.isNotBlank() }
        val vpa = rest.firstOrNull { isVpa(it) }
        for (f in rest) {
            if (f == vpa || isVpa(f) || isBankField(f) || isCodeField(f)) continue
            // "From Muthulaksh", "Swiggy Order Id", "Hungerbox Order": the name in it.
            val cleaned = f.replace(Regex("""^(FROM|TO)\s+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+ORDER(\s+ID)?$""", RegexOption.IGNORE_CASE), "").trim()
            val up = cleaned.uppercase()
            if (upiFiller.matches(up) || f.uppercase().let { upiFiller.matches(it) && !it.startsWith("FROM ") } || gateway.matches(up)) continue
            ok(cleaned)?.let { return title(it) }
        }
        val id = vpa ?: return null
        // "easebuzz.bbdail", "adda622525.rzp", "tendercuts.payu", "paytm-irctcapp": the business, not the gateway.
        val handle = id.substringBefore('@').lowercase()
            .replace(Regex("""^(easebuzz|razorpay|payu|cashfree|paytm|upi)[._\-]?"""), "")
            .replace(Regex("""[._\-](payu|rzp|razorpay|cf|easebuzz)$"""), "")
            .replace(Regex("""[._\-\d]+"""), " ").trim()
        ok(handle)?.let { return title(it) }
        return "UPI ${id.lowercase()}"
    }

    private val townEnd = Regex(
        """\s+(MUMBAI|BANGALORE|BENGALURU|CHENNAI|CHEENAI|KANCHIPURAM|GURGAON|GURUGRAM|NEW\s+DELHI|DELHI|NOIDA|HYDERABAD|PUNE|KOLKATA|""" +
            """CHROMPET|CHROMEPET|TAMBARAM|COIMBATORE|MADURAI|TRICHY|SALEM|AHMEDABAD|JAIPUR|KOCHI|THANE|NAVI\s+MUMBAI|WWW\.\S+|HTTPS?\S*)$""",
    )

    private fun generic(d: String): String? {
        val up = d.uppercase()
        if (Regex("""\b(ATW|ATM|NWD|CASH\s*WDL|CASH\s*WITHDRAWAL|ATD)\b""").containsMatchIn(up)) return "ATM withdrawal"
        if (Regex("""\b(INT\.?\s*PD|INTEREST\s+(PAID|CREDIT|CAPITALISED)|CREDIT\s+INTEREST|SB\s*INT)\b""").containsMatchIn(up)) return "Interest"
        if (Regex("""\bPAYMENT\s+RECEIVED|THANK\s*YOU\b""").containsMatchIn(up)) return "Card payment received"

        val structured = Regex("""[/\-|]""").findAll(up).count() >= 2
        if (structured) {
            val tokens = up.split('/', '-', '|', ':').map { it.trim() }.filter { it.isNotEmpty() }
            var vpaName: String? = null
            for (raw in tokens) {
                // Card statements end the name with the country: "UPI-123456789012-SWIGGY IN".
                val t = raw.trim('.', ',', '*').replace(Regex("""\s+(IN|IND|INDIA)$"""), "")
                if (t.contains('@')) { if (vpaName == null) vpaName = t.substringBefore('@').split(' ').last(); continue }
                if (t in noise || ifsc.matches(t) || bankCode.matches(t) && t.length <= 11 && t.take(4) in bankPrefixes) continue
                val letters = t.count(Char::isLetter)
                if (letters < 3 || t.count(Char::isDigit) > letters) continue
                if (t.split(' ').all { it in noise || it.length == 1 }) continue
                if (filler.containsMatchIn(t) || Regex("""\bBANK\b""").containsMatchIn(t)) continue
                return title(t)
            }
            vpaName?.let { v -> v.replace(Regex("""[._\d]+"""), " ").trim().takeIf { it.length >= 3 }?.let { return title(it) } }
        }
        // Card bills: "SWIGGY BANGALORE IN", "AMAZON PAY INDIA PRIVA MUMBAI".
        val plain = up.replace(Regex("""\b(POS|ECOM|PUR|PURCHASE|TXN|REF)\b|\bCYBS(\s+SI)?\b"""), " ")
            .replace(Regex("""\b[0-9X*]{6,}\b"""), " ")
            .replace(Regex("""\s+(IN|IND|INDIA)$"""), "")
            .replace(Regex("""\s+\d{4,5}$"""), "")
            .replace(Regex("""\s+"""), " ").trim()
        val noCountry = plain.replace(countryEnd, "").trim()
        // "AMAZON INDIA MUMBAI", "GOOD TO GO FOODWORKS PR KANCHIPURAM": the town the card machine is in is not the name.
        val noTown = noCountry.replace(townEnd, "").trim().takeIf { it.count(Char::isLetter) >= 3 } ?: noCountry
        return noTown.takeIf { it.count(Char::isLetter) >= 3 }?.let { title(it.take(40).trim()) }
    }

    /** Nothing better: the narration without reference numbers, cut short. */
    private fun fallback(d: String): String {
        val cleaned = d.replace(modePrefix, "").replace(Regex("""\b\d{6,}\b"""), " ").replace(Regex("""[/|]+"""), " ")
            .replace(Regex("""\s+"""), " ").trim().trim('-', ' ')
        return title(cleaned.ifEmpty { d }.take(40).trim())
    }

    private val filler = Regex("""^(PAYMENT|PAID|SENT|PAY|UPI)\s+(FROM|TO|VIA|USING|INTENT)\b""")

    private val bankPrefixes = setOf(
        "HDFC", "ICIC", "SBIN", "UTIB", "KKBK", "YESB", "PUNB", "BARB", "CNRB", "UBIN", "IDIB", "IOBA", "INDB", "IDFB",
        "FDRL", "PYTM", "AIRP", "BKID", "MAHB", "CBIN", "UCBA", "ESFB", "AUBL", "RATN", "DBSS", "SCBL", "CITI", "HSBC",
    )

    private fun title(s: String): String =
        s.lowercase().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
