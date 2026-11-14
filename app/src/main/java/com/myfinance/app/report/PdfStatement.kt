package com.myfinance.app.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.myfinance.core.DefaultCategories
import com.myfinance.core.MonthReport
import com.myfinance.core.formatInr
import com.myfinance.core.isInvestment
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The month as a printable report: A4 pages in colour that still read when printed in black and white,
 * with a header and page number on every page, so it can be printed and filed. Every entry comes first,
 * the insights and charts after. Drawn with Android's built-in PDF writer; the numbers come
 * from [MonthReport].
 */
object PdfStatement {

    private const val PAGE_W = 595f // A4 at 72 dpi
    private const val PAGE_H = 842f
    private const val M = 36f
    private const val CONTENT_W = PAGE_W - 2 * M

    private val INK = Color.rgb(0x1A, 0x1A, 0x1A)
    private val SOFT = Color.rgb(0x5C, 0x5C, 0x5C)
    private val RULE = Color.rgb(0xC8, 0xC8, 0xC8)
    private val WHITE = Color.rgb(0xFF, 0xFF, 0xFF)

    // Colours are dark or light enough to stay apart when printed in black and white too.
    private val BRAND = Color.rgb(0x0F, 0x6E, 0x5A)
    private val BRAND_LIGHT = Color.rgb(0x8C, 0xC7, 0xB9)
    private val ZEBRA = Color.rgb(0xEE, 0xF6, 0xF4)
    private val IN_GREEN = Color.rgb(0x1E, 0x7D, 0x32)
    /** Lighter than [OUT_RED], so received and spent bars differ in black and white too. */
    private val IN_GREEN_LIGHT = Color.rgb(0x81, 0xC7, 0x84)
    private val OUT_RED = Color.rgb(0xC6, 0x28, 0x28)
    private val INVEST_BLUE = Color.rgb(0x15, 0x65, 0xC0)
    private val AMBER = Color.rgb(0xE0, 0x8E, 0x0B)
    private val PURPLE = Color.rgb(0x6A, 0x1B, 0x9A)
    /** The user's own notes: dark brown, so they stand out from printed text without being lost in grey. */
    private val NOTE = Color.rgb(0x6D, 0x4C, 0x1F)
    private val TINT_GREEN = Color.rgb(0xE8, 0xF5, 0xE9)
    private val TINT_RED = Color.rgb(0xFD, 0xEC, 0xEA)
    private val TINT_BLUE = Color.rgb(0xE3, 0xF2, 0xFD)
    private val TINT_AMBER = Color.rgb(0xFF, 0xF4, 0xE0)
    private val TINT_TEAL = Color.rgb(0xE0, 0xF2, 0xEE)
    private val TINT_PURPLE = Color.rgb(0xF3, 0xE8, 0xFD)

    /** For charts and categories without a colour of their own. */
    private val PALETTE = intArrayOf(
        BRAND, AMBER, INVEST_BLUE, OUT_RED, PURPLE, Color.rgb(0x7C, 0xB3, 0x42),
        Color.rgb(0xEC, 0x40, 0x7A), Color.rgb(0x5D, 0x40, 0x37), Color.rgb(0x00, 0x97, 0xA7), Color.rgb(0x9E, 0x9D, 0x24),
    )

    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy")
    private val dayFmt = DateTimeFormatter.ofPattern("dd MMM")
    private val dayShort = DateTimeFormatter.ofPattern("d MMM")
    private val stamp = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
    private val shortMonth = DateTimeFormatter.ofPattern("MMM")

