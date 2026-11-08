package com.myfinance.app

import android.app.Application
import com.myfinance.app.data.FinanceDatabase
import com.myfinance.app.data.FinanceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyFinanceApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository: FinanceRepository by lazy { FinanceRepository(this, FinanceDatabase.create(this)) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { repository.ensureDefaults() }
    }
}
