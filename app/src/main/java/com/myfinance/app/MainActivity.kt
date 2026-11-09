package com.myfinance.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import android.view.WindowManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.myfinance.app.ui.v13.AutoDebitsScreen
import com.myfinance.app.ui.v13.RescanScreen
import com.myfinance.app.ui.v13.TrashScreen
import com.myfinance.app.security.AppLock
import com.myfinance.app.share.FamilyShare
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.v12.BackupScreen
import com.myfinance.app.ui.v12.BillsScreen
import com.myfinance.app.ui.v12.CashScreen
import com.myfinance.app.ui.v12.GoalsScreen
import com.myfinance.app.ui.v12.LockScreen
import com.myfinance.app.ui.v12.NetWorthScreen
import com.myfinance.app.ui.v12.PlanScreen
import com.myfinance.app.ui.v12.RulesScreen
import com.myfinance.app.ui.v12.SecurityScreen
import com.myfinance.app.ui.v12.SplitsScreen
import com.myfinance.app.ui.v12.TaxScreen
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
import com.myfinance.app.ui.screens.ImportStatementScreen
import com.myfinance.app.ui.screens.AccountsScreen
import com.myfinance.app.ui.screens.OverviewScreen
import com.myfinance.app.ui.screens.ReportsScreen
import com.myfinance.app.ui.screens.TransactionsScreen
import com.myfinance.app.ui.screens.UpcomingScreen
import com.myfinance.app.ui.theme.MyFinanceTheme

/** A FragmentActivity so the fingerprint prompt can be shown. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) FamilyShare.accept(intent)
        setContent {
            val themeMode by com.myfinance.app.ui.theme.ThemePref.mode.collectAsState()
            val dark = when (themeMode) {
                com.myfinance.app.ui.theme.ThemeMode.LIGHT -> false
                com.myfinance.app.ui.theme.ThemeMode.DARK -> true
                com.myfinance.app.ui.theme.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            MyFinanceTheme(dark = dark) {
                val lockOn by AppLock.enabled.collectAsState()
                val locked by AppLock.locked.collectAsState()
                val bio by AppLock.biometric.collectAsState()
                // Keep the app's contents out of screenshots and the recent apps screen while the lock is on.
                LaunchedEffect(lockOn) {
                    if (lockOn) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                Box(Modifier.fillMaxSize()) {
                    MyFinanceRoot()
                    if (lockOn && locked) LockScreen(bio) else SharedFileImport()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        FamilyShare.accept(intent)
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        AppLock.onBackground()
    }
}

/** Asks before merging a file opened from WhatsApp, then shows what was added. */
@Composable
private fun SharedFileImport() {
    val vm: FinanceViewModel = viewModel()
    val incoming by FamilyShare.incoming.collectAsState()
    val sync by vm.syncState.collectAsState()
    var merging by remember { mutableStateOf(false) }

    incoming?.let { uri ->
        AlertDialog(
            onDismissRequest = { FamilyShare.clear() },
            title = { Text("Merge shared file?") },
            text = {
                Text(
                    "Add the transactions in this MyFinance file to this phone. Entries already here are " +
                        "skipped, and each one stays labelled with the person who shared it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    FamilyShare.clear()
                    merging = true
                    vm.mergeShared(uri)
                }) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { FamilyShare.clear() }) { Text("Cancel") } },
        )
    }
    if (merging && !sync.running && sync.message != null) {
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text("Family file") },
            text = { Text(sync.message.orEmpty()) },
            confirmButton = { TextButton(onClick = { merging = false }) { Text("OK") } },
        )
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("overview", "Overview", Icons.Filled.Home),
    Tab("transactions", "Transactions", Icons.AutoMirrored.Filled.List),
    Tab("reports", "Reports", Icons.Filled.Info),
    Tab("plan", "Plan", Icons.Filled.DateRange),
    Tab("categories", "Manage", Icons.Filled.Star),
)