    /**
     * @param categoryColors the app's colour for each category (ARGB), used for its bars and dots.
     */
    fun write(out: OutputStream, report: MonthReport, preparedFor: String, categoryColors: Map<String, Int> = emptyMap()) {
        val doc = PdfDocument()
        val month = report.period.format(monthYear)
        val w = Writer(doc, "MyFinance · Monthly report · $month", preparedFor, "Made ${LocalDateTime.now().format(stamp)}")
        val fallback = HashMap<String, Int>()
        fun catColor(c: String): Int = categoryColors[c] ?: fallback.getOrPut(c) { PALETTE[fallback.size % PALETTE.size] }

        // ---- Title and summary ----
        w.text(month, 22f, bold = true, color = BRAND)
        w.text(listOfNotNull(report.rangeLabel, report.cycleNote).joinToString(" · "), 9.5f, color = SOFT)
        w.text("Prepared for $preparedFor · ${report.rows.size} entries" +
            (if (report.hiddenCount > 0) " (${report.hiddenCount} hidden, still counted)" else ""), 9.5f, color = SOFT)
        w.gap(8f)
        w.tiles(
            listOf(
                Tile("Received", formatInr(report.incomeMinor), TINT_GREEN, IN_GREEN),
                Tile("Spent", formatInr(report.spentMinor), TINT_RED, OUT_RED),
                Tile("Invested", formatInr(report.investedMinor), TINT_BLUE, INVEST_BLUE),
                Tile("Left over", formatInr(report.leftMinor), TINT_TEAL, if (report.leftMinor < 0) OUT_RED else BRAND),
                Tile("Saved (after spending)", report.savingsRatePercent?.let { "$it%" } ?: "–", TINT_TEAL, BRAND),
                Tile("On credit cards", formatInr(report.cardSpentMinor), TINT_PURPLE, PURPLE),
                Tile("Biggest category", report.categories.firstOrNull()?.key ?: "–", TINT_AMBER, INK),
                Tile("Not counted", "${report.notCounted.size} entries", TINT_AMBER, INK),
            ),
        )
        w.small("Invested money (SIP, mutual funds, FD …) is not spending. Own-account transfers and card bill payments are listed after the entries and change no total.")

        // ---- Every entry ----
        w.heading("All entries")
        if (report.rows.isEmpty()) w.small("No entries this month.")
        val cols = listOf(
            Col("No.", 0.05f), Col("Date", 0.075f), Col("Payee / your note", 0.235f), Col("Category · labels", 0.17f), Col("Account", 0.11f),
            Col("Out", 0.12f, right = true), Col("In", 0.12f, right = true), Col("Balance", 0.12f, right = true),
        )
        val rows = report.rows
        w.table(
            cols,
            rows.map { r ->
                // Whose it is, for family members' entries merged in.
                val payee = r.payee + (r.txn.owner?.takeIf { report.people.size > 1 && it != preparedFor }?.let { " · $it" } ?: "") +
                    (if (r.hidden) " (hidden)" else "")
                val cat = (if (r.txn.isInvestment && r.txn.category != DefaultCategories.INVESTMENTS) "Invested: " else "") + r.txn.category +
                    (if (r.labels.isNotEmpty()) " · " + r.labels.joinToString(", ") else "")
                listOf(
                    "${rows.indexOf(r) + 1}", r.date.format(dayFmt), payee, cat, r.txn.account ?: r.txn.mode.label,
                    if (r.isCredit) "" else formatInr(r.txn.amountMinor, withSymbol = false),
                    if (r.isCredit) formatInr(r.txn.amountMinor, withSymbol = false) else "",
                    formatInr(r.runningMinor, withSymbol = false),
                )
            },
            size = 7.5f,
            notes = rows.map { it.note },
            dots = rows.map { catColor(it.txn.category) },
            dotColumn = 3,
            noteColumn = 2,
            cellColor = { r, c ->
                val row = rows[r]
                when (c) {
                    0 -> SOFT
                    5 -> if (row.txn.isInvestment) INVEST_BLUE else OUT_RED
                    6 -> IN_GREEN
                    7 -> if (row.runningMinor < 0) OUT_RED else null
                    else -> null
                }
            },
        )
        if (rows.isNotEmpty()) {
            w.totalRow(cols, listOf("", "", "Total", "", "", formatInr(report.spentMinor + report.investedMinor, false), formatInr(report.incomeMinor, false), formatInr(report.leftMinor, false)),
                colors = listOf(null, null, null, null, null, OUT_RED, IN_GREEN, if (report.leftMinor < 0) OUT_RED else BRAND))
            val noted = rows.count { it.note != null }
            w.small("Amounts in ₹. Out is red (blue when invested), in is green. Balance is money in minus money out so far this month." +
                (if (noted > 0) " Your notes are under the payee ($noted entries)." else ""))
        }

        if (report.notCounted.isNotEmpty()) {
            w.heading("Not counted in totals")
            w.small("Moves between your own accounts and credit card bill payments: the spends are already counted on the card.")
            w.table(
                listOf(Col("Date", 0.1f), Col("Payee", 0.4f), Col("Category", 0.2f), Col("Account", 0.15f), Col("Amount", 0.15f, right = true)),
                report.notCounted.map { r ->
                    listOf(r.date.format(dayFmt), r.payee, r.txn.category, r.txn.account ?: r.txn.mode.label,
                        (if (r.isCredit) "+" else "−") + formatInr(r.txn.amountMinor, false))
                },
                size = 7.5f,
                notes = report.notCounted.map { it.note },
            )
        }

        // ---- Household accounts ----
        w.newPage()
        w.text("Household accounts", 18f, bold = true, color = BRAND)
        w.text("${report.period.format(monthYear)} · ${report.rangeLabel}", 9.5f, color = SOFT)

        w.heading("Income and expenditure")
        val st = ArrayList<SLine>()
        st += SLine("Income", null, SKind.SECTION)
        report.incomeCategories.forEach { st += SLine(it.key, it.totalMinor, SKind.ITEM, IN_GREEN) }
        if (report.incomeCategories.isEmpty()) st += SLine("Nothing received", 0, SKind.ITEM)
        st += SLine("Total income", report.incomeMinor, SKind.TOTAL, IN_GREEN)
        st += SLine("Expenditure", null, SKind.SECTION)
        report.categories.forEach { st += SLine(it.key, it.totalMinor, SKind.ITEM, catColor(it.key)) }
        if (report.categories.isEmpty()) st += SLine("Nothing spent", 0, SKind.ITEM)
        st += SLine("Total expenditure", report.spentMinor, SKind.TOTAL, OUT_RED)
        val surplus = report.incomeMinor - report.spentMinor
        st += SLine(if (surplus >= 0) "Surplus before investing" else "Deficit before investing", surplus, SKind.TOTAL, if (surplus < 0) OUT_RED else BRAND)
        if (report.investments.isNotEmpty()) {
            st += SLine("Saved and invested", null, SKind.SECTION)
            report.investments.forEach { st += SLine(it.key, it.totalMinor, SKind.ITEM, INVEST_BLUE) }
            st += SLine("Total invested", report.investedMinor, SKind.TOTAL, INVEST_BLUE)
        }
        st += SLine(if (report.leftMinor >= 0) "Net surplus for the month" else "Net deficit for the month", report.leftMinor, SKind.GRAND,
            if (report.leftMinor < 0) OUT_RED else BRAND)
        w.statement(st)
        report.savingsRatePercent?.let { w.small("Savings rate: $it% of income not spent. Own-account transfers and card bill payments are left out: they move money, they don't spend it.") }

        if (report.accountLines.isNotEmpty()) {
            w.heading("Bank accounts and cards")
            val accCols = listOf(
                Col("Account / card", 0.28f), Col("In", 0.13f, right = true), Col("Out", 0.13f, right = true),
                Col("Moved in", 0.12f, right = true), Col("Moved out", 0.12f, right = true), Col("Net", 0.14f, right = true), Col("Entries", 0.08f, right = true),
            )
            val lines = report.accountLines
            w.table(
                accCols,
                lines.map { a ->
                    listOf(
                        (if (a.isCard) "Card " else "") + a.account,
                        formatInr(a.inMinor, false), formatInr(a.outMinor, false),
                        formatInr(a.movedInMinor, false), formatInr(a.movedOutMinor, false), formatInr(a.netMinor, false), "${a.count}",
                    )
                },
                dots = lines.map { if (it.isCard) PURPLE else BRAND },
                dotColumn = 0,
                cellColor = { r, c ->
                    when (c) {
                        1 -> IN_GREEN
                        2 -> OUT_RED
                        5 -> if (lines[r].netMinor < 0) OUT_RED else IN_GREEN
                        else -> null
                    }
                },
            )
            val cards = lines.filter { it.isCard }
            if (cards.isNotEmpty()) {
                val due = cards.sumOf { it.outMinor - it.inMinor }
                w.small("Card spends this month, less refunds: ${formatInr(due)}. That goes on the card bills. Moved in on a card is a bill payment received; moved out of a bank is the bill paid or a transfer to your own account.")
            } else {
                w.small("Moved in / out: transfers between your own accounts and card bill payments, which are not counted as income or spending.")
            }
        }

        if (report.balances.isNotEmpty()) {
            w.heading("Bank reconciliation")
            val bl = report.balances
            w.table(
                listOf(
                    Col("Account", 0.24f), Col("Opening", 0.14f, right = true), Col("Entries net", 0.14f, right = true),
                    Col("Expected", 0.14f, right = true), Col("Bank says", 0.14f, right = true), Col("Difference", 0.2f, right = true),
                ),
                bl.map { b ->
                    listOf(
                        b.account, formatInr(b.openingMinor, false), formatInr(b.entriesNetMinor, false),
                        formatInr(b.openingMinor + b.entriesNetMinor, false), formatInr(b.closingMinor, false),
                        if (b.differenceMinor == 0L) "Matches" else formatInr(b.differenceMinor, false),
                    )
                },
                cellColor = { r, c -> if (c == 5) (if (bl[r].differenceMinor == 0L) IN_GREEN else OUT_RED) else null },
            )
            w.small("Opening and closing come from the balance in bank messages and statements. A difference means an entry is missing or counted twice between those dates.")
        }

        if (report.personLines.size > 1) {
            w.heading("Family members")
            val pl = report.personLines
            w.table(
                listOf(Col("Person", 0.32f), Col("Received", 0.16f, right = true), Col("Spent", 0.16f, right = true), Col("Invested", 0.16f, right = true), Col("Entries", 0.2f, right = true)),
                pl.map { listOf(it.person, formatInr(it.receivedMinor, false), formatInr(it.spentMinor, false), formatInr(it.investedMinor, false), "${it.count}") },
                cellColor = { _, c -> when (c) { 1 -> IN_GREEN; 2 -> OUT_RED; 3 -> INVEST_BLUE; else -> null } },
            )
        }

        if (report.emis.isNotEmpty()) {
            w.heading("Loans and EMIs paid")
            w.shares(report.emis, OUT_RED)
            w.small("Total EMIs: ${formatInr(report.emis.sumOf { it.totalMinor })}" +
                (if (report.incomeMinor > 0) ", ${report.emis.sumOf { it.totalMinor } * 100 / report.incomeMinor}% of income." else "."))
        }

        w.signOff()

        // ---- Insights and charts, after the entries ----
        w.newPage()
        w.text("Insights", 18f, bold = true, color = BRAND)
        if (report.insights.isNotEmpty()) {
            w.heading("What stands out")
            report.insights.forEach { w.bullet(it) }
        }
        if (report.categories.isNotEmpty()) {
            w.heading("Spending by category")
            w.hBars(report.categories.map { Bar(it.key, it.totalMinor, "${it.percent}% · ${it.count}×", catColor(it.key)) })
        }
        if (report.paidWith.isNotEmpty()) {
            w.heading("How it was paid")
            val paidColors = mapOf("Credit card" to PURPLE, "Bank account / UPI" to BRAND, "Cash" to AMBER)
            w.stackedBar(report.paidWith.mapIndexed { i, p -> Part(p.key, p.totalMinor, p.percent, paidColors[p.key] ?: PALETTE[(i + 3) % PALETTE.size]) })
            if (report.modes.size > 1) {
                w.gap(4f)
                w.hBars(report.modes.mapIndexed { i, m -> Bar(m.key, m.totalMinor, "${m.percent}% · ${m.count}×", PALETTE[(i + 1) % PALETTE.size]) }, max = 6)
            }
        }
        if (report.daily.any { it.second > 0 }) {
            w.heading("Spending day by day", keep = 160f)
            w.dayBars(report.daily)
        }
        if (report.trend.any { it.receivedMinor > 0 || it.spentMinor > 0 }) {
            w.heading("Last six months", keep = 170f)
            w.trend(report.trend)
        }
        if (report.budgets.isNotEmpty()) {
            w.heading("Budgets")
            w.budgets(report.budgets)
        }
        if (report.people.size > 1) {
            w.heading("By person")
            w.shares(report.people)
        }
        if (report.labels.isNotEmpty()) {
            w.heading("By label")
            w.shares(report.labels)
            w.small("A payment with two labels is counted under both.")
        }
        if (report.accounts.size > 1) {
            w.heading("By account and card")
            w.shares(report.accounts)
        }
        if (report.merchants.isNotEmpty()) {
            w.heading("Top payees")
            w.table(
                listOf(Col("Payee", 0.6f), Col("Payments", 0.15f, right = true), Col("Amount", 0.25f, right = true)),
                report.merchants.map { listOf(it.merchant, "${it.count}", formatInr(it.totalMinor)) },
            )
        }
        if (report.repeating.isNotEmpty()) {
            w.heading("Repeating payments and EMIs")
            w.table(
                listOf(Col("Payee", 0.38f), Col("Category", 0.22f), Col("Every", 0.12f), Col("Next due", 0.12f), Col("Paid", 0.16f, right = true)),
                report.repeating.map { listOf(it.name, it.category, it.cadence, it.nextDate?.format(dayShort) ?: "–", formatInr(it.paidMinor)) },
            )
        }
        if (report.incomeSources.isNotEmpty()) {
            w.heading("Money received")
            w.shares(report.incomeSources, IN_GREEN)
        }
        if (report.investments.isNotEmpty()) {
            w.heading("Invested")
            w.shares(report.investments, INVEST_BLUE)
        }
        if (report.biggest.isNotEmpty()) {
            w.heading("Biggest spends")
            w.table(
                listOf(Col("Date", 0.14f), Col("Payee", 0.46f), Col("Category", 0.22f), Col("Amount", 0.18f, right = true)),
                report.biggest.map { listOf(it.date.format(dayFmt), it.payee, it.txn.category, formatInr(it.txn.amountMinor)) },
                dots = report.biggest.map { catColor(it.txn.category) },
                dotColumn = 2,
                cellColor = { _, c -> if (c == 3) OUT_RED else null },
            )
        }

        w.finish()
        doc.writeTo(out)
        doc.close()
    }

