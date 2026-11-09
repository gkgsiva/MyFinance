package com.myfinance.core

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** What kind of file a statement is, from its first bytes rather than its name. */
enum class StatementFileType { PDF, XLSX, XLS, HTML, TEXT }

/**
 * Reads the first sheet of a statement download into rows of cell text: CSV / tab-delimited text,
 * Excel 2007+ (.xlsx), Excel 97-2003 (.xls) and the HTML tables some banks save with an .xls name.
 * Uses no third-party library.
 */
object SpreadsheetReader {

    fun detect(bytes: ByteArray): StatementFileType {
        fun startsWith(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        return when {
            startsWith(0x25, 0x50, 0x44, 0x46) -> StatementFileType.PDF // %PDF
            startsWith(0x50, 0x4B, 0x03, 0x04) -> StatementFileType.XLSX // zip
            startsWith(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> StatementFileType.XLS // OLE2
            else -> {
                val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1).lowercase()
                if (head.contains("<table") || head.contains("<html") || head.contains("<!doctype html")) StatementFileType.HTML
                else StatementFileType.TEXT
            }
        }
    }

    fun read(bytes: ByteArray): List<List<String>> = when (detect(bytes)) {
        StatementFileType.XLSX -> readXlsx(bytes)
        StatementFileType.XLS -> readXls(bytes)
        StatementFileType.HTML -> readHtml(decodeText(bytes))
        StatementFileType.TEXT -> readDelimited(decodeText(bytes))
        StatementFileType.PDF -> error("PDF is not a spreadsheet")
    }

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val utf8 = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        // Not valid UTF-8 (older Windows exports): fall back to Latin-1 so nothing is lost.
        return if (utf8.contains('�')) String(bytes, Charsets.ISO_8859_1) else utf8
    }

    // ---------------------------------------------------------------- CSV / delimited

    fun readDelimited(text: String): List<List<String>> {
        val sample = text.lineSequence().take(60).toList()
        val delimiter = listOf(',', '\t', ';', '|').maxByOrNull { d -> sample.sumOf { line -> line.count { it == d } } } ?: ','
        return split(text, delimiter)
    }

