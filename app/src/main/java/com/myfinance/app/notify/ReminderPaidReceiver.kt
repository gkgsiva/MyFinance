package com.myfinance.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.myfinance.app.MyFinanceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The "Already paid" button on a payment reminder notification: marks that reminder paid. */
class ReminderPaidReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val billId = intent.getStringExtra(EXTRA_BILL) ?: return
        val notification = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        NotificationManagerCompat.from(context).cancel(notification)
        val repo = (context.applicationContext as MyFinanceApp).repository
        val pending = goAsync()
        scope.launch {
            try {
                repo.v12.dao.bills().firstOrNull { it.id == billId }?.let { repo.v12.markBillPaid(it) }
                repo.onDataChanged()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_BILL = "bill_id"
        const val EXTRA_NOTIFICATION = "notification_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
