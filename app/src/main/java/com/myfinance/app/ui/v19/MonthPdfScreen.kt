package com.myfinance.app.ui.v19

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentAdapter.LayoutResultCallback
import android.print.PrintDocumentAdapter.WriteResultCallback
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.formatInr
import java.io.FileOutputStream
import java.time.format.DateTimeFormatter

private val monthYearFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

/** Settings, Home and Insights > Monthly report (PDF): a printable report of one month to save, share or print. */
@Composable
fun MonthPdfScreen(vm: FinanceViewModel, onBack: () -> Unit, open: ((String) -> Unit)? = null) {
    val month by vm.month.collectAsState()
    val current by vm.currentMonth.collectAsState()
    val cycle by vm.monthCycle.collectAsState()
    val summary by vm.summary.collectAsState()
    val entries by vm.monthTransactions.collectAsState()
    val sync by vm.syncState.collectAsState()
    val context = LocalContext.current

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let(vm::exportPdf)
    }

    fun send(uri: Uri, view: Boolean) = sendPdf(context, uri, view, "MyFinance report, ${month.format(monthYearFmt)}")
    fun print(uri: Uri) = printPdf(context, uri, "MyFinance ${month.format(monthYearFmt)}")

    SimpleScreen("Monthly report (PDF)", onBack) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousMonth) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(month.format(monthYearFmt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    cycle.note(month)?.let { Muted(it) }
                }
                IconButton(onClick = vm::nextMonth, enabled = month < current) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${entries.size} entries", style = MaterialTheme.typography.titleSmall)
                    Muted("Received ${formatInr(summary.incomeMinor)} · Spent ${formatInr(summary.expenseMinor)}" +
                        if (summary.investedMinor > 0) " · Invested ${formatInr(summary.investedMinor)}" else "")
                    Text(
                        "A4 pages that print well in black and white, with the month and page number on every page:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    listOf(
                        "Summary: received, spent, invested, left over, savings, card spends",
                        "Every entry, numbered, with date, payee, your notes, category, labels, account and running balance; own transfers and card bill payments listed separately",
                        "Household accounts: income and expenditure, each bank account and card, bank reconciliation (with where to look when it doesn't match), family members, loans and EMIs, receipts kept, and lines to sign",
                        "Insights and charts: what stands out, categories, card vs bank vs cash, day by day, last six months, budgets, top payees, repeating payments",
                    ).forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.sharePdf(::print) }, enabled = !sync.running, modifier = Modifier.fillMaxWidth()) {
                    Text("Print")
                }
                OutlinedButton(onClick = { vm.sharePdf { send(it, view = false) } }, enabled = !sync.running, modifier = Modifier.fillMaxWidth()) {
                    Text("Share (WhatsApp, Drive, email …)")
                }
                OutlinedButton(onClick = { vm.sharePdf { send(it, view = true) } }, enabled = !sync.running, modifier = Modifier.fillMaxWidth()) {
                    Text("Open")
                }
                OutlinedButton(onClick = { save.launch(vm.pdfFileName(month)) }, enabled = !sync.running, modifier = Modifier.fillMaxWidth()) {
                    Text("Save to phone")
                }
                if (sync.running) Muted("Making the report…")
                sync.message?.let { Muted(it) }
                Muted("Print sends it to a printer near you, or choose \"Save as PDF\" there.")
            }
        }
        if (open != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("More reports", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { open("year-pdf") }, modifier = Modifier.fillMaxWidth()) { Text("Year-end report (12 months, tax, receipts)") }
                OutlinedButton(onClick = { open("family-report") }, modifier = Modifier.fillMaxWidth()) { Text("Family report (each person apart)") }
                OutlinedButton(onClick = { open("import-health") }, modifier = Modifier.fillMaxWidth()) { Text("Balances don't match? Import health check") }
            }
        }
    }
}

/** Opens [uri] in a PDF viewer ([view]) or the share sheet; falls back to sharing with no viewer. */
fun sendPdf(context: Context, uri: Uri, view: Boolean, subject: String) {
    val intent = (if (view) {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
    } else {
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(subject, uri)
        }
    }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(if (view) intent else Intent.createChooser(intent, "Share the report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
        if (view) sendPdf(context, uri, view = false, subject = subject)
    }
}

/** Sends the PDF at [uri] to Android's print dialog (a printer, or "Save as PDF"). */
fun printPdf(context: Context, uri: Uri, name: String) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(name, object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder("$name.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }

        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest.fileDescriptor).use { input.copyTo(it) }
                }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(e.message)
            }
        }
    }, null)
}

/** Print, Share, Open and Save buttons for a report made by [make]. */
@Composable
fun PdfButtons(vm: FinanceViewModel, name: String, make: ((Uri) -> Unit) -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    val sync by vm.syncState.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { make { printPdf(context, it, name) } }, enabled = !sync.running, modifier = Modifier.fillMaxWidth()) { Text("Print") }
        OutlinedButton(onClick = { make { sendPdf(context, it, false, name) } }, enabled = !sync.running, modifier = Modifier.fillMaxWidth()) {
            Text("Share (WhatsApp, Drive, email …)")
        }
        OutlinedButton(onClick = { make { sendPdf(context, it, true, name) } }, enabled = !sync.running, modifier = Modifier.fillMaxWidth()) { Text("Open") }
        OutlinedButton(onClick = onSave, enabled = !sync.running, modifier = Modifier.fillMaxWidth()) { Text("Save to phone") }
        if (sync.running) Muted("Making the report…")
        sync.message?.let { Muted(it) }
    }
}