    private fun split(text: String, delimiter: Char): List<List<String>> {
        val records = ArrayList<List<String>>()
        var field = StringBuilder()
        var record = ArrayList<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> if (field.isBlank()) { field = StringBuilder(); inQuotes = true } else field.append(ch)
                delimiter -> { record += field.toString().trim(); field = StringBuilder() }
                '\r' -> {}
                '\n' -> { record += field.toString().trim(); records += record; record = ArrayList(); field = StringBuilder() }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) { record += field.toString().trim(); records += record }
        return records
    }

    // ---------------------------------------------------------------- HTML tables

    fun readHtml(html: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val tr = Regex("""<tr\b[^>]*>(.*?)</tr\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val td = Regex("""<t[dh]\b([^>]*)>(.*?)</t[dh]\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (r in tr.findAll(html)) {
            val cells = ArrayList<String>()
            for (c in td.findAll(r.groupValues[1])) {
                val text = unescape(c.groupValues[2].replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ").replace(Regex("""<[^>]+>"""), ""))
                    .replace(Regex("""\s+"""), " ").trim()
                cells += text
                val span = Regex("""colspan\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE).find(c.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 1
                repeat((span - 1).coerceIn(0, 20)) { cells += "" }
            }
            if (cells.isNotEmpty()) rows += cells
        }
        return rows
    }

    private fun unescape(s: String): String =
        s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }

    // ---------------------------------------------------------------- .xlsx

    fun readXlsx(bytes: ByteArray): List<List<String>> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.removePrefix("/")
                if (name.endsWith(".xml") || name.endsWith(".rels")) parts[name] = zip.readBytes()
            }
        }
        val shared = parts["xl/sharedStrings.xml"]?.let { xml ->
            val doc = parse(xml)
            elements(doc.documentElement, "si").map { si -> elements(si, "t").joinToString("") { it.textContent } }
        }.orEmpty()

        val sheetPath = firstSheetPath(parts) ?: parts.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
            ?: error("No worksheet in this Excel file")
        val doc = parse(parts[sheetPath] ?: error("Worksheet missing"))
        val rows = ArrayList<List<String>>()
        for (row in elements(doc.documentElement, "row")) {
            val rIndex = row.getAttribute("r").toIntOrNull()?.minus(1) ?: rows.size
            while (rows.size < rIndex) rows += emptyList<String>()
            val cells = ArrayList<String>()
            for (c in elements(row, "c")) {
                val ref = c.getAttribute("r")
                val col = if (ref.isNotEmpty()) columnIndex(ref) else cells.size
                while (cells.size < col) cells += ""
                val type = c.getAttribute("t")
                val v = elements(c, "v").firstOrNull()?.textContent
                val text = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> elements(c, "t").joinToString("") { it.textContent }
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    else -> v?.let(::cleanNumber).orEmpty()
                }
                cells += text.trim()
            }
            rows += cells
        }
        return rows
    }

    private fun firstSheetPath(parts: Map<String, ByteArray>): String? {
        val wb = parts["xl/workbook.xml"]?.let(::parse) ?: return null
        val sheet = elements(wb.documentElement, "sheet").firstOrNull() ?: return null
        val rid = sheet.getAttribute("r:id").ifEmpty {
            (0 until sheet.attributes.length).map { sheet.attributes.item(it) }.firstOrNull { it.nodeName.endsWith(":id") }?.nodeValue.orEmpty()
        }
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let(::parse) ?: return null
        val target = elements(rels.documentElement, "Relationship").firstOrNull { it.getAttribute("Id") == rid }?.getAttribute("Target") ?: return null
        return if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
    }

    private fun parse(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml))

    /** Elements named [name] (ignoring any prefix) anywhere under [root], in document order. */
    private fun elements(root: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        val list = root.getElementsByTagName("*")
        for (i in 0 until list.length) {
            val e = list.item(i) as Element
            if (e.tagName == name || e.tagName.endsWith(":$name")) out += e
        }
        return out
    }

    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (!ch.isLetter()) break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    /** "450" for 450.0, keeping real decimals; avoids "4.5E2" from doubles. */
    private fun cleanNumber(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        return java.math.BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    // ---------------------------------------------------------------- .xls (BIFF8 inside an OLE2 compound file)

    fun readXls(bytes: ByteArray): List<List<String>> {
        val stream = Cfb(bytes).stream("Workbook") ?: Cfb(bytes).stream("Book")
            ?: error("No workbook in this .xls file")
        return Biff8(stream).firstSheet()
    }

    /** Minimal reader for the OLE2 compound file that holds an .xls workbook. */
    private class Cfb(private val data: ByteArray) {
        private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        private val sectorSize = 1 shl buf.getShort(0x1E).toInt()
        private val miniSectorSize = 1 shl buf.getShort(0x20).toInt()
        private val miniCutoff = buf.getInt(0x38)
        private val fat: IntArray
        private val miniFat: IntArray
        private val entries: List<Entry>

        private data class Entry(val name: String, val type: Int, val start: Int, val size: Long)

        init {
            val numFat = buf.getInt(0x2C)
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) { val s = buf.getInt(0x4C + i * 4); if (s >= 0) fatSectors += s }
            var difat = buf.getInt(0x44)
            var guard = 0
            while (difat >= 0 && fatSectors.size < numFat && guard++ < 10_000) {
                val off = offset(difat)
                val per = sectorSize / 4 - 1
                for (i in 0 until per) { val s = buf.getInt(off + i * 4); if (s >= 0) fatSectors += s }
                difat = buf.getInt(off + per * 4)
            }
            val perSector = sectorSize / 4
            fat = IntArray(fatSectors.size * perSector)
            fatSectors.forEachIndexed { i, s ->
                val off = offset(s)
                for (j in 0 until perSector) if (off + j * 4 + 4 <= data.size) fat[i * perSector + j] = buf.getInt(off + j * 4)
            }
            val dir = chain(buf.getInt(0x30))
            entries = (0 until dir.size / 128).map { i ->
                val b = ByteBuffer.wrap(dir, i * 128, 128).order(ByteOrder.LITTLE_ENDIAN)
                val nameLen = (b.getShort(i * 128 + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
                val name = String(dir, i * 128, maxOf(0, nameLen - 2), Charsets.UTF_16LE)
                Entry(name, dir[i * 128 + 0x42].toInt(), b.getInt(i * 128 + 0x74), b.getInt(i * 128 + 0x78).toLong() and 0xFFFFFFFFL)
            }
            val miniFatBytes = chain(buf.getInt(0x3C))
            val mb = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
            miniFat = IntArray(miniFatBytes.size / 4) { mb.getInt(it * 4) }
        }

        private fun offset(sector: Int) = (sector + 1) * sectorSize

        private fun chain(start: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && s < fat.size.coerceAtLeast(1) && guard++ < 1_000_000) {
                val off = offset(s)
                if (off >= data.size) break
                out.write(data, off, minOf(sectorSize, data.size - off))
                s = if (fat.isEmpty()) -2 else fat[s]
            }
            return out.toByteArray()
        }

        fun stream(name: String): ByteArray? {
            val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
            val size = e.size.toInt()
            if (e.size < miniCutoff) {
                val root = chain(entries.first { it.type == 5 }.start)
                val out = java.io.ByteArrayOutputStream()
                var s = e.start
                var guard = 0
                while (s >= 0 && s < miniFat.size && guard++ < 1_000_000 && out.size() < size) {
                    val off = s * miniSectorSize
                    if (off >= root.size) break
                    out.write(root, off, minOf(miniSectorSize, root.size - off))
                    s = miniFat[s]
                }
                return out.toByteArray().copyOf(size)
            }
            return chain(e.start).copyOf(size)
        }
    }

    /** Reads the cells of the first worksheet from a BIFF8 workbook stream. */
    private class Biff8(private val data: ByteArray) {
        private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        private fun u16(o: Int) = buf.getShort(o).toInt() and 0xFFFF

        fun firstSheet(): List<List<String>> {
            val sst = ArrayList<String>()
            val cells = HashMap<Long, String>()
            var maxRow = -1
            var substream = 0
            var inFirstSheet = false
            var sheetDone = false
            var pos = 0
            var pendingFormula: Long? = null
            fun put(r: Int, c: Int, v: String) {
                if (!inFirstSheet || sheetDone) return
                cells[(r.toLong() shl 16) or c.toLong()] = v
                if (r > maxRow) maxRow = r
            }
            while (pos + 4 <= data.size) {
                val type = u16(pos)
                val len = u16(pos + 2)
                val start = pos + 4
                if (start + len > data.size) break
                when (type) {
                    0x0809 -> { // BOF
                        substream++
                        val dt = if (len >= 4) u16(start + 2) else 0
                        if (substream > 1 && dt == 0x0010 && !sheetDone && !inFirstSheet) inFirstSheet = true
                    }
                    0x000A -> if (inFirstSheet) { sheetDone = true; inFirstSheet = false } // EOF
                    0x00FC -> { // SST, possibly followed by CONTINUE records
                        val pieces = arrayListOf(start until start + len)
                        var next = start + len
                        while (next + 4 <= data.size && u16(next) == 0x003C) {
                            val l = u16(next + 2)
                            pieces += (next + 4) until (next + 4 + l)
                            next += 4 + l
                        }
                        readSst(pieces, sst)
                    }
                    0x00FD -> put(u16(start), u16(start + 2), sst.getOrElse(buf.getInt(start + 6)) { "" }) // LABELSST
                    0x0204 -> put(u16(start), u16(start + 2), readShortString(start + 6, u16(start + 6), true)) // LABEL
                    0x0203 -> put(u16(start), u16(start + 2), number(buf.getDouble(start + 6))) // NUMBER
                    0x027E -> put(u16(start), u16(start + 2), number(rk(buf.getInt(start + 6)))) // RK
                    0x00BD -> { // MULRK
                        val r = u16(start)
                        val first = u16(start + 2)
                        val count = (len - 6) / 6
                        for (i in 0 until count) put(r, first + i, number(rk(buf.getInt(start + 4 + i * 6 + 2))))
                    }
                    0x0006 -> { // FORMULA: cached result
                        val r = u16(start)
                        val c = u16(start + 2)
                        if (u16(start + 12) == 0xFFFF) {
                            if (data[start + 6].toInt() == 0) pendingFormula = (r.toLong() shl 16) or c.toLong()
                        } else put(r, c, number(buf.getDouble(start + 6)))
                    }
                    0x0207 -> pendingFormula?.let { key -> // STRING after a text formula
                        put((key shr 16).toInt(), (key and 0xFFFF).toInt(), readShortString(start, u16(start), true))
                        pendingFormula = null
                    }
                }
                pos = start + len
            }
            val out = ArrayList<List<String>>()
            for (r in 0..maxRow) {
                val cols = cells.keys.filter { (it shr 16).toInt() == r }.map { (it and 0xFFFF).toInt() }
                val width = (cols.maxOrNull() ?: -1) + 1
                out += (0 until width).map { c -> cells[(r.toLong() shl 16) or c.toLong()].orEmpty() }
            }
            return out
        }

        /** XLUnicodeString with a 16-bit length at [lenOffset]-2 already read as [cch]. */
        private fun readShortString(lenOffset: Int, cch: Int, sixteenBitLen: Boolean): String {
            val flagsAt = lenOffset + if (sixteenBitLen) 2 else 1
            val wide = data[flagsAt].toInt() and 1 == 1
            val from = flagsAt + 1
            return if (wide) String(data, from, cch * 2, Charsets.UTF_16LE) else String(data, from, cch, Charsets.ISO_8859_1)
        }

        private fun readSst(pieces: List<IntRange>, out: MutableList<String>) {
            var p = 0
            var off = pieces[0].first
            fun remaining() = pieces[p].last + 1 - off
            fun advancePiece(): Boolean { if (p + 1 >= pieces.size) return false; p++; off = pieces[p].first; return true }
            fun byte(): Int { if (remaining() <= 0 && !advancePiece()) throw IndexOutOfBoundsException(); return data[off++].toInt() and 0xFF }
            fun u16r() = byte() or (byte() shl 8)
            fun u32r() = u16r() or (u16r() shl 16)
            fun skip(n: Int) { var k = n; while (k > 0) { if (remaining() <= 0 && !advancePiece()) return; val t = minOf(k, remaining()); off += t; k -= t } }
            try {
                byte(); byte(); byte(); byte() // total count
                val unique = u32r()
                repeat(unique) {
                    val cch = u16r()
                    var flags = byte()
                    val runs = if (flags and 0x08 != 0) u16r() else 0
                    val ext = if (flags and 0x04 != 0) u32r() else 0
                    val sb = StringBuilder()
                    var left = cch
                    while (left > 0) {
                        if (remaining() <= 0) {
                            // Characters continue in the next record, which starts with a fresh flags byte.
                            if (!advancePiece()) break
                            flags = byte()
                        }
                        val wide = flags and 1 == 1
                        val canTake = if (wide) remaining() / 2 else remaining()
                        val take = minOf(left, canTake)
                        if (take <= 0) { off = pieces[p].last + 1; continue }
                        sb.append(if (wide) String(data, off, take * 2, Charsets.UTF_16LE) else String(data, off, take, Charsets.ISO_8859_1))
                        off += if (wide) take * 2 else take
                        left -= take
                    }
                    skip(runs * 4 + ext)
                    out += sb.toString()
                }
            } catch (_: IndexOutOfBoundsException) {
                // A truncated table still gives the strings read so far.
            }
        }

        private fun rk(v: Int): Double {
            val d = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
            return if (v and 1 != 0) d / 100 else d
        }

        private fun number(d: Double): String =
            java.math.BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
