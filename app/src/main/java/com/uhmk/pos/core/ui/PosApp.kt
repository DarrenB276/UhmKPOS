package com.uhmk.pos.core.ui

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.uhmk.pos.core.AppContainer
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.feature.auth.AuthViewModel
import com.uhmk.pos.feature.auth.LoginScreen
import com.uhmk.pos.feature.auth.StaffScreen
import com.uhmk.pos.feature.inventory.CategoriesScreen
import com.uhmk.pos.feature.inventory.InventoryScreen
import com.uhmk.pos.feature.inventory.InventoryViewModel
import com.uhmk.pos.feature.inventory.ItemEditScreen
import com.uhmk.pos.feature.inventory.ItemEditViewModel
import com.uhmk.pos.feature.notices.NoticesScreen
import com.uhmk.pos.feature.notices.NoticesViewModel
import com.uhmk.pos.feature.reports.ReportsScreen
import com.uhmk.pos.feature.reports.ReportsViewModel
import com.uhmk.pos.feature.sales.ReceiptScreen
import com.uhmk.pos.feature.sales.ReceiptViewModel
import com.uhmk.pos.feature.sales.SalesScreen
import com.uhmk.pos.feature.sales.SalesViewModel
import com.uhmk.pos.feature.sell.SellScreen
import com.uhmk.pos.feature.sell.SellViewModel
import com.uhmk.pos.feature.settings.SettingsScreen
import com.uhmk.pos.feature.settings.SettingsViewModel
import com.uhmk.pos.feature.tally.AdvancedDayTallyScreen
import com.uhmk.pos.feature.tally.AdvancedDayTallyViewModel
import com.uhmk.pos.core.ui.components.ItemThumbnail

object Routes {
    const val LOGIN = "login"
    const val SELL = "sell"
    const val INVENTORY = "inventory"
    const val ITEM_EDIT = "item"
    const val CATEGORIES = "categories"
    const val REPORTS = "reports"
    const val TALLY = "tally"
    const val SALES = "sales"
    const val RECEIPT = "receipt"
    const val NOTICES = "notices"
    const val SETTINGS = "settings"
    const val STAFF = "staff"
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val adminOnly: Boolean = false,
)

