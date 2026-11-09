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
        return ParsedStatement(fixed, kind, bankIn(preamble), accountTailIn(preamble))
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
    private val lineStart = Regex("""^\s*(?:\d{1,4}\s+)?$DATE_RE(?:\s+(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[ap]m)?))?\s*""", RegexOption.IGNORE_CASE)
    private val dateAnywhere = Regex("""(?<![\d/])$DATE_RE(?![\d/])""", RegexOption.IGNORE_CASE)
    // Amounts in statements always carry paise: 1,234.56 / 450.00 / -12.00 / 1,234.56 Cr
    private val moneyRe = Regex(
        """(?<![\w.,])(?:(?:rs\.?|inr|₹)\s*)?(\(?[+\-]?(?:\d{1,3}(?:,\d{2,3})+|\d+)\.\d{2}\)?)(?:\s*(cr|dr|c|d)\b)?(?![\w.]|,\d)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses statement text taken from a PDF, one transaction per line. Lines that start with a date
     * and contain amounts are transactions; lines without either that follow a transaction continue
     * its description.
     */
    fun fromText(text: String): ParsedStatement? {
        data class Line(val date: LocalDate, val time: LocalTime?, val desc: StringBuilder, val amounts: List<Pair<Long, TxnType?>>, val raw: String)

        val lines = text.lines()
        val parsed = ArrayList<Line>()
        var openingBalance: Long? = null
        var lastWasTxn = false
        var continuation = 0
        var headerText = StringBuilder()
        for (raw in lines) {
            val line = raw.replace(' ', ' ').trim()
            if (line.isEmpty()) { lastWasTxn = false; continue }
            if (parsed.isEmpty() && headerText.length < 4000) headerText.append(line).append('\n')
            val low = line.lowercase()
            if (openingBalance == null && (low.contains("opening balance") || low.contains("balance b/f") || low.contains("brought forward"))) {
                moneyRe.findAll(line).lastOrNull()?.let { openingBalance = parseMoney(it.value)?.first }
            }
            val start = lineStart.find(line)
            val date = start?.let { parseDate(it.groupValues[1]) }
            if (start == null || date == null) {
                if (lastWasTxn && continuation < 2 && moneyRe.find(line) == null && !isFooter(line) && parsed.isNotEmpty()) {
                    parsed.last().desc.append(' ').append(line)
                    continuation++
                } else lastWasTxn = false
                continue
            }
            val rest = line.substring(start.range.last + 1)
            val amounts = moneyRe.findAll(rest).toList()
            if (amounts.isEmpty() || isSummaryLine(rest)) { lastWasTxn = false; continue }
            val time = start.groupValues[2].ifBlank { null }?.let { parseTime(it) }
            // The description is what comes before the first amount, without a second (value) date.
            val descText = rest.substring(0, amounts.first().range.first).replace(dateAnywhere, " ")
            val values = amounts.mapNotNull { m -> parseMoney(m.value) }
            parsed += Line(date, time, StringBuilder(descText.trim()), values, line)
            lastWasTxn = true
            continuation = 0
        }
        if (parsed.isEmpty()) return null

        // A bank statement line ends "... amount balance"; check whether the balances chain up.
        val multi = parsed.count { it.amounts.size >= 2 }
        val asBank = parsed.map { (if (it.amounts.size >= 2) it.amounts[it.amounts.size - 2].first else it.amounts.last().first) to (if (it.amounts.size >= 2) it.amounts.last().first else null) }
        val bankDirs = if (multi >= parsed.size * 0.6) directionsFromBalance(asBank, openingBalance) else null
        val chained = bankDirs?.count { it != null } ?: 0
        val isBank = bankDirs != null && chained >= (parsed.size - 1) * 0.6 && !looksLikeCard(headerText.toString())

        val rows = parsed.mapIndexed { i, l ->
            val desc = cleanDesc(l.desc.toString())
            if (isBank) {
                val (amt, bal) = asBank[i]
                val marker = l.amounts[l.amounts.size - 2].second
                StatementRow(l.date, l.time, desc, abs(amt), bankDirs!![i] ?: marker ?: guessType(desc, StatementKind.BANK), bal?.let { abs(it) }, referenceIn(l.raw))
            } else {
                // Card bills: the transaction amount is the last money value; "Cr" marks payments and refunds.
                val (amt, marker) = l.amounts.last()
                val type = marker ?: if (amt < 0) TxnType.CREDIT else guessType(desc, StatementKind.CARD)
                StatementRow(l.date, l.time, desc, abs(amt), type, null, referenceIn(l.raw))
            }
        }.filter { it.amountMinor > 0 }
        if (rows.isEmpty()) return null
        val head = headerText.toString()
        val kind = if (isBank) StatementKind.BANK else StatementKind.CARD
        val bill = if (kind == StatementKind.CARD) runCatching { CardBills.summaryFromText(text, rows.maxOf { it.date }) }.getOrNull()
            // A due date far from the statement's last line is a misread.
            ?.takeIf { b -> rows.maxOf { it.date }.let { last -> !b.third.isBefore(last.minusDays(3)) && !b.third.isAfter(last.plusDays(60)) } }
        else null
        return ParsedStatement(
            rows, kind, bankIn(head) ?: bankIn(text.take(6000)), accountTailIn(head) ?: accountTailIn(text.take(6000)),
            billTotalMinor = bill?.first, billMinMinor = bill?.second, billDueDate = bill?.third,
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
        """\b(neft\s*cr|imps\s*cr|upi\s*/?\s*cr|by\s+transfer|by\s+clg|deposit|salary|sal\b|refund|reversal|cashback|interest\s+(?:paid|credit)|int\.?pd|credit\s+interest|payment\s+received|thank\s*you|received)""",
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
        """^\W*(opening|closing)\s+balance|^\W*total\b|balance\s+(b/f|c/f|brought|carried)|^\W*(previous|last)\s+(statement\s+)?balance|^\W*(minimum|total)\s+(amount\s+)?due|^\W*statement\s+(date|period)|^\W*payment\s+due\s+date""",
        RegexOption.IGNORE_CASE,
    )

    private fun isSummaryLine(desc: String) = summaryWords.containsMatchIn(desc.trim())

    private val footerWords = Regex(
        """\bpage\s+\d|\bpage\b.*\bof\b|statement\s+of\s+account|computer\s+generated|does\s+not\s+require|registered\s+office|customer\s+care|call\s+us|www\.|https?://|\*{3,}|^-{3,}|^date\b.*\b(narration|description|particulars)""",
        RegexOption.IGNORE_CASE,
    )

    private fun isFooter(line: String) = footerWords.containsMatchIn(line)

    private fun cleanDesc(s: String): String = s.replace(Regex("""\s+"""), " ").trim().trim('-', '|', ',').trim()

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

    fun merchant(description: String): String? {
        val d = description.trim()
        if (d.isEmpty()) return null
        val up = d.uppercase()
        if (Regex("""\b(ATW|ATM|NWD|CASH\s*WDL|CASH\s*WITHDRAWAL|ATD)\b""").containsMatchIn(up)) return "ATM withdrawal"
        if (Regex("""\b(INT\.?\s*PD|INTEREST\s+(PAID|CREDIT|CAPITALISED)|CREDIT\s+INTEREST|SB\s*INT)\b""").containsMatchIn(up)) return "Interest"
        if (Regex("""\bPAYMENT\s+RECEIVED|THANK\s*YOU\b""").containsMatchIn(up)) return "Card payment received"

        val structured = Regex("""[/\-|]""").findAll(up).count() >= 2
        if (structured) {
            val tokens = up.split('/', '-', '|', ':').map { it.trim() }.filter { it.isNotEmpty() }
            var vpaName: String? = null
            for (raw in tokens) {
                val t = raw.trim('.', ',', '*')
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
        val plain = up.replace(Regex("""\b(POS|ECOM|PUR|PURCHASE|TXN|REF)\b"""), " ")
            .replace(Regex("""\b[0-9X*]{6,}\b"""), " ")
            .replace(Regex("""\s+(IN|IND|INDIA)$"""), "")
            .replace(Regex("""\s+"""), " ").trim()
        return plain.takeIf { it.count(Char::isLetter) >= 3 }?.let { title(it.take(40).trim()) }
    }

    private val filler = Regex("""^(PAYMENT|PAID|SENT|PAY|UPI)\s+(FROM|TO|VIA|USING|INTENT)\b""")

    private val bankPrefixes = setOf(
        "HDFC", "ICIC", "SBIN", "UTIB", "KKBK", "YESB", "PUNB", "BARB", "CNRB", "UBIN", "IDIB", "IOBA", "INDB", "IDFB",
        "FDRL", "PYTM", "AIRP", "BKID", "MAHB", "CBIN", "UCBA", "ESFB", "AUBL", "RATN", "DBSS", "SCBL", "CITI", "HSBC",
    )

    private fun title(s: String): String =
        s.lowercase().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
