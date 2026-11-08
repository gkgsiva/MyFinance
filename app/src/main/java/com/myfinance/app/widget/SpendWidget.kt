package com.myfinance.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.myfinance.app.MainActivity
import com.myfinance.app.MyFinanceApp
import com.myfinance.app.R
import com.myfinance.core.formatInr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Home screen widget: this month's spending and income at a glance. Tap to open the app. */
class SpendWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                SpendWidget.refresh(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

object SpendWidget {
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM")

    suspend fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, SpendWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val repo = (context.applicationContext as MyFinanceApp).repository
        val month = YearMonth.now()
        val totals = repo.monthTotals(month)
        val budget = repo.totalBudget()

        val views = RemoteViews(context.packageName, R.layout.widget_spend)
        views.setTextViewText(R.id.widget_title, "Spent in ${month.format(monthFormat)}")
        views.setTextViewText(R.id.widget_spent, formatInr(totals.expenseMinor))
        val detail = buildString {
            append("Income ${formatInr(totals.incomeMinor)}")
            if (budget > 0) {
                val left = budget - totals.expenseMinor
                append(if (left >= 0) " · ${formatInr(left)} left" else " · over by ${formatInr(-left)}")
            }
        }
        views.setTextViewText(R.id.widget_detail, detail)
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        manager.updateAppWidget(ids, views)
    }
}