private val AllTabs = listOf(
    Tab(Routes.SELL, "Sales", Icons.Default.PointOfSale),
    Tab(Routes.INVENTORY, "Stock", Icons.Default.Inventory2),
    Tab(Routes.REPORTS, "Reports", Icons.AutoMirrored.Filled.ListAlt, adminOnly = true),
    Tab(Routes.TALLY, "Day tally", Icons.Default.Calculate),
    Tab(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp(
    container: AppContainer,
    settings: StoreSettings,
    onLock: () -> Unit = {},
    canLock: Boolean = false,
    requestedRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
) {
    val factory = remember(container) { appViewModelFactory(container) }
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showProfile by remember { mutableStateOf(false) }

    val session by container.sessionStore.session.collectAsState(
        initial = com.uhmk.pos.core.prefs.Session()
    )

    val noticesVm: NoticesViewModel = viewModel(factory = factory)
    val noticesState by noticesVm.state.collectAsStateWithLifecycle()

    if (!session.isSignedIn) {
        val authVm: AuthViewModel = viewModel(factory = factory)
        val authState by authVm.state.collectAsStateWithLifecycle()
        LoginScreen(
            state = authState,
            storeName = settings.storeName,
            onEmail = authVm::setEmail,
            onPassword = authVm::setPassword,
            onSignIn = authVm::signIn,
            onContinueLocal = authVm::continueOnDevice,
            onResetPassword = authVm::resetPassword,
        )
        return
    }

    // Inventory and Categories intentionally share one ViewModel. Selecting a category in the
    // manager therefore returns to the same filtered stock list instead of creating a second copy.
    val inventoryVm: InventoryViewModel = viewModel(factory = factory)

    // Outlives any single destination, so a snackbar raised while navigating still gets shown.
    val appScope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }

    val tabs = AllTabs.filter { !it.adminOnly || session.isAdmin }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showChrome = currentRoute in tabs.map { it.route } || currentRoute == Routes.NOTICES

    LaunchedEffect(requestedRoute, session.uid) {
        if (requestedRoute != null && session.isSignedIn) {
            val route = when (requestedRoute) {
                com.uhmk.pos.MainActivity.DESTINATION_NOTICES -> Routes.NOTICES
                com.uhmk.pos.MainActivity.DESTINATION_SALES -> if (session.isAdmin) Routes.SALES else Routes.SELL
                com.uhmk.pos.MainActivity.DESTINATION_INVENTORY -> Routes.INVENTORY
                com.uhmk.pos.MainActivity.DESTINATION_SETTINGS -> Routes.SETTINGS
                else -> null
            }
            route?.let { navController.navigate(it) { launchSingleTop = true } }
            onRouteConsumed()
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = {
                Text(
                    "Sales already recorded stay on this device and sync as normal. You will need " +
                        "your password to sign back in."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    // Releases this device's session so the account can sign in elsewhere.
                    appScope.launch { container.authService.signOut() }
                }) { Text("Log out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Stay signed in") }
            },
        )
    }

    Scaffold(
        // The top bar and navigation bar apply their own system insets, so the Scaffold must not
        // add them a second time — otherwise content is pushed down twice.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (showChrome) {
                TopAppBar(
                    title = { Text(settings.storeName) },
                    actions = {
                        IconButton(onClick = { navController.navigateTab(Routes.NOTICES) }) {
                            BadgedBox(
                                badge = {
                                    if (noticesState.unread > 0) {
                                        Badge { Text(noticesState.unread.toString()) }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Notifications, contentDescription = "Notices")
                            }
                        }
                        if (canLock) {
                            IconButton(onClick = onLock) {
                                Icon(Icons.Default.Lock, contentDescription = "Lock app")
                            }
                        }
                        IconButton(onClick = { showProfile = true }) {
                            if (session.profileImagePath != null) {
                                ItemThumbnail(
                                    name = session.displayName.ifBlank { "Account" },
                                    imagePath = session.profileImagePath,
                                    size = 32.dp,
                                    corner = 16.dp,
                                )
                            } else {
                                Icon(Icons.Default.AccountCircle, contentDescription = "Account profile")
                            }
                        }
                        DropdownMenu(
                            expanded = showProfile,
                            onDismissRequest = { showProfile = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    androidx.compose.foundation.layout.Column {
                                        Text(session.displayName.ifBlank { "Account" })
                                        Text(
                                            session.email,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            if (session.isAdmin) "Administrator" else "Staff",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                },
                                onClick = {},
                                enabled = false,
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Check sync") },
                                leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                                onClick = {
                                    showProfile = false
                                    appScope.launch {
                                        if (!container.syncManager.isCloudEnabled) {
                                            snackbar.showSnackbar("Running on this device only — Firebase is not connected")
                                            return@launch
                                        }
                                        snackbar.showSnackbar("Checking sync…")
                                        container.syncManager.syncAll().fold(
                                            onSuccess = { report ->
                                                val up = report.itemsPushed + report.salesPushed +
                                                    report.usersPushed + report.noticesPushed
                                                val down = report.itemsPulled + report.salesPulled +
                                                    report.usersPulled + report.noticesPulled
                                                snackbar.showSnackbar(
                                                    if (up == 0 && down == 0) "Everything is already up to date"
                                                    else "Synced · $up sent, $down received"
                                                )
                                            },
                                            onFailure = {
                                                snackbar.showSnackbar("Sync failed: ${it.message ?: "no connection"}")
                                            },
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Account settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    showProfile = false
                                    navController.navigateTab(Routes.SETTINGS)
                                },
                            )
                            if (canLock) {
                                DropdownMenuItem(
                                    text = { Text("Lock now") },
                                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                    onClick = { showProfile = false; onLock() },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Log out") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showProfile = false
                                    confirmLogout = true
                                },
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showChrome) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Inset the whole nav graph instead of each screen, so a screen's own bottom bar (the
        // cart summary) lands above the navigation bar rather than behind it.
        val inner = PaddingValues(0.dp)

        NavHost(
            navController = navController,
            startDestination = Routes.SELL,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SELL) {
                val vm: SellViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                val event by vm.events.collectAsStateWithLifecycle()

                LaunchedEffect(event) {
                    val current = event ?: return@LaunchedEffect

                    // Consume before doing anything else. Navigating away cancels this effect, so
                    // consuming at the end left the event live — and every time the user came back
                    // from the receipt it fired again and pushed another receipt, trapping them in
                    // a loop until the back stack unwound.
                    vm.consumeEvent()

                    val message = when (current) {
                        is com.uhmk.pos.feature.sell.SellEvent.Completed -> {
                            val base = "Sale saved · take-home " +
                                com.uhmk.pos.core.money.Money.format(current.profit, settings.currencySymbol)
                            if (current.oversold.isEmpty()) base
                            else "$base · stock was short on ${current.oversold.joinToString()}"
                        }
                        is com.uhmk.pos.feature.sell.SellEvent.Failed -> current.message
                        is com.uhmk.pos.feature.sell.SellEvent.Info -> current.message
                    }

                    if (current is com.uhmk.pos.feature.sell.SellEvent.Completed) {
                        // The snackbar is hosted by the outer Scaffold, so it must be shown from a
                        // scope that survives this destination being replaced.
                        appScope.launch { snackbar.showSnackbar(message) }
                        navController.navigate("${Routes.RECEIPT}/${current.saleId}")
                    } else {
                        snackbar.showSnackbar(message)
                    }
                }

                SellScreen(
                    state = state,
                    onQuery = vm::setQuery,
                    onCategory = vm::setCategory,
                    onPage = vm::setPage,
                    onSaleTier = vm::setSaleTier,
                    onPin = vm::setPinned,
                    onAddPage = vm::addPage,
                    onUpdatePage = vm::updatePage,
                    onDeletePage = vm::deletePage,
                    onAdd = { vm.add(it) },
                    onQty = vm::setQty,
                    onTier = vm::setTier,
                    onDiscount = vm::setDiscount,
                    onTendered = vm::setTendered,
                    onPaymentMethod = vm::setPaymentMethod,
                    onOrderType = vm::setOrderType,
                    onOrderLabel = vm::setOrderLabel,
                    onNote = vm::setNote,
                    onClearCart = vm::clearCart,
                    onHoldTicket = vm::holdTicket,
                    onLoadTicket = vm::loadTicket,
                    onDeleteTicket = vm::deleteTicket,
                    onCheckout = vm::checkout,
                    contentPadding = inner,
                )
            }

            composable(Routes.INVENTORY) {
                val state by inventoryVm.state.collectAsStateWithLifecycle()
                InventoryScreen(
                    state = state,
                    onQuery = inventoryVm::setQuery,
                    onCategory = inventoryVm::setCategory,
                    onToggleLowStock = inventoryVm::toggleLowStockOnly,
                    onToggleNeedsCost = inventoryVm::toggleNeedsCostOnly,
                    onSort = inventoryVm::setSort,
                    onSetCost = inventoryVm::setCost,
                    onOpenItem = { navController.navigate("${Routes.ITEM_EDIT}/$it") },
                    onAddItem = { navController.navigate("${Routes.ITEM_EDIT}/${ItemEditViewModel.NEW}") },
                    onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                    contentPadding = inner,
                )
            }

            composable(Routes.CATEGORIES) {
                val state by inventoryVm.state.collectAsStateWithLifecycle()
                CategoriesScreen(
                    state = state,
                    onRename = inventoryVm::renameCategory,
                    onOpenCategory = { category ->
                        inventoryVm.setCategory(category)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable("${Routes.ITEM_EDIT}/{itemId}") { entry ->
                val itemId = entry.arguments?.getString("itemId")
                val vm: ItemEditViewModel = viewModel(factory = factory)
                LaunchedEffect(itemId) { vm.load(itemId) }
                val state by vm.state.collectAsStateWithLifecycle()

                ItemEditScreen(
                    state = state,
                    onEdit = vm::edit,
                    onPickImage = vm::pickImage,
                    onClearImage = vm::clearImage,
                    onDeriveCost = vm::deriveUnitCostFromBox,
                    onSave = vm::save,
                    onDelete = { vm.delete { navController.popBackStack() } },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.REPORTS) {
                val vm: ReportsViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                val share by vm.shareIntent.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()

                LaunchedEffect(share) {
                    share?.let {
                        context.startActivity(Intent.createChooser(it, "Share CSV"))
                        vm.consumeShareIntent()
                    }
                }
                LaunchedEffect(error) {
                    error?.let {
                        snackbar.showSnackbar(it)
                        vm.consumeError()
                    }
                }

                ReportsScreen(
                    state = state,
                    onPreset = vm::setPreset,
                    onCustomRange = vm::setCustomRange,
                    onView = vm::setView,
                    onOpenSales = { navController.navigate(Routes.SALES) },
                    onExportItems = vm::exportItemBreakdown,
                    onExportCategories = vm::exportCategoryBreakdown,
                    onExportSales = vm::exportSaleDetail,
                    onExportHistory = vm::exportOrderHistory,
                    onExportInventory = vm::exportInventory,
                    contentPadding = inner,
                )
            }

            composable(Routes.TALLY) {
                val vm: AdvancedDayTallyViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()

                LaunchedEffect(message) {
                    message?.let {
                        snackbar.showSnackbar(it)
                        vm.consumeMessage()
                    }
                }

                AdvancedDayTallyScreen(
                    state = state,
                    onPeriod = vm::setPeriod,
                    onDate = vm::setDate,
                    onRange = vm::setRange,
                    onQuery = vm::setQuery,
                    onCategory = vm::setCategory,
                    onToggleOnlyCounted = vm::toggleOnlyCounted,
                    onStudent = vm::setStudent,
                    onRegular = vm::setRegular,
                    onLoadFromSales = vm::loadFromSales,
                    onLoadSavedTally = vm::loadSavedTallyForEdit,
                    onRecord = vm::recordAsSale,
                    onClear = vm::clear,
                    contentPadding = inner,
                )
            }

            composable(Routes.SALES) {
                val vm: SalesViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()

                LaunchedEffect(message) {
                    message?.let {
                        snackbar.showSnackbar(it)
                        vm.consumeMessage()
                    }
                }

                SalesScreen(
                    state = state,
                    onPreset = vm::setPreset,
                    onCustomRange = vm::setCustomRange,
                    onStatusFilter = vm::setStatusFilter,
                    onOpenReceipt = { navController.navigate("${Routes.RECEIPT}/$it") },
                    onBack = { navController.popBackStack() },
                )
            }

            composable("${Routes.RECEIPT}/{saleId}") { entry ->
                val saleId = entry.arguments?.getString("saleId").orEmpty()
                val vm: ReceiptViewModel = viewModel(factory = factory)
                LaunchedEffect(saleId) { vm.load(saleId) }
                val state by vm.state.collectAsStateWithLifecycle()
                val share by vm.shareIntent.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()

                LaunchedEffect(share) {
                    share?.let {
                        context.startActivity(Intent.createChooser(it, "Share receipt"))
                        vm.consumeShareIntent()
                    }
                }
                LaunchedEffect(message) {
                    message?.let {
                        snackbar.showSnackbar(it)
                        vm.consumeMessage()
                    }
                }

                ReceiptScreen(
                    state = state,
                    receiptText = vm.receiptText(),
                    onShare = vm::shareReceipt,
                    onExport = vm::exportReceipt,
                    onVoid = { vm.voidSale { navController.popBackStack() } },
                    onReturn = { reason -> vm.returnSale(reason) { navController.popBackStack() } },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.NOTICES) {
                val result by noticesVm.result.collectAsStateWithLifecycle()
                LaunchedEffect(result) {
                    result?.let {
                        snackbar.showSnackbar(it)
                        noticesVm.consumeResult()
                    }
                }
                NoticesScreen(
                    state = noticesState,
                    onSend = noticesVm::send,
                    onMarkRead = noticesVm::markRead,
                    onMarkAllRead = noticesVm::markAllRead,
                    onDelete = noticesVm::delete,
                    contentPadding = inner,
                )
            }

            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                val authVm: AuthViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                val updateState by vm.updateState.collectAsStateWithLifecycle()

                LaunchedEffect(message) {
                    message?.let {
                        snackbar.showSnackbar(it)
                        vm.consumeMessage()
                    }
                }

                SettingsScreen(
                    state = state,
                    updateState = updateState,
                    onUpdate = vm::update,
                    onSyncNow = vm::syncNow,
                    onReseed = vm::reseedCatalogue,
                    onResetSales = vm::resetAllSales,
                    onOpenStaff = { navController.navigate(Routes.STAFF) },
                    onSetPin = vm::setPin,
                    onRemovePin = vm::removePin,
                    onPinAutoUnlock = vm::setPinAutoUnlock,
                    onPickProfileImage = vm::setProfileImage,
                    onRemoveProfileImage = vm::removeProfileImage,
                    onResetPassword = vm::resetPassword,
                    onDeleteAccount = vm::deleteAccount,
                    onCheckForUpdates = vm::checkForUpdates,
                    onDownloadAndInstallUpdate = vm::downloadAndInstallUpdate,
                    onDismissUpdate = vm::dismissUpdate,
                    onSignOut = { authVm.signOut() },
                    contentPadding = inner,
                )
            }

            composable(Routes.STAFF) {
                val authVm: AuthViewModel = viewModel(factory = factory)
                val staff by authVm.staff.collectAsStateWithLifecycle()
                val result by authVm.staffResult.collectAsStateWithLifecycle()

                LaunchedEffect(result) {
                    result?.let {
                        snackbar.showSnackbar(it)
                        authVm.consumeStaffResult()
                    }
                }

                StaffScreen(
                    staff = staff,
                    cloudEnabled = container.syncManager.isCloudEnabled,
                    onCreate = authVm::createStaff,
                    onSetActive = authVm::setStaffActive,
                    onSetRole = authVm::setStaffRole,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Tab switches should not stack history, and should restore where the user left off. */
private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
