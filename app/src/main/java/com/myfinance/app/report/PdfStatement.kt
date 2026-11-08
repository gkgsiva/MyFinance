package com.myfinance.app.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.myfinance.core.FinanceSummary
import com.myfinance.core.Reports
import com.myfinance.core.TxnLite
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import java.io.OutputStream
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A monthly family statement as a PDF, drawn with Android's built-in PDF writer. */
object PdfStatement {

    private const val PAGE_W = 595 // A4 at 72 dpi
    private const val PAGE_H = 842
    private const val MARGIN = 40f

    fun write(out: OutputStream, month: YearMonth, txns: List<TxnLite>, generatedBy: String) {
        val doc = PdfDocument()
        val w = Writer(doc)
        val summary = FinanceSummary.of(txns)
        val monthName = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

        w.text("MyFinance statement", 20f, bold = true)
        w.text("$monthName · prepared by $generatedBy", 11f, color = Color.DKGRAY)
        w.gap(10f)

        w.heading("Summary")
        w.row("Income", formatInr(summary.incomeMinor))
        w.row("Spent", formatInr(summary.expenseMinor))
        w.row("Net", formatInr(summary.netMinor))
        summary.savingsRatePercent?.let { w.row("Savings rate", "$it%") }

        if (summary.byPerson.size > 1 || summary.byPerson.firstOrNull()?.key != "Unknown") {
            w.heading("By person")
            summary.byPerson.forEach { w.row(it.key, formatInr(it.totalMinor), "${it.count} payments") }
        }

        w.heading("By category")
        summary.byCategory.forEach { w.row(it.key, formatInr(it.totalMinor), "${it.count} payments") }

        val (top, _) = Reports.topMerchants(txns, 10)
        if (top.isNotEmpty()) {
            w.heading("Top merchants")
            top.forEach { w.row(it.merchant, formatInr(it.totalMinor), "${it.count}×") }
        }

        val modes = Reports.paymentModes(txns)
        if (modes.isNotEmpty()) {
            w.heading("Payment modes")
            modes.forEach { w.row(it.mode.label, formatInr(it.totalMinor), "${it.count} payments") }
        }

        w.heading("Transactions")
        val dayFmt = DateTimeFormatter.ofPattern("d MMM")
        val zone = ZoneId.systemDefault()
        txns.sortedBy { it.timestamp }.forEach { t ->
            val date = Instant.ofEpochMilli(t.timestamp).atZone(zone).format(dayFmt)
            val who = listOfNotNull(t.merchant ?: if (t.type == TxnType.CREDIT) "Received" else "Payment", t.owner).joinToString(" · ")
            val amount = (if (t.type == TxnType.CREDIT) "+" else "−") + formatInr(t.amountMinor)
            w.row("$date  $who", amount, t.category, size = 9f)
        }

        w.finish()
        doc.writeTo(out)
        doc.close()
    }

    private class Writer(private val doc: PdfDocument) {
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        private var y = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val canvas: Canvas
            get() = (page ?: newPage()).canvas

        private fun newPage(): PdfDocument.Page {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = p
            y = MARGIN
            return p
        }

        private fun ensure(height: Float) {
            if (page == null || y + height > PAGE_H - MARGIN) newPage()
        }

        private fun style(size: Float, bold: Boolean, color: Int) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = color
        }

        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) {
            ensure(size + 6)
            style(size, bold, color)
            y += size
            canvas.drawText(s, MARGIN, y, paint)
            y += 6
        }

        fun gap(h: Float) { y += h }

        fun heading(s: String) {
            ensure(40f)
            y += 12
            text(s, 13f, bold = true, color = Color.rgb(0x1B, 0x6E, 0x5A))
            paint.color = Color.LTGRAY
            canvas.drawLine(MARGIN, y - 2, PAGE_W - MARGIN, y - 2, paint)
            y += 2
        }

        /** Label on the left, [middle] in grey, [value] right-aligned. */
        fun row(label: String, value: String, middle: String? = null, size: Float = 10.5f) {
            ensure(size + 6)
            y += size
            style(size, false, Color.BLACK)
            canvas.drawText(ellipsize(label, 300f), MARGIN, y, paint)
            middle?.let {
                style(size, false, Color.GRAY)
                canvas.drawText(ellipsize(it, 120f), MARGIN + 320f, y, paint)
            }
            style(size, true, Color.BLACK)
            val vw = paint.measureText(value)
            canvas.drawText(value, PAGE_W - MARGIN - vw, y, paint)
            y += 5
        }

        private fun ellipsize(s: String, maxWidth: Float): String {
            if (paint.measureText(s) <= maxWidth) return s
            var end = s.length
            while (end > 1 && paint.measureText(s.substring(0, end) + "…") > maxWidth) end--
            return s.substring(0, end) + "…"
        }

        fun finish() {
            if (page == null) newPage()
            page?.let { doc.finishPage(it) }
            page = null
        }
    }
}
