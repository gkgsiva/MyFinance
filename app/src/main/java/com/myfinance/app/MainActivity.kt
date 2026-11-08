package com.myfinance.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.screens.CategoriesScreen
import com.myfinance.app.ui.screens.EditTransactionScreen
import com.myfinance.app.ui.screens.OverviewScreen
import com.myfinance.app.ui.screens.ReportsScreen
import com.myfinance.app.ui.screens.TransactionsScreen
import com.myfinance.app.ui.screens.UpcomingScreen
import com.myfinance.app.ui.theme.MyFinanceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MyFinanceTheme { MyFinanceRoot() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("overview", "Overview", Icons.Filled.Home),
    Tab("transactions", "Transactions", Icons.AutoMirrored.Filled.List),
    Tab("reports", "Reports", Icons.Filled.Info),
    Tab("categories", "Manage", Icons.Filled.Star),
)

@Composable
private fun MyFinanceRoot() {
    val vm: FinanceViewModel = viewModel()
    val nav = rememberNavController()
    val context = LocalContext.current

    fun hasSmsPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

    var smsGranted by remember { mutableStateOf(hasSmsPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        smsGranted = result[Manifest.permission.READ_SMS] == true
        if (smsGranted) vm.importInbox()
    }
    val requestSms = {
        val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    // Budget alerts and bill reminders need notification permission on Android 13+.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && smsGranted &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val importOrAsk = { if (smsGranted) vm.importInbox() else requestSms() }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == currentRoute }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "overview", modifier = Modifier.padding(padding)) {
            composable("overview") {
                OverviewScreen(
                    vm = vm,
                    smsGranted = smsGranted,
                    onImport = importOrAsk,
                    onOpenGroup = { category ->
                        vm.categoryFilter.value = category
                        nav.navigate("transactions") { launchSingleTop = true }
                    },
                    onAdd = { nav.navigate("edit/0") },
                    onOpenUpcoming = { nav.navigate("upcoming") },
                )
            }
            composable("upcoming") { UpcomingScreen(vm = vm, onBack = { nav.popBackStack() }) }
            composable("reports") { ReportsScreen(vm = vm) }
            composable("transactions") {
                TransactionsScreen(
                    vm = vm,
                    onOpen = { id -> nav.navigate("edit/$id") },
                    onAdd = { nav.navigate("edit/0") },
                )
            }
            composable("categories") { CategoriesScreen(vm = vm) }
            composable(
                "edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                EditTransactionScreen(
                    vm = vm,
                    id = entry.arguments?.getLong("id") ?: 0L,
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}
