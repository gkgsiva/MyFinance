package com.myfinance.core

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a single-sheet Excel (.xlsx) file with no third-party library. Numbers are written as
 * numbers so Excel can sum them; everything else as text.
 */
object XlsxWriter {

    fun write(out: OutputStream, sheetName: String, header: List<String>, rows: List<List<Any?>>) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbook(sheetName))
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheet(header, rows))
        }
    }

    private fun sheet(header: List<String>, rows: List<List<Any?>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        (listOf(header) + rows).forEachIndexed { r, row ->
            append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, v ->
                val ref = column(c) + (r + 1)
                val bold = if (r == 0) """ s="1"""" else ""
                when (v) {
                    null -> {}
                    is Number -> append("""<c r="$ref"$bold><v>$v</v></c>""")
                    else -> append("""<c r="$ref" t="inlineStr"$bold><is><t xml:space="preserve">${escape(v.toString())}</t></is></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /** 0 -> A, 25 -> Z, 26 -> AA. */
    fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun escape(s: String): String = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            // XML 1.0 cannot carry most control characters.
            ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> append(' ')
            else -> append(ch)
        }
    }

    private fun workbook(name: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${escape(name.take(31))}" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

    private const val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val WORKBOOK_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>"""
}

/** Turns transactions into readable statement rows for Excel / CSV. */
object StatementExport {
    val header = listOf("Date", "Type", "Amount (₹)", "Merchant", "Category", "Account", "Person", "Mode", "Note")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun rows(txns: List<Pair<TxnLite, String?>>, zone: ZoneId = ZoneId.systemDefault()): List<List<Any?>> =
        txns.sortedBy { it.first.timestamp }.map { (t, note) ->
            listOf(
                Instant.ofEpochMilli(t.timestamp).atZone(zone).format(dateFormat),
                if (t.type == TxnType.DEBIT) "Expense" else "Income",
                java.math.BigDecimal.valueOf(t.amountMinor, 2),
                t.merchant, t.category, t.account, t.owner, t.mode.label, note,
            )
        }
}
