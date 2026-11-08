package com.myfinance.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.myfinance.app.MyFinanceApp
import com.myfinance.core.SmsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Picks up new bank SMS as they arrive and stores any transaction they describe. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Long messages arrive in several parts; stitch them back together per sender.
        val bySender = messages.filterNotNull().groupBy { it.originatingAddress.orEmpty() }
        val repo = (context.applicationContext as MyFinanceApp).repository
        val pending = goAsync()

        scope.launch {
            try {
                for ((sender, parts) in bySender) {
                    if (!SmsParser.looksLikeFinancialSender(sender)) continue
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    // Use the receive time, which matches the inbox "date" column.
                    repo.ingestSms(sender, body, System.currentTimeMillis())
                }
                repo.onDataChanged()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