    private data class Col(val title: String, val weight: Float, val right: Boolean = false)
    private data class Bar(val label: String, val amountMinor: Long, val note: String, val color: Int = BRAND)
    private data class Part(val label: String, val amountMinor: Long, val percent: Int, val color: Int)
    private enum class SKind { SECTION, ITEM, TOTAL, GRAND }
    private data class SLine(val label: String, val amountMinor: Long?, val kind: SKind, val color: Int? = null)
    private data class Tile(val label: String, val value: String, val background: Int, val ink: Int)

    private class Writer(
        private val doc: PdfDocument,
        private val headerLeft: String,
        private val headerRight: String,
        private val footerLeft: String,
    ) {
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        private var y = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f }
        /** Drawn again at the top of a new page while a table runs on. */
        private var repeatHeader: (() -> Unit)? = null

        private val canvas: Canvas
            get() = (page ?: startPage()).canvas

        private fun startPage(): PdfDocument.Page {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNo).create())
            page = p
            val c = p.canvas
            fill.color = BRAND
            c.drawRect(0f, 0f, PAGE_W, 5f, fill)
            style(8f, true, BRAND)
            c.drawText(headerLeft, M, M - 12, paint)
            style(8f, false, SOFT)
            c.drawText(headerRight, PAGE_W - M - paint.measureText(headerRight), M - 12, paint)
            line(c, M - 6)
            line(c, PAGE_H - M + 6)
            c.drawText(footerLeft, M, PAGE_H - M + 18, paint)
            val pn = "Page $pageNo"
            c.drawText(pn, PAGE_W - M - paint.measureText(pn), PAGE_H - M + 18, paint)
            y = M + 6
            return p
        }

        fun newPage() { startPage(); repeatHeader?.invoke() }

        private fun line(c: Canvas, at: Float, from: Float = M, to: Float = PAGE_W - M, color: Int = RULE) {
            stroke.color = color
            c.drawLine(from, at, to, at, stroke)
        }

        private fun ensure(height: Float) {
            if (page == null) startPage()
            if (y + height > PAGE_H - M) newPage()
        }

        private fun style(size: Float, bold: Boolean, color: Int) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = color
        }

        fun text(s: String, size: Float, bold: Boolean = false, color: Int = INK) {
            ensure(size + 5)
            style(size, bold, color)
            y += size
            canvas.drawText(ellipsize(s, CONTENT_W), M, y, paint)
            y += 5
        }

        fun gap(h: Float) { y += h }

        /** Grey small print, wrapped. */
        fun small(s: String) = wrapped(s, 7.5f, SOFT, M)

        fun bullet(s: String) {
            ensure(14f)
            fill.color = BRAND
            canvas.drawCircle(M + 3, y + 6.5f, 1.6f, fill)
            wrapped(s, 9.5f, INK, M + 12)
        }

        private fun wrapped(s: String, size: Float, color: Int, x: Float) {
            style(size, false, color)
            val lines = wrap(s, PAGE_W - M - x)
            lines.forEach { l ->
                ensure(size + 4)
                style(size, false, color)
                y += size
                canvas.drawText(l, x, y, paint)
                y += 3
            }
            y += 2
        }

        private fun wrap(s: String, width: Float): List<String> {
            val out = ArrayList<String>()
            var cur = ""
            for (word in s.split(' ')) {
                val next = if (cur.isEmpty()) word else "$cur $word"
                if (paint.measureText(next) <= width || cur.isEmpty()) cur = next else { out += cur; cur = word }
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        /** [keep] is how much room what follows needs, so a heading is never left alone at the foot of a page. */
        fun heading(s: String, keep: Float = 90f) {
            repeatHeader = null
            ensure(keep)
            y += 14
            style(12.5f, true, BRAND)
            y += 12.5f
            fill.color = BRAND
            canvas.drawRect(M, y - 11f, M + 4f, y + 1f, fill)
            canvas.drawText(s, M + 9, y, paint)
            y += 4
            stroke.strokeWidth = 1.2f
            line(canvas, y, color = BRAND)
            stroke.strokeWidth = 0.6f
            y += 7
        }

        /** Summary tiles, four to a row. */
        fun tiles(items: List<Tile>) {
            val perRow = 4
            val gapX = 8f
            val tw = (CONTENT_W - gapX * (perRow - 1)) / perRow
            val th = 40f
            items.chunked(perRow).forEach { row ->
                ensure(th + 8)
                row.forEachIndexed { i, (label, value, background, ink) ->
                    val x = M + i * (tw + gapX)
                    fill.color = background
                    canvas.drawRoundRect(RectF(x, y, x + tw, y + th), 4f, 4f, fill)
                    fill.color = ink
                    canvas.drawRect(x, y + 4, x + 3, y + th - 4, fill)
                    style(7.5f, false, SOFT)
                    canvas.drawText(ellipsize(label, tw - 12), x + 8, y + 13, paint)
                    style(12f, true, ink)
                    canvas.drawText(ellipsize(value, tw - 14), x + 8, y + 31, paint)
                }
                y += th + 8
            }
        }

        /** Horizontal bars: label, bar, amount and note. */
        fun hBars(bars: List<Bar>, max: Int = 14) {
            val shown = if (bars.size > max) bars.take(max - 1) + Bar("Everything else", bars.drop(max - 1).sumOf { it.amountMinor }, "${bars.size - max + 1} more") else bars
            val top = shown.maxOf { it.amountMinor }.coerceAtLeast(1)
            val labelW = 130f
            val valueW = 150f
            val barW = CONTENT_W - labelW - valueW - 12
            shown.forEach { b ->
                ensure(15f)
                style(8.5f, false, INK)
                canvas.drawText(ellipsize(b.label, labelW - 6), M, y + 9, paint)
                val len = (barW * b.amountMinor / top).coerceAtLeast(1f)
                fill.color = b.color
                canvas.drawRect(M + labelW, y + 2, M + labelW + len, y + 11, fill)
                style(8.5f, true, INK)
                val amt = formatInr(b.amountMinor)
                val ax = M + labelW + barW + 6
                canvas.drawText(amt, ax, y + 9, paint)
                style(7.5f, false, SOFT)
                canvas.drawText(ellipsize(b.note, valueW - paint.measureText(amt) - 14), ax + paint.measureText(amt) + 14, y + 9, paint)
                y += 14
            }
            y += 2
        }

        /** One bar split into parts, with a legend. */
        fun stackedBar(parts: List<Part>) {
            ensure(46f)
            val total = parts.sumOf { it.amountMinor }.coerceAtLeast(1)
            var x = M
            parts.forEach { (_, amt, _, color) ->
                val w = CONTENT_W * amt / total
                fill.color = color
                canvas.drawRect(x, y, x + w, y + 16, fill)
                x += w
            }
            stroke.color = INK
            canvas.drawRect(M, y, M + CONTENT_W, y + 16, stroke)
            y += 26
            var lx = M
            parts.forEach { (label, amt, pct, color) ->
                val s = "$label  ${formatInr(amt)} ($pct%)"
                style(8.5f, false, INK)
                val w = paint.measureText(s) + 22
                if (lx + w > PAGE_W - M) { lx = M; y += 13 }
                fill.color = color
                canvas.drawRect(lx, y - 7, lx + 8, y + 1, fill)
                stroke.color = INK
                canvas.drawRect(lx, y - 7, lx + 8, y + 1, stroke)
                canvas.drawText(s, lx + 12, y, paint)
                lx += w
            }
            y += 10
        }

        /** A bar for every day of the month. */
        fun dayBars(days: List<Pair<java.time.LocalDate, Long>>) {
            val h = 90f
            ensure(h + 30)
            val top = days.maxOf { it.second }.coerceAtLeast(1)
            val axisX = M + 46
            val w = (PAGE_W - M - axisX) / days.size
            style(7f, false, SOFT)
            canvas.drawText(formatInr(top), M, y + 7, paint)
            canvas.drawText("₹0", M, y + h, paint)
            line(canvas, y + h, axisX, PAGE_W - M, SOFT)
            line(canvas, y, axisX, PAGE_W - M, ZEBRA)
            days.forEachIndexed { i, (d, amt) ->
                val bh = h * amt / top
                val x = axisX + i * w
                fill.color = if (d.dayOfWeek.value >= 6) BRAND_LIGHT else BRAND
                if (amt > 0) canvas.drawRect(x + 1, y + h - bh, x + w - 1, y + h, fill)
                if (i == 0 || d.dayOfMonth == 1 || d.dayOfMonth % 5 == 0) {
                    style(6.5f, false, SOFT)
                    canvas.drawText(d.format(dayShort), x, y + h + 9, paint)
                }
            }
            y += h + 14
            small("Darker bars are weekdays, lighter bars weekends.")
        }

        /** Received and spent, month by month. */
        fun trend(points: List<com.myfinance.core.SavingsPoint>) {
            val h = 80f
            ensure(h + 40)
            val top = points.maxOf { maxOf(it.receivedMinor, it.spentMinor) }.coerceAtLeast(1)
            val axisX = M + 46
            val slot = (PAGE_W - M - axisX) / points.size
            style(7f, false, SOFT)
            canvas.drawText(formatInr(top), M, y + 7, paint)
            line(canvas, y + h, axisX, PAGE_W - M, SOFT)
            points.forEachIndexed { i, p ->
                val x = axisX + i * slot + slot * 0.15f
                val bw = slot * 0.3f
                fill.color = IN_GREEN_LIGHT
                canvas.drawRect(x, y + h - h * p.receivedMinor / top, x + bw, y + h, fill)
                fill.color = OUT_RED
                canvas.drawRect(x + bw, y + h - h * p.spentMinor / top, x + 2 * bw, y + h, fill)
                style(7f, i == points.lastIndex, INK)
                canvas.drawText(p.month.format(shortMonth), x, y + h + 10, paint)
                style(6.5f, false, SOFT)
                canvas.drawText(formatInr(p.spentMinor), x, y + h + 19, paint)
            }
            y += h + 30
            legend(listOf("Received" to IN_GREEN_LIGHT, "Spent (figure below the month)" to OUT_RED))
        }

        private fun legend(items: List<Pair<String, Int>>) {
            ensure(14f)
            var lx = M
            y += 8
            items.forEach { (label, color) ->
                fill.color = color
                canvas.drawRect(lx, y - 7, lx + 8, y + 1, fill)
                style(8f, false, INK)
                canvas.drawText(label, lx + 12, y, paint)
                lx += paint.measureText(label) + 28
            }
            y += 8
        }

        fun budgets(lines: List<MonthReport.BudgetLine>) {
            val labelW = 130f
            val valueW = 210f
            val barW = CONTENT_W - labelW - valueW - 12
            lines.forEach { b ->
                ensure(16f)
                style(8.5f, false, INK)
                canvas.drawText(ellipsize(b.category, labelW - 6), M, y + 9, paint)
                val bx = M + labelW
                stroke.color = SOFT
                canvas.drawRect(bx, y + 2, bx + barW, y + 11, stroke)
                val filled = barW * (b.percent.coerceAtMost(100)) / 100f
                val color = when {
                    b.percent > 100 -> OUT_RED
                    b.percent >= 80 -> AMBER
                    else -> IN_GREEN
                }
                fill.color = color
                canvas.drawRect(bx, y + 2, bx + filled, y + 11, fill)
                style(8.5f, b.percent > 100, if (b.percent > 100) OUT_RED else INK)
                val s = "${formatInr(b.spentMinor)} of ${formatInr(b.budgetMinor)} · ${b.percent}%" + if (b.percent > 100) " · over" else ""
                canvas.drawText(ellipsize(s, valueW), bx + barW + 6, y + 9, paint)
                y += 15
            }
            y += 2
        }

        /** An income and expenditure statement: sections, indented items, totals and a grand total. */
        fun statement(lines: List<SLine>) {
            val amountX = PAGE_W - M
            lines.forEach { l ->
                when (l.kind) {
                    SKind.SECTION -> {
                        ensure(40f)
                        y += 6
                        style(9.5f, true, BRAND)
                        y += 9.5f
                        canvas.drawText(l.label.uppercase(), M, y, paint)
                        y += 5
                    }
                    SKind.ITEM -> {
                        ensure(13f)
                        y += 9f
                        l.color?.let { fill.color = it; canvas.drawCircle(M + 12, y - 3.2f, 2.6f, fill) }
                        style(9f, false, INK)
                        canvas.drawText(ellipsize(l.label, CONTENT_W - 140), M + 20, y, paint)
                        val a = formatInr(l.amountMinor ?: 0)
                        canvas.drawText(a, amountX - 90 - paint.measureText(a), y, paint)
                        y += 4
                    }
                    SKind.TOTAL, SKind.GRAND -> {
                        val grand = l.kind == SKind.GRAND
                        ensure(if (grand) 26f else 18f)
                        y += 2
                        stroke.strokeWidth = if (grand) 1.2f else 0.6f
                        line(canvas, y, amountX - 200, amountX, if (grand) BRAND else RULE)
                        stroke.strokeWidth = 0.6f
                        if (grand) {
                            fill.color = TINT_TEAL
                            canvas.drawRect(M, y + 1, amountX, y + 18, fill)
                        }
                        y += if (grand) 13f else 10.5f
                        style(if (grand) 10.5f else 9.5f, true, l.color ?: INK)
                        canvas.drawText(l.label, M + (if (grand) 6 else 0), y, paint)
                        val a = formatInr(l.amountMinor ?: 0)
                        canvas.drawText(a, amountX - (if (grand) 6 else 0) - paint.measureText(a), y, paint)
                        y += if (grand) 9f else 5f
                    }
                }
            }
            y += 4
        }

        fun shares(list: List<MonthReport.Share>, amountColor: Int? = null) = table(
            listOf(Col("", 0.55f), Col("Payments", 0.12f, right = true), Col("Share", 0.11f, right = true), Col("Amount", 0.22f, right = true)),
            list.map { listOf(it.key, "${it.count}", "${it.percent}%", formatInr(it.totalMinor)) },
            cellColor = { _, c -> if (c == 3) amountColor else null },
        )

        /**
         * A table that runs on over pages, its header repeated on each. [notes] adds a grey line under a row,
         * [dots] a coloured dot before the text in [dotColumn], and [cellColor] colours a cell's text.
         */
        fun table(
            cols: List<Col>,
            rows: List<List<String>>,
            size: Float = 8.5f,
            notes: List<String?> = emptyList(),
            dots: List<Int?> = emptyList(),
            dotColumn: Int = 2,
            noteColumn: Int = 1,
            cellColor: (row: Int, col: Int) -> Int? = { _, _ -> null },
        ) {
            val total = cols.sumOf { it.weight.toDouble() }.toFloat()
            val widths = cols.map { CONTENT_W * it.weight / total }
            val xs = widths.runningFold(M) { a, b -> a + b }
            val rowH = size + 6
            val header = {
                if (cols.any { it.title.isNotEmpty() }) {
                    fill.color = BRAND
                    canvas.drawRect(M, y + 1, PAGE_W - M, y + size + 7, fill)
                    y += size + 3
                    style(size - 0.5f, true, WHITE)
                    cols.forEachIndexed { i, c -> cell(c.title, xs[i], widths[i], c.right) }
                    y += 7
                }
            }
            ensure(rowH * 3)
            header()
            repeatHeader = header
            // Notes run under the payee and the next two columns, wrapped over as many lines as they need.
            val noteW = (noteColumn until minOf(noteColumn + 3, cols.size)).sumOf { widths[it].toDouble() }.toFloat() - 4
            rows.forEachIndexed { r, cells ->
                val note = notes.getOrNull(r)
                style(size - 0.5f, false, INK)
                val noteLines = note?.let { wrap("Note: $it", noteW) }.orEmpty()
                val h = rowH + noteLines.size * (size + 1)
                ensure(h)
                if (r % 2 == 1) {
                    fill.color = ZEBRA
                    canvas.drawRect(M, y - 1, PAGE_W - M, y - 1 + h, fill)
                }
                y += size + 1
                cells.forEachIndexed { i, s ->
                    style(size, cols[i].right, cellColor(r, i) ?: INK)
                    val dot = if (i == dotColumn) dots.getOrNull(r) else null
                    if (dot != null) {
                        fill.color = dot
                        canvas.drawCircle(xs[i] + 4.5f, y - size * 0.35f, 2.6f, fill)
                        cell(s, xs[i] + 8, widths[i] - 8, cols[i].right)
                    } else {
                        cell(s, xs[i], widths[i], cols[i].right)
                    }
                }
                noteLines.forEach { l ->
                    y += size + 1
                    style(size - 0.5f, false, NOTE)
                    canvas.drawText(l, xs[noteColumn] + 2, y, paint)
                }
                y += 5
            }
            repeatHeader = null
            y += 2
        }

        fun totalRow(cols: List<Col>, cells: List<String>, size: Float = 7.5f, colors: List<Int?> = emptyList()) {
            val total = cols.sumOf { it.weight.toDouble() }.toFloat()
            val widths = cols.map { CONTENT_W * it.weight / total }
            val xs = widths.runningFold(M) { a, b -> a + b }
            ensure(size + 10)
            stroke.strokeWidth = 1.2f
            line(canvas, y, color = BRAND)
            stroke.strokeWidth = 0.6f
            y += size + 3
            cells.forEachIndexed { i, s ->
                style(size, true, colors.getOrNull(i) ?: INK)
                cell(s, xs[i], widths[i], cols[i].right)
            }
            y += 6
        }

        private fun cell(s: String, x: Float, w: Float, right: Boolean) {
            val t = ellipsize(s, w - 4)
            val tx = if (right) x + w - 2 - paint.measureText(t) else x + 2
            canvas.drawText(t, tx, y, paint)
        }

        /** Space to sign and date the printout. */
        fun signOff() {
            ensure(80f)
            y += 34
            val third = (CONTENT_W - 20) / 3
            listOf("Prepared by", "Checked by", "Date").forEachIndexed { i, label ->
                val x = M + i * (third + 10)
                style(9f, true, BRAND)
                canvas.drawText(label, x, y, paint)
                line(canvas, y + 24, x, x + third, INK)
            }
            y += 34
        }

        private fun ellipsize(s: String, maxWidth: Float): String {
            if (paint.measureText(s) <= maxWidth) return s
            var end = s.length
            while (end > 1 && paint.measureText(s.substring(0, end) + "…") > maxWidth) end--
            return s.substring(0, end) + "…"
        }

        fun finish() {
            if (page == null) startPage()
            page?.let { doc.finishPage(it) }
            page = null
        }
    }
}
