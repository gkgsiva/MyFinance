package com.myfinance.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One transaction with everything the user added to it, for the full export. */
data class ExportRow(
    val txn: TxnLite,
    val labels: List<String>,
    val note: String?,
    val bank: String?,
    val source: String,
    val hidden: Boolean,
    val excluded: Boolean,
    val hasPhoto: Boolean,
)

/** A full workbook for the user's own analysis in Excel or Google Sheets. */
object FullExport {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    private fun rupees(minor: Long): Double = minor / 100.0

    fun sheets(rows: List<ExportRow>, zone: ZoneId = ZoneId.systemDefault()): List<XlsxSheet> {
        val sorted = rows.sortedBy { it.txn.timestamp }
        val txns = XlsxSheet(
            "Transactions",
            listOf("Date", "Time", "Month", "Type", "Amount (₹)", "Signed (₹)", "Category", "Payee", "Labels", "Account", "Bank", "Paid by", "Person", "Note", "Source", "Counted", "Hidden", "Bill photo"),
            sorted.map { r ->
                val t = r.txn
                val at = Instant.ofEpochMilli(t.timestamp).atZone(zone)
                listOf(
                    at.format(dateFmt), at.format(timeFmt), YearMonth.from(at).toString(),
                    if (t.type == TxnType.DEBIT) "Expense" else "Income", rupees(t.amountMinor),
                    rupees(if (t.type == TxnType.DEBIT) -t.amountMinor else t.amountMinor),
                    t.category, t.merchant.orEmpty(), r.labels.joinToString(", "), t.account.orEmpty(), r.bank.orEmpty(), t.mode.label,
                    t.owner.orEmpty(), r.note.orEmpty(), r.source, if (r.excluded) "No" else "Yes", if (r.hidden) "Yes" else "No", if (r.hasPhoto) "Yes" else "No",
                )
            },
        )
        val counted = sorted.filterNot { it.excluded }
        val months = counted.map { YearMonth.from(Instant.ofEpochMilli(it.txn.timestamp).atZone(zone)) }.distinct().sorted()
        val cats = counted.filter { it.txn.type == TxnType.DEBIT }.map { it.txn.category }.distinct().sorted()
        val byMonthCat = counted.filter { it.txn.type == TxnType.DEBIT }
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.txn.timestamp).atZone(zone)) to it.txn.category }
            .mapValues { (_, v) -> v.sumOf { it.txn.amountMinor } }
        val categories = XlsxSheet(
            "Categories by month", listOf("Category") + months.map { it.toString() } + "Total",
            cats.map { c -> listOf<Any?>(c) + months.map { m -> rupees(byMonthCat[m to c] ?: 0) } + rupees(months.sumOf { m -> byMonthCat[m to c] ?: 0 }) },
        )
        val monthly = XlsxSheet(
            "Months", listOf("Month", "Income (₹)", "Spent (₹)", "Saved (₹)", "Entries"),
            months.map { m ->
                val list = counted.filter { YearMonth.from(Instant.ofEpochMilli(it.txn.timestamp).atZone(zone)) == m }
                val inc = list.filter { it.txn.type == TxnType.CREDIT }.sumOf { it.txn.amountMinor }
                val out = list.filter { it.txn.type == TxnType.DEBIT }.sumOf { it.txn.amountMinor }
                listOf(m.toString(), rupees(inc), rupees(out), rupees(inc - out), list.size)
            },
        )
        val labels = XlsxSheet(
            "Labels", listOf("Label", "Spent (₹)", "Received (₹)", "Entries"),
            counted.flatMap { r -> r.labels.map { it to r } }.groupBy({ it.first }, { it.second }).toSortedMap().map { (l, v) ->
                listOf(l, rupees(v.filter { it.txn.type == TxnType.DEBIT }.sumOf { it.txn.amountMinor }), rupees(v.filter { it.txn.type == TxnType.CREDIT }.sumOf { it.txn.amountMinor }), v.size)
            },
        )
        val payees = XlsxSheet(
            "Payees", listOf("Payee", "Spent (₹)", "Entries", "Usual category"),
            counted.filter { it.txn.type == TxnType.DEBIT && !it.txn.merchant.isNullOrBlank() }.groupBy { it.txn.merchant!! }
                .map { (p, v) -> listOf(p, rupees(v.sumOf { it.txn.amountMinor }), v.size, v.groupingBy { it.txn.category }.eachCount().maxBy { it.value }.key) }
                .sortedByDescending { it[1] as Double },
        )
        return listOf(txns, monthly, categories, labels, payees)
    }

    /** The same transactions as CSV, for apps that prefer it. */
    fun csv(rows: List<ExportRow>, zone: ZoneId = ZoneId.systemDefault()): String {
        val s = sheets(rows, zone).first()
        fun cell(v: Any?): String {
            val t = v?.toString().orEmpty()
            return if (t.any { it == ',' || it == '"' || it == '\n' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
        }
        return buildString {
            appendLine(s.header.joinToString(",") { cell(it) })
            s.rows.forEach { appendLine(it.joinToString(",") { c -> cell(c) }) }
        }
    }
}
