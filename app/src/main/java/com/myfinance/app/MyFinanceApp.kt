package com.myfinance.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.myfinance.app.data.ExtrasDatabase
import com.myfinance.app.data.FinanceDatabase
import com.myfinance.app.data.FinanceRepository
import com.myfinance.app.data.V12Database
import com.myfinance.app.data.V13Database
import com.myfinance.app.data.V14Database
import com.myfinance.app.data.V15Database
import com.myfinance.app.data.V12Repository
import com.myfinance.app.notify.Notifier
import com.myfinance.app.notify.ReminderWorker
import com.myfinance.app.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyFinanceApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository: FinanceRepository by lazy {
        // A restore chosen last time is swapped in before any database is opened.
        V12Repository.applyPendingRestore(this)
        FinanceRepository(this, FinanceDatabase.create(this), ExtrasDatabase.create(this), V12Database.create(this), V13Database.create(this), V14Database.create(this), V15Database.create(this), com.myfinance.app.data.V16Database.create(this), com.myfinance.app.data.V17Database.create(this))
    }

    override fun onCreate() {
        super.onCreate()
        AppLock.init(this)
        com.myfinance.app.ui.theme.ThemePref.init(this)
        Notifier.createChannels(this)
        ReminderWorker.schedule(this)
        com.myfinance.app.backup.AutoBackup.init(this)
        com.myfinance.app.backup.AutoBackup.schedule(this)
        appScope.launch {
            repository.ensureDefaults()
            runCatching { repository.purgeOldTrash() }
            // After upgrading from v1.0, read the inbox once more to pick up bills and balances.
            val canRead = ContextCompat.checkSelfPermission(this@MyFinanceApp, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
            if (canRead && repository.needsBackfill) runCatching { repository.importInbox() }
            if (repository.syncUri.value != null && repository.autoSync.value) runCatching { repository.syncNow() }
        }
    }
}
