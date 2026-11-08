package com.myfinance.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.myfinance.app.data.ExtrasDatabase
import com.myfinance.app.data.FinanceDatabase
import com.myfinance.app.data.FinanceRepository
import com.myfinance.app.notify.Notifier
import com.myfinance.app.notify.ReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyFinanceApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository: FinanceRepository by lazy {
        FinanceRepository(this, FinanceDatabase.create(this), ExtrasDatabase.create(this))
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        ReminderWorker.schedule(this)
        appScope.launch {
            repository.ensureDefaults()
            // After upgrading from v1.0, read the inbox once more to pick up bills and balances.
            val canRead = ContextCompat.checkSelfPermission(this@MyFinanceApp, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
            if (canRead && repository.needsBackfill) runCatching { repository.importInbox() }
            if (repository.syncUri.value != null && repository.autoSync.value) runCatching { repository.syncNow() }
        }
    }
}