@Composable
private fun MyFinanceRoot() {
    val vm: FinanceViewModel = viewModel()
    val v12: V12ViewModel = viewModel()
    val nav = rememberNavController()
    val context = LocalContext.current

    fun hasSmsPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

    var smsGranted by remember { mutableStateOf(hasSmsPermission()) }
    // Android blocks SMS access for apps installed from an APK ("restricted settings"), so the request
    // can be refused without any prompt. When that happens, explain how to allow it in Settings.
    var smsBlocked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        smsGranted = result[Manifest.permission.READ_SMS] == true || hasSmsPermission()
        if (smsGranted) vm.importInbox() else smsBlocked = true
    }
    // Pick up a permission granted in Settings as soon as the user comes back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !smsGranted && hasSmsPermission()) {
                smsGranted = true
                smsBlocked = false
                vm.importInbox()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (smsBlocked) {
        AlertDialog(
            onDismissRequest = { smsBlocked = false },
            title = { Text("Allow SMS access in Settings") },
            text = {
                Text(
                    "Android blocks SMS access for apps installed from an APK until you allow it:\n\n" +
                        "1. Tap Open settings below.\n" +
                        "2. Tap the ⋮ menu (top right) and choose \"Allow restricted settings\". " +
                        "Confirm with your PIN or fingerprint. If you don't see it, skip this step.\n" +
                        "3. Tap Permissions > SMS > Allow.\n" +
                        "4. Come back to MyFinance. Import starts by itself.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    smsBlocked = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { smsBlocked = false }) { Text("Not now") } },
        )
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

    // "Transaction deleted · Undo" after any delete. Restoring later is in Manage > Recently deleted.
    val snackbar = remember { SnackbarHostState() }
    val justDeleted by vm.justDeleted.collectAsState()
    LaunchedEffect(justDeleted) {
        val t = justDeleted ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(
            message = "Deleted ${t.merchant ?: t.category}",
            actionLabel = "Undo",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (r == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.clearJustDeleted()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                    open = { route -> nav.navigate(route) },
                )
            }
            composable("upcoming") {
                UpcomingScreen(vm = vm, onBack = { nav.popBackStack() }, onOpenAutoDebits = { nav.navigate("autodebits") })
            }
            composable("autodebits") { AutoDebitsScreen(vm = vm, onBack = { nav.popBackStack() }) }
            composable("trash") { TrashScreen(vm = vm, onBack = { nav.popBackStack() }) }
            composable("settings") {
                com.myfinance.app.ui.v13.SettingsScreen(vm = vm, v12 = v12, onBack = { nav.popBackStack() }) { route ->
                    if (route in tabs.map { it.route }) {
                        nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    } else nav.navigate(route)
                }
            }
            composable("hidden") { com.myfinance.app.ui.v13.HiddenItemsScreen(vm = vm, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("edit/$id") }) }
            composable("payees") { com.myfinance.app.ui.v13.PayeeNamesScreen(vm = vm, onBack = { nav.popBackStack() }) }
            composable("labels") { com.myfinance.app.ui.v13.LabelsScreen(vm = vm, onBack = { nav.popBackStack() }) }
            composable("month-settings") { com.myfinance.app.ui.v13.MonthSettingsScreen(vm = vm, onBack = { nav.popBackStack() }) }
            composable("rescan") {
                RescanScreen(vm = vm, smsGranted = smsGranted, onAskPermission = requestSms, onBack = { nav.popBackStack() })
            }
            composable("reports") { ReportsScreen(vm = vm, onExplore = { nav.navigate("explore") }, open = { route -> nav.navigate(route) }) }
            // v1.4
            composable("label-suggestions") { com.myfinance.app.ui.v14.SuggestionsScreen(vm = vm, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("edit/$id") }) }
            composable("card-bills") { com.myfinance.app.ui.v14.CardBillsScreen(vm = vm, onBack = { nav.popBackStack() }, onImport = { nav.navigate("import-statement") }) }
            composable("loans") { com.myfinance.app.ui.v14.LoansScreen(vm = vm, onBack = { nav.popBackStack() }, onOpenAutoDebits = { nav.navigate("autodebits") }) }
            composable("refunds") { com.myfinance.app.ui.v14.RefundsScreen(vm = vm, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("edit/$id") }) }
            composable("year-review") { com.myfinance.app.ui.v14.YearReviewScreen(vm = vm, onBack = { nav.popBackStack() }) }
            composable("statement-reminders") {
                com.myfinance.app.ui.v14.StatementRemindersScreen(vm = vm, onBack = { nav.popBackStack() }, onImport = { nav.navigate("import-statement") })
            }
            composable("explore") { com.myfinance.app.ui.v13.ExploreScreen(vm = vm, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("edit/$id") }) }
            composable("transactions") {
                TransactionsScreen(
                    vm = vm,
                    onOpen = { id -> nav.navigate("edit/$id") },
                    onAdd = { nav.navigate("edit/0") },
                )
            }
            composable("categories") { CategoriesScreen(vm = vm, open = { route -> nav.navigate(route) }) }
            composable("accounts") { AccountsScreen(vm = vm, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("edit/$id") }) }
            composable("import-statement") {
                ImportStatementScreen(
                    onBack = { nav.popBackStack() },
                    onDone = {
                        nav.popBackStack()
                        nav.navigate("transactions") { launchSingleTop = true }
                    },
                )
            }
            composable("plan") { PlanScreen(v12) { route -> nav.navigate(route) } }
            val back = { nav.popBackStack(); Unit }
            val openTxn = { id: Long -> nav.navigate("edit/$id") }
            composable("goals") { GoalsScreen(v12, back) }
            composable("networth") { NetWorthScreen(v12, back) }
            composable("bills") { BillsScreen(v12, back) }
            composable("splits") { SplitsScreen(v12, back) }
            composable("cash") { CashScreen(v12, back, openTxn) }
            composable("tax") { TaxScreen(v12, back, openTxn) }
            composable("rules") { RulesScreen(v12, back) }
            composable("security") { SecurityScreen(v12, back) }
            composable("backup") { BackupScreen(v12, back) }
            composable(
                "edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                EditTransactionScreen(
                    vm = vm,
                    v12 = v12,
                    id = entry.arguments?.getLong("id") ?: 0L,
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}
