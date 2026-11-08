package com.myfinance.app.ui.v12

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.myfinance.app.data.ReceiptEntity
import com.myfinance.app.data.SplitEntity
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.security.AppLock
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.core.TaxHelper
import com.myfinance.core.TaxSection
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Receipt photos, tax section and "split this payment" for a saved transaction. */
@Composable
fun TransactionExtras(vm: V12ViewModel, txn: TransactionEntity) {
    val context = LocalContext.current
    val receipts by remember(txn.uid) { vm.receipts(txn.uid) }.collectAsState(initial = emptyList())
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var viewing by remember { mutableStateOf<ReceiptEntity?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraFile?.let { f -> if (ok) vm.addReceiptFromCamera(txn.uid, f) else f.delete() }
        cameraFile = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.addReceiptFromUri(txn.uid, it) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Receipt / bill photos", style = MaterialTheme.typography.labelLarge)
            if (receipts.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    receipts.forEach { r -> Thumb(vm.receiptFile(r)) { viewing = r } }
                }
            } else Hint12("Keep the bill for warranties and records. Photos stay in the app's private storage and go into your encrypted backup.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val f = vm.newReceiptFile()
                    cameraFile = f
                    AppLock.expectExternalActivity()
                    camera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", f))
                }) { Text("Take photo") }
                OutlinedButton(onClick = {
                    AppLock.expectExternalActivity()
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("From gallery") }
            }
        }
    }

    if (txn.type == TxnType.DEBIT) {
        TaxPicker(vm, txn)
        SplitButton(vm, txn)
    }

    viewing?.let { r ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            text = { Thumb(vm.receiptFile(r), sizeDp = 280) {} },
            confirmButton = {
                TextButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", vm.receiptFile(r))
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    AppLock.expectExternalActivity()
                    runCatching { context.startActivity(Intent.createChooser(view, "Open receipt")) }
                    viewing = null
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { vm.deleteReceipt(r); viewing = null }) { Text("Delete") } },
        )
    }
}

@Composable
private fun Thumb(file: File, sizeDp: Int = 88, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, file.path, sizeDp) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, sizeDp * 3) }
    }
    Box(Modifier.size(sizeDp.dp).clickable(onClick = onClick)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Receipt", contentScale = ContentScale.Crop, modifier = Modifier.size(sizeDp.dp)) }
    }
}

private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun TaxPicker(vm: V12ViewModel, txn: TransactionEntity) {
    var current by remember { mutableStateOf<TaxSection?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(txn.uid) {
        current = vm.taxTagFor(txn.uid)?.taxSection
        loaded = true
    }
    if (!loaded) return
    val suggestion = remember(txn) { TaxHelper.suggest(txn.merchant, txn.category, txn.note, txn.rawSms) }
    Dropdown(
        "Tax section" + if (current == null && suggestion != null) " (suggested: ${suggestion.short})" else "",
        current,
        listOf<TaxSection?>(null) + TaxSection.entries,
        { it?.label ?: "Not tax-saving" },
        { current = it; vm.setTax(txn.uid, it, notTaxSaving = it == null) },
    )
}

@Composable
private fun SplitButton(vm: V12ViewModel, txn: TransactionEntity) {
    var existing by remember { mutableStateOf<SplitEntity?>(null) }
    var open by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(txn.uid, version) {
        if (version > 0) kotlinx.coroutines.delay(300)
        existing = vm.splitFor(txn.uid)
    }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(existing?.let { "Shared: ${it.toSplit().shares.size} people, ${formatInr(it.totalMinor)} (edit)" } ?: "Split this payment")
    }
    if (open) {
        SplitDialog(vm, existing, txn.uid, txn.amountMinor, txn.merchant ?: txn.category) { open = false; version++ }
    }
}
