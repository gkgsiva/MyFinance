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
 * The month as a printable report: A4 pages, black and white friendly, with a header and page number on
 * every page, so it can be printed and filed. Drawn with Android's built-in PDF writer; the numbers come
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
    private val ZEBRA = Color.rgb(0xF2, 0xF2, 0xF2)
    private val BAR = Color.rgb(0x4A, 0x4A, 0x4A)
    private val BAR_LIGHT = Color.rgb(0xB0, 0xB0, 0xB0)
    /** Greys that stay apart when printed in black and white. */
    private val SHADES = intArrayOf(
        Color.rgb(0x22, 0x22, 0x22), Color.rgb(0x77, 0x77, 0x77), Color.rgb(0xB8, 0xB8, 0xB8),
        Color.rgb(0x4D, 0x4D, 0x4D), Color.rgb(0x99, 0x99, 0x99), Color.rgb(0xDD, 0xDD, 0xDD),
    )

    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy")
    private val dayFmt = DateTimeFormatter.ofPattern("dd MMM")
    private val dayShort = DateTimeFormatter.ofPattern("d MMM")
    private val stamp = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
    private val shortMonth = DateTimeFormatter.ofPattern("MMM")

    fun write(out: OutputStream, report: MonthReport, preparedFor: String) {
        val doc = PdfDocument()
        val month = report.period.format(monthYear)
        val w = Writer(doc, "MyFinance · Monthly report · $month", preparedFor, "Made ${LocalDateTime.now().format(stamp)}")

        // ---- Title and summary ----
        w.text(month, 22f, bold = true)
        w.text(listOfNotNull(report.rangeLabel, report.cycleNote).joinToString(" · "), 9.5f, color = SOFT)
        w.text("Prepared for $preparedFor · ${report.rows.size} entries" +
            (if (report.hiddenCount > 0) " (${report.hiddenCount} hidden, still counted)" else ""), 9.5f, color = SOFT)
        w.gap(8f)
        w.tiles(
            listOf(
                "Received" to formatInr(report.incomeMinor),
                "Spent" to formatInr(report.spentMinor),
                "Invested" to formatInr(report.investedMinor),
                "Left over" to formatInr(report.leftMinor),
                "Saved (after spending)" to (report.savingsRatePercent?.let { "$it%" } ?: "–"),
                "On credit cards" to formatInr(report.cardSpentMinor),
                "Biggest category" to (report.categories.firstOrNull()?.key ?: "–"),
                "Not counted" to "${report.notCounted.size} entries",
            ),
        )
        w.small("Invested money (SIP, mutual funds, FD …) is not spending. Own-account transfers and card bill payments are listed at the end and change no total.")

        if (report.balances.isNotEmpty()) {
            w.heading("Bank balances")
            w.table(
                listOf(Col("Account", 0.5f), Col("Start of month", 0.25f, right = true), Col("End of month", 0.25f, right = true)),
                report.balances.map { listOf(it.account, formatInr(it.openingMinor), formatInr(it.closingMinor)) },
            )
            w.small("From the balance shown in bank messages and statements.")
        }

        if (report.insights.isNotEmpty()) {
            w.heading("What stands out")
            report.insights.forEach { w.bullet(it) }
        }

        // ---- Charts ----
        if (report.categories.isNotEmpty()) {
            w.heading("Spending by category")
            w.hBars(report.categories.map { Bar(it.key, it.totalMinor, "${it.percent}% · ${it.count}×") })
        }
        if (report.paidWith.isNotEmpty()) {
            w.heading("How it was paid")
            w.stackedBar(report.paidWith.map { Triple(it.key, it.totalMinor, it.percent) })
            if (report.modes.size > 1) {
                w.gap(4f)
                w.hBars(report.modes.map { Bar(it.key, it.totalMinor, "${it.percent}% · ${it.count}×") }, max = 6)
            }
        }
        if (report.daily.any { it.second > 0 }) {
            w.heading("Spending day by day")
            w.dayBars(report.daily)
        }
        if (report.trend.any { it.receivedMinor > 0 || it.spentMinor > 0 }) {
            w.heading("Last six months")
            w.trend(report.trend)
        }
        if (report.budgets.isNotEmpty()) {
            w.heading("Budgets")
            w.budgets(report.budgets)
        }

        // ---- Breakdowns ----
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
            w.shares(report.incomeSources)
        }
        if (report.investments.isNotEmpty()) {
            w.heading("Invested")
            w.shares(report.investments)
        }
        if (report.biggest.isNotEmpty()) {
            w.heading("Biggest spends")
            w.table(
                listOf(Col("Date", 0.14f), Col("Payee", 0.46f), Col("Category", 0.22f), Col("Amount", 0.18f, right = true)),
                report.biggest.map { listOf(it.date.format(dayFmt), it.payee, it.txn.category, formatInr(it.txn.amountMinor)) },
            )
        }

        // ---- Every entry ----
        w.newPage()
        w.heading("All entries")
        if (report.rows.isEmpty()) w.small("No entries this month.")
        val cols = listOf(
            Col("Date", 0.08f), Col("Payee / note", 0.25f), Col("Category · labels", 0.19f), Col("Account", 0.12f),
            Col("Out", 0.12f, right = true), Col("In", 0.12f, right = true), Col("Balance", 0.12f, right = true),
        )
        w.table(cols, report.rows.map { r ->
            // Whose it is, for family members' entries merged in.
            val payee = r.payee + (r.txn.owner?.takeIf { report.people.size > 1 && it != preparedFor }?.let { " · $it" } ?: "") +
                (if (r.hidden) " (hidden)" else "")
            val cat = (if (r.txn.isInvestment && r.txn.category != DefaultCategories.INVESTMENTS) "Invested: " else "") + r.txn.category +
                (if (r.labels.isNotEmpty()) " · " + r.labels.joinToString(", ") else "")
            listOf(
                r.date.format(dayFmt), payee, cat, r.txn.account ?: r.txn.mode.label,
                if (r.isCredit) "" else formatInr(r.txn.amountMinor, withSymbol = false),
                if (r.isCredit) formatInr(r.txn.amountMinor, withSymbol = false) else "",
                formatInr(r.runningMinor, withSymbol = false),
            )
        }, size = 7.5f, notes = report.rows.map { it.note })
        if (report.rows.isNotEmpty()) {
            w.totalRow(cols, listOf("", "Total", "", "", formatInr(report.spentMinor + report.investedMinor, false), formatInr(report.incomeMinor, false), formatInr(report.leftMinor, false)))
            w.small("Amounts in ₹. Balance is money in minus money out so far this month.")
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
            )
        }

        w.signOff()
        w.finish()
        doc.writeTo(out)
        doc.close()
    }

    private data class Col(val title: String, val weight: Float, val right: Boolean = false)
    private data class Bar(val label: String, val amountMinor: Long, val note: String)

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
            style(8f, false, SOFT)
            c.drawText(headerLeft, M, M - 12, paint)
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
            fill.color = INK
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

        fun heading(s: String) {
            repeatHeader = null
            ensure(60f)
            y += 14
            style(12.5f, true, INK)
            y += 12.5f
            canvas.drawText(s, M, y, paint)
            y += 4
            stroke.strokeWidth = 1.2f
            line(canvas, y, color = INK)
            stroke.strokeWidth = 0.6f
            y += 7
        }

        /** Summary tiles, four to a row. */
        fun tiles(items: List<Pair<String, String>>) {
            val perRow = 4
            val gapX = 8f
            val tw = (CONTENT_W - gapX * (perRow - 1)) / perRow
            val th = 40f
            items.chunked(perRow).forEach { row ->
                ensure(th + 8)
                row.forEachIndexed { i, (label, value) ->
                    val x = M + i * (tw + gapX)
                    stroke.color = RULE
                    canvas.drawRoundRect(RectF(x, y, x + tw, y + th), 4f, 4f, stroke)
                    style(7.5f, false, SOFT)
                    canvas.drawText(ellipsize(label, tw - 12), x + 6, y + 13, paint)
                    style(12f, true, INK)
                    canvas.drawText(ellipsize(value, tw - 12), x + 6, y + 31, paint)
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
                fill.color = BAR
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
        fun stackedBar(parts: List<Triple<String, Long, Int>>) {
            ensure(46f)
            val total = parts.sumOf { it.second }.coerceAtLeast(1)
            var x = M
            parts.forEachIndexed { i, (_, amt, _) ->
                val w = CONTENT_W * amt / total
                fill.color = SHADES[i % SHADES.size]
                canvas.drawRect(x, y, x + w, y + 16, fill)
                x += w
            }
            stroke.color = INK
            canvas.drawRect(M, y, M + CONTENT_W, y + 16, stroke)
            y += 26
            var lx = M
            parts.forEachIndexed { i, (label, amt, pct) ->
                val s = "$label  ${formatInr(amt)} ($pct%)"
                style(8.5f, false, INK)
                val w = paint.measureText(s) + 22
                if (lx + w > PAGE_W - M) { lx = M; y += 13 }
                fill.color = SHADES[i % SHADES.size]
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
                fill.color = if (d.dayOfWeek.value >= 6) BAR_LIGHT else BAR
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
                fill.color = BAR_LIGHT
                canvas.drawRect(x, y + h - h * p.receivedMinor / top, x + bw, y + h, fill)
                fill.color = BAR
                canvas.drawRect(x + bw, y + h - h * p.spentMinor / top, x + 2 * bw, y + h, fill)
                style(7f, i == points.lastIndex, INK)
                canvas.drawText(p.month.format(shortMonth), x, y + h + 10, paint)
                style(6.5f, false, SOFT)
                canvas.drawText(formatInr(p.spentMinor), x, y + h + 19, paint)
            }
            y += h + 30
            legend(listOf("Received" to BAR_LIGHT, "Spent (figure below the month)" to BAR))
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
                fill.color = if (b.percent > 100) INK else BAR_LIGHT
                canvas.drawRect(bx, y + 2, bx + filled, y + 11, fill)
                style(8.5f, b.percent > 100, INK)
                val s = "${formatInr(b.spentMinor)} of ${formatInr(b.budgetMinor)} · ${b.percent}%" + if (b.percent > 100) " · over" else ""
                canvas.drawText(ellipsize(s, valueW), bx + barW + 6, y + 9, paint)
                y += 15
            }
            y += 2
        }

        fun shares(list: List<MonthReport.Share>) = table(
            listOf(Col("", 0.55f), Col("Payments", 0.12f, right = true), Col("Share", 0.11f, right = true), Col("Amount", 0.22f, right = true)),
            list.map { listOf(it.key, "${it.count}", "${it.percent}%", formatInr(it.totalMinor)) },
        )

        /** A table that runs on over pages, its header repeated on each. [notes] adds a grey line under a row. */
        fun table(cols: List<Col>, rows: List<List<String>>, size: Float = 8.5f, notes: List<String?> = emptyList()) {
            val total = cols.sumOf { it.weight.toDouble() }.toFloat()
            val widths = cols.map { CONTENT_W * it.weight / total }
            val xs = widths.runningFold(M) { a, b -> a + b }
            val rowH = size + 6
            val header = {
                if (cols.any { it.title.isNotEmpty() }) {
                    y += size + 1
                    style(size - 0.5f, true, SOFT)
                    cols.forEachIndexed { i, c -> cell(c.title, xs[i], widths[i], c.right) }
                    y += 4
                    line(canvas, y, color = SOFT)
                    y += 3
                }
            }
            ensure(rowH * 3)
            header()
            repeatHeader = header
            rows.forEachIndexed { r, cells ->
                val note = notes.getOrNull(r)
                val h = rowH + (if (note != null) size + 1 else 0f)
                ensure(h)
                if (r % 2 == 1) {
                    fill.color = ZEBRA
                    canvas.drawRect(M, y - 1, PAGE_W - M, y - 1 + h, fill)
                }
                y += size + 1
                cells.forEachIndexed { i, s ->
                    style(size, cols[i].right, INK)
                    cell(s, xs[i], widths[i], cols[i].right)
                }
                if (note != null) {
                    y += size + 1
                    style(size - 1, false, SOFT)
                    cell("“$note”", xs[1], widths[1] + widths[2], false)
                }
                y += 5
            }
            repeatHeader = null
            y += 2
        }

        fun totalRow(cols: List<Col>, cells: List<String>, size: Float = 7.5f) {
            val total = cols.sumOf { it.weight.toDouble() }.toFloat()
            val widths = cols.map { CONTENT_W * it.weight / total }
            val xs = widths.runningFold(M) { a, b -> a + b }
            ensure(size + 10)
            line(canvas, y, color = INK)
            y += size + 3
            cells.forEachIndexed { i, s ->
                style(size, true, INK)
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
            ensure(70f)
            y += 30
            style(9f, false, INK)
            val half = CONTENT_W / 2
            canvas.drawText("Checked by", M, y, paint)
            canvas.drawText("Date", M + half + 10, y, paint)
            y += 22
            line(canvas, y, M, M + half - 10, INK)
            line(canvas, y, M + half + 10, PAGE_W - M, INK)
            y += 10
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
