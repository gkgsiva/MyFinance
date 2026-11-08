package com.myfinance.app.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.myfinance.app.MyFinanceApp
import java.util.concurrent.TimeUnit

/** Runs a few times a day: bill and recurring payment reminders, budget alerts, widget refresh, Drive sync. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as MyFinanceApp).repository
        runCatching { repo.checkReminders() }
        runCatching { repo.v12.checkBillReminders() }
        runCatching { if (repo.syncUri.value != null && repo.autoSync.value) repo.syncNow() }
        repo.onDataChanged()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
