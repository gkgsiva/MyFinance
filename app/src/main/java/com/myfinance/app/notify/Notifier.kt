package com.myfinance.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myfinance.app.MainActivity
import com.myfinance.app.R
import com.myfinance.core.BudgetAlert

/** Budget alerts and bill / recurring payment reminders. */
object Notifier {
    private const val CHANNEL_BUDGETS = "budgets"
    private const val CHANNEL_REMINDERS = "reminders"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BUDGETS, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a category passes 80% or 100% of its monthly budget"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Bills and payments due", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Credit card bills, EMIs and recurring payments that are coming up"
            },
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun budget(context: Context, alert: BudgetAlert) {
        val title = if (alert.percent >= 100) "${alert.category}: over budget" else "${alert.category}: ${alert.percent}% of budget used"
        post(context, CHANNEL_BUDGETS, ("budget" + alert.key).hashCode(), title, alert.message)
    }

    /** A payment reminder; with [paidBillId] it has an "Already paid" button that marks that reminder paid. */
    fun reminder(context: Context, id: Int, title: String, text: String, paidBillId: String? = null) =
        post(context, CHANNEL_REMINDERS, id, title, text, paidBillId)

    private fun post(context: Context, channel: String, id: Int, title: String, text: String, paidBillId: String? = null) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply {
                if (paidBillId != null) {
                    val paid = PendingIntent.getBroadcast(
                        context, id,
                        Intent(context, ReminderPaidReceiver::class.java)
                            .putExtra(ReminderPaidReceiver.EXTRA_BILL, paidBillId)
                            .putExtra(ReminderPaidReceiver.EXTRA_NOTIFICATION, id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    addAction(0, "Already paid", paid)
                }
            }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the call.
        }
    }
}
