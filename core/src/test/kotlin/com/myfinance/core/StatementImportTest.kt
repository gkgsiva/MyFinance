package com.myfinance.core

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatementImportTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/statements/$name")!!.readBytes()

    // ---------------------------------------------------------------- spreadsheets

    @Test
    fun hdfcXlsWithSharedStringsAcrossContinueRecords() {
        val bytes = resource("hdfc-bank.xls")
        assertEquals(StatementFileType.XLS, SpreadsheetReader.detect(bytes))
        val st = assertNotNull(StatementParser.fromTable(SpreadsheetReader.read(bytes)))
        assertEquals(300, st.rows.size)
        assertEquals(StatementKind.BANK, st.kind)
        assertEquals("HDFC Bank", st.bank)
        assertEquals("1234", st.accountTail)
        assertEquals(LocalDate.of(2024, 4, 1), st.from)
        val salary = st.rows[0]
        assertEquals(TxnType.CREDIT, salary.type)
        assertEquals(8_500_000, salary.amountMinor)
        assertEquals("Acme Technologies Pvt Ltd", Narration.merchant(salary.description))
        val upi = st.rows[1]
        assertEquals(TxnType.DEBIT, upi.type)
        assertEquals("Merchant 1 Shop", Narration.merchant(upi.description))
        assertEquals("400010000001", upi.reference)
        // The last narration is intact, so strings split over CONTINUE records were joined correctly.
        assertTrue(st.rows.last().description.startsWith("UPI-MERCHANT 299 SHOP"), st.rows.last().description)
    }

    @Test
    fun sbiXlsxWithExcelDates() {
        val bytes = resource("sbi-bank.xlsx")
        assertEquals(StatementFileType.XLSX, SpreadsheetReader.detect(bytes))
        val st = assertNotNull(StatementParser.fromTable(SpreadsheetReader.read(bytes)))
        assertEquals(3, st.rows.size)
        assertEquals(LocalDate.of(2023, 4, 3), st.rows[0].date)
        assertEquals(45_000, st.rows[0].amountMinor)
        assertEquals(TxnType.DEBIT, st.rows[0].type)
        assertEquals("312345678901", st.rows[0].reference)
        assertEquals("Swiggy", Narration.merchant(st.rows[0].description))
        assertEquals(TxnType.CREDIT, st.rows[1].type)
        assertEquals("ATM withdrawal", Narration.merchant(st.rows[2].description))
        assertEquals("7890", st.accountTail)
    }

    @Test
    fun iciciCardCsvWithDrCrInTheAmount() {
        val st = assertNotNull(StatementParser.fromTable(SpreadsheetReader.read(resource("icici-card.csv"))))
        assertEquals(StatementKind.CARD, st.kind)
        assertEquals("ICICI Bank", st.bank)
        assertEquals("9876", st.accountTail)
        assertEquals(listOf(TxnType.DEBIT, TxnType.DEBIT, TxnType.CREDIT, TxnType.CREDIT), st.rows.map { it.type })
        assertEquals(123_450, st.rows[0].amountMinor)
        assertEquals("Swiggy Bangalore", Narration.merchant(st.rows[0].description))
    }

    @Test
    fun axisCsvWithSingleAmountColumnUsesTheBalance() {
        val csv = """
            Tran Date,CHQNO,PARTICULARS,AMOUNT,BALANCE
            01-04-2024,,OPENING BALANCE,,"10,000.00"
            02-04-2024,,UPI/P2M/412345678901/BLINKIT/UPI/Axis Bank,500.00,"9,500.00"
            03-04-2024,,NEFT/AXIS1234/ACME CORP,"20,000.00","29,500.00"
        """.trimIndent()
        val st = assertNotNull(StatementParser.fromTable(SpreadsheetReader.read(csv.toByteArray())))
        assertEquals(2, st.rows.size)
        assertEquals(TxnType.DEBIT, st.rows[0].type)
        assertEquals(TxnType.CREDIT, st.rows[1].type)
        assertEquals("Blinkit", Narration.merchant(st.rows[0].description))
    }

    @Test
    fun htmlTableSavedAsXls() {
        val html = """
            <html><body><table>
            <tr><td>Account No</td><td>XXXXXXXX4321</td></tr>
            <tr><th>Date</th><th>Description</th><th>Debit</th><th>Credit</th><th>Balance</th></tr>
            <tr><td>05 Apr 2024</td><td>POS 437546XXXXXX1234 DMART AVENUE</td><td>1,250.00</td><td></td><td>8,750.00</td></tr>
            <tr><td>06 Apr 2024</td><td>IMPS-412345678902-RAVI KUMAR-SBIN-XXXX-NA</td><td></td><td>300.00</td><td>9,050.00</td></tr>
            </table></body></html>
        """.trimIndent()
        val bytes = html.toByteArray()
        assertEquals(StatementFileType.HTML, SpreadsheetReader.detect(bytes))
        val st = assertNotNull(StatementParser.fromTable(SpreadsheetReader.read(bytes)))
        assertEquals("4321", st.accountTail)
        assertEquals(listOf(TxnType.DEBIT, TxnType.CREDIT), st.rows.map { it.type })
        assertEquals("Dmart Avenue", Narration.merchant(st.rows[0].description))
        assertEquals("Ravi Kumar", Narration.merchant(st.rows[1].description))
    }

    @Test
    fun wrappedNarrationRowsAreJoined() {
        val csv = """
            Date,Narration,Withdrawal,Deposit,Balance
            01/04/2024,UPI/412345678903/Payment from Ph,100.00,,900.00
            ,swiggy@ybl/YES BANK LIMITED,,,
            02/04/2024,Interest paid,,5.00,905.00
        """.trimIndent()
        val st = assertNotNull(StatementParser.fromTable(SpreadsheetReader.read(csv.toByteArray())))
        assertEquals(2, st.rows.size)
        assertEquals("Swiggy", Narration.merchant(st.rows[0].description))
        assertEquals("Interest", Narration.merchant(st.rows[1].description))
    }

    @Test
    fun moneyAndDates() {
        assertEquals(123_456L to null, StatementParser.parseMoney("1,234.56"))
        assertEquals(123_456L to TxnType.CREDIT, StatementParser.parseMoney("1,234.56 Cr"))
        assertEquals(-45_000L to null, StatementParser.parseMoney("(450.00)"))
        assertEquals(1_00_000_00L to null, StatementParser.parseMoney("₹ 1,00,000"))
        assertNull(StatementParser.parseMoney("UPI"))
        assertEquals(LocalDate.of(2024, 4, 5), StatementParser.parseDate("05/04/2024"))
        assertEquals(LocalDate.of(2024, 4, 5), StatementParser.parseDate("05-Apr-24"))
        assertEquals(LocalDate.of(2024, 4, 5), StatementParser.parseDate("5 April 2024"))
        assertEquals(LocalDate.of(2024, 4, 1), StatementParser.parseDateTime("45383")?.first)
        assertEquals(14, StatementParser.parseDateTime("05/04/2024 14:32:10")?.second?.hour)
    }

    // ---------------------------------------------------------------- PDFs

    /** Draws statement lines in columns, the way bank PDFs lay them out, optionally with a password. */
    private fun pdf(lines: List<List<Pair<Float, String>>>, password: String? = null): ByteArray {
        PDDocument().use { doc ->
            var page: PDPage? = null
            var stream: PDPageContentStream? = null
            var y = 0f
            for (cols in lines) {
                if (page == null || y < 60f) {
                    stream?.close()
                    page = PDPage().also(doc::addPage)
                    stream = PDPageContentStream(doc, page)
                    y = 760f
                }
                for ((x, text) in cols) {
                    stream!!.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 8f)
                    stream.newLineAtOffset(x, y)
                    stream.showText(text)
                    stream.endText()
                }
                y -= 12f
            }
            stream?.close()
            if (password != null) {
                doc.protect(StandardProtectionPolicy("owner-$password", password, AccessPermission()).apply { encryptionKeyLength = 128 })
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    private fun pdfText(bytes: ByteArray, password: String = ""): String =
        PDDocument.load(bytes, password).use { PDFTextStripper().apply { sortByPosition = true }.getText(it) }

    @Test
    fun passwordProtectedBankPdf() {
        val lines = mutableListOf(
            listOf(30f to "HDFC BANK LIMITED"),
            listOf(30f to "Account No : 50100012345678"),
            listOf(30f to "Statement of account From : 01/04/2024 To : 30/04/2024"),
            listOf(30f to "Opening Balance", 480f to "10,000.00"),
            listOf(30f to "Date", 80f to "Narration", 280f to "Chq./Ref.No.", 360f to "Value Dt", 410f to "Withdrawal", 470f to "Deposit", 530f to "Balance"),
        )
        var bal = 10_000_00L
        val expected = ArrayList<TxnType>()
        for (i in 1..60) {
            val credit = i % 10 == 0
            val amt = if (credit) 5_000_00L else (100L + i * 37) * 100 / 10
            bal += if (credit) amt else -amt
            expected += if (credit) TxnType.CREDIT else TxnType.DEBIT
            val d = "%02d/04/24".format((i - 1) / 2 + 1)
            val money = java.math.BigDecimal.valueOf(amt, 2).toPlainString()
            val balText = "%,.2f".format(bal / 100.0)
            lines += listOf(
                30f to d, 80f to if (credit) "NEFT CR-ACME CORP" else "UPI-SHOP $i-shop$i@okaxis-UTIB0000",
                280f to "0000412345678%03d".format(i), 360f to d,
                (if (credit) 470f else 410f) to money, 530f to balText,
            )
            if (i % 7 == 0) lines += listOf(80f to "123-PAYMENT") // wrapped narration
        }
        lines += listOf(listOf(30f to "Page 1 of 2"))
        val bytes = pdf(lines, password = "SIVA0101")

        assertFailsWith<InvalidPasswordException> { pdfText(bytes) }
        assertFailsWith<InvalidPasswordException> { pdfText(bytes, "wrong") }
        val st = assertNotNull(StatementParser.fromText(pdfText(bytes, "SIVA0101")))
        assertEquals(StatementKind.BANK, st.kind)
        assertEquals("HDFC Bank", st.bank)
        assertEquals("5678", st.accountTail)
        assertEquals(60, st.rows.size)
        assertEquals(expected, st.rows.map { it.type })
        assertEquals(LocalDate.of(2024, 4, 1), st.from)
        assertEquals(LocalDate.of(2024, 4, 30), st.to)
        assertTrue(st.rows[6].description.endsWith("123-PAYMENT"), st.rows[6].description)
    }

    @Test
    fun creditCardPdf() {
        val lines = listOf(
            listOf(30f to "Axis Bank Credit Card Statement"),
            listOf(30f to "Card No: 4375 XXXX XXXX 1122"),
            listOf(30f to "Payment Due Date", 200f to "05/04/2025"),
            listOf(30f to "Total Amount Due", 200f to "12,345.00"),
            listOf(30f to "DATE", 100f to "TRANSACTION DETAILS", 400f to "AMOUNT"),
            listOf(30f to "16/02/2025", 100f to "ZOMATO GURGAON", 400f to "650.00 Dr"),
            listOf(30f to "17/02/2025", 100f to "INDIGO AIRLINES WWW.GOINDIGO.IN", 400f to "8,250.00 Dr"),
            listOf(30f to "20/02/2025", 100f to "BBPS PAYMENT RECEIVED", 400f to "10,000.00 Cr"),
            listOf(30f to "22/02/2025", 100f to "AMAZON PAY INDIA PVT LTD", 330f to "USD 12.00", 400f to "1,020.50 Dr"),
        )
        val st = assertNotNull(StatementParser.fromText(pdfText(pdf(lines))))
        assertEquals(StatementKind.CARD, st.kind)
        assertEquals("Axis Bank", st.bank)
        assertEquals("1122", st.accountTail)
        assertEquals(4, st.rows.size)
        assertEquals(listOf(65_000L, 825_000L, 1_000_000L, 102_050L), st.rows.map { it.amountMinor })
        assertEquals(listOf(TxnType.DEBIT, TxnType.DEBIT, TxnType.CREDIT, TxnType.DEBIT), st.rows.map { it.type })
    }

    // ---------------------------------------------------------------- dedupe

    private fun stmt(vararg rows: StatementRow) = ParsedStatement(rows.toList(), StatementKind.BANK, "HDFC Bank", "1234")

    private fun row(day: Int, amount: Long, desc: String = "UPI-SHOP", type: TxnType = TxnType.DEBIT, ref: String? = null) =
        StatementRow(LocalDate.of(2024, 4, day), null, desc, amount, type, null, ref)

    private fun sms(day: Int, amount: Long, tail: String? = "1234", ref: String? = null, hour: Int = 20) = ExistingTxn(
        amount, TxnType.DEBIT, LocalDate.of(2024, 4, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(), tail, ref, "HDFCBK|x$day$amount",
    )

    @Test
    fun smsAlreadyStoredIsSkippedOncePerSms() {
        val c = StatementImport.candidates(stmt(row(1, 50_000), row(1, 50_000), row(3, 12_000)), zone = zone)
        // Only one ₹500 SMS on the 1st: one statement line matches it, the other is new.
        val marked = StatementImport.markDuplicates(c, listOf(sms(1, 50_000), sms(4, 12_000)), zone = zone)
        assertEquals(listOf(DuplicateReason.MATCHED, null, DuplicateReason.MATCHED), marked.map { it.duplicateOf })
    }

    @Test
    fun otherAccountOrFarDateIsNotADuplicate() {
        val c = StatementImport.candidates(stmt(row(1, 50_000), row(10, 7_000)), zone = zone)
        val marked = StatementImport.markDuplicates(c, listOf(sms(1, 50_000, tail = "9999"), sms(14, 7_000)), zone = zone)
        assertEquals(listOf(null, null), marked.map { it.duplicateOf })
    }

    @Test
    fun referenceMatchWinsEvenDaysApart() {
        val c = StatementImport.candidates(stmt(row(1, 50_000, ref = "412345678901")), zone = zone)
        val marked = StatementImport.markDuplicates(c, listOf(sms(6, 50_000, tail = null, ref = "412345678901")), zone = zone)
        assertEquals(DuplicateReason.REFERENCE, marked.single().duplicateOf)
    }

    @Test
    fun importingTheSameOrAnOverlappingStatementAddsNothing() {
        val first = StatementImport.candidates(stmt(row(1, 100), row(2, 200), row(2, 200)), zone = zone)
        val stored = first.map { ExistingTxn(it.row.amountMinor, it.row.type, it.timestamp, it.accountTail, it.row.reference, it.key) }
        val again = StatementImport.candidates(stmt(row(2, 200), row(2, 200), row(5, 300)).let { it.copy(rows = listOf(row(1, 100)) + it.rows) }, zone = zone)
        val marked = StatementImport.markDuplicates(again, stored, zone = zone)
        assertEquals(listOf(DuplicateReason.SAME_STATEMENT, DuplicateReason.SAME_STATEMENT, DuplicateReason.SAME_STATEMENT, null), marked.map { it.duplicateOf })
        // Same lines give the same ids on every phone.
        assertEquals(first.map { it.uid }, again.take(3).map { it.uid })
        assertEquals(LocalDate.of(2024, 4, 1), Dates.localDate(first[0].timestamp, zone))
    }
}
