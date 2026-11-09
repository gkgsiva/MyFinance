package com.myfinance.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.myfinance.core.ParsedStatement
import com.myfinance.core.SpreadsheetReader
import com.myfinance.core.StatementFileType
import com.myfinance.core.StatementParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens a downloaded bank or card statement (PDF, Excel or CSV) and reads its transactions. */
object StatementFiles {

    sealed interface Result {
        data class Ok(val statement: ParsedStatement, val type: StatementFileType) : Result
        /** A password-protected PDF; [wrong] when the password given did not open it. */
        data class NeedsPassword(val wrong: Boolean) : Result
        data class Failed(val message: String) : Result
    }

    @Volatile private var pdfReady = false

    fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Statement"

    suspend fun read(context: Context, uri: Uri, password: String?): Result = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext Result.Failed("Could not open the file.")
        } catch (e: Exception) {
            return@withContext Result.Failed("Could not open the file: ${e.message}")
        }
        val type = SpreadsheetReader.detect(bytes)
        try {
            val statement = if (type == StatementFileType.PDF) {
                val text = when (val t = pdfText(context, bytes, password)) {
                    null -> return@withContext Result.NeedsPassword(wrong = password != null)
                    else -> t
                }
                if (text.count { it.isLetterOrDigit() } < 50) {
                    return@withContext Result.Failed(
                        "This PDF has no readable text (it is probably a scan). Download the statement again from net banking or the bank's email as PDF, Excel or CSV.",
                    )
                }
                StatementParser.fromText(text)
            } else {
                StatementParser.fromTable(SpreadsheetReader.read(bytes))
            }
            statement?.let { Result.Ok(it, type) } ?: Result.Failed(
                "No transactions found. Make sure this is a bank account statement or credit card statement with dates and amounts.",
            )
        } catch (e: OutOfMemoryError) {
            Result.Failed("This file is too large to read. Try a statement covering a shorter period.")
        } catch (e: Exception) {
            Result.Failed("Could not read this file (${e.javaClass.simpleName}). Try the Excel or CSV download instead.")
        }
    }

    /** The PDF's text, or null when it needs a (different) password. */
    private fun pdfText(context: Context, bytes: ByteArray, password: String?): String? {
        if (!pdfReady) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfReady = true
        }
        val doc = try {
            PDDocument.load(bytes, password.orEmpty())
        } catch (e: InvalidPasswordException) {
            return null
        }
        return doc.use {
            // Bank statements print in columns: keep words in reading order across each line.
            PDFTextStripper().apply { sortByPosition = true }.getText(it)
        }
    }
}
