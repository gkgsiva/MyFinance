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
import com.myfinance.app.Shortcuts
import com.myfinance.core.formatInr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Home screen widget: today's spend and the next EMI or bill due, with a + to add a spend. */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                TodayWidget.refresh(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

object TodayWidget {
    private val day = DateTimeFormatter.ofPattern("d MMM")

    suspend fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val repo = (context.applicationContext as MyFinanceApp).repository
        val (spent, next) = repo.todayGlance()

        val views = RemoteViews(context.packageName, R.layout.widget_today)
        views.setTextViewText(R.id.today_spent, formatInr(spent))
        val today = LocalDate.now()
        views.setTextViewText(
            R.id.today_next,
            next?.let { (name, amount, date) ->
                val whenText = when (date) { today -> "today"; today.plusDays(1) -> "tomorrow"; else -> date.format(day) }
                "Next: $name" + (amount?.let { " ${formatInr(it)}" } ?: "") + ", $whenText"
            } ?: "Nothing due soon",
        )
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val add = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java)
                .setAction(Shortcuts.ACTION)
                .putExtra(Shortcuts.EXTRA_ROUTE, "edit/0")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.today_root, open)
        views.setOnClickPendingIntent(R.id.today_add, add)
        manager.updateAppWidget(ids, views)
    }
}
