package com.uhmk.pos.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.uhmk.pos.BuildConfig
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.prefs.CartPanelPosition
import com.uhmk.pos.core.prefs.ThemeMode
import com.uhmk.pos.core.prefs.ReminderRepeat
import com.uhmk.pos.core.prefs.ScheduledReminder
import com.uhmk.pos.core.time.Clock
import com.uhmk.pos.core.ui.theme.Accents
import com.uhmk.pos.core.ui.components.SelectionDropdown
import com.uhmk.pos.core.ui.components.ItemThumbnail
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private enum class ReminderTimeTarget { LOW_STOCK_START, LOW_STOCK_END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    updateState: UpdateUiState,
    onUpdate: ((com.uhmk.pos.core.prefs.StoreSettings) -> com.uhmk.pos.core.prefs.StoreSettings) -> Unit,
    onSyncNow: () -> Unit,
    onReseed: () -> Unit,
    onImportInventory: (android.net.Uri) -> Unit,
    onResetSales: () -> Unit,
    onOpenStaff: () -> Unit,
    onSetPin: (String, String) -> Unit,
    onRemovePin: () -> Unit,
    onPinAutoUnlock: (Boolean) -> Unit,
    onPickProfileImage: (android.net.Uri) -> Unit,
    onRemoveProfileImage: () -> Unit,
    onResetPassword: () -> Unit,
    onDeleteAccount: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onSignOut: () -> Unit,
    contentPadding: PaddingValues,
) {
    val s = state.settings
    var confirmReseed by remember { mutableStateOf(false) }
    var confirmResetSales by remember { mutableStateOf(false) }
    var reminderTimeTarget by remember { mutableStateOf<ReminderTimeTarget?>(null) }
    var editingReminder by remember { mutableStateOf<ScheduledReminder?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    var newPaymentMethod by remember { mutableStateOf("") }
    val profilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onPickProfileImage) }
    val inventoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportInventory) }

    LazyColumn(
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item {
            CloudStatusCard(
                cloudEnabled = state.cloudEnabled,
                lastSyncAt = state.lastSyncAt,
                busy = state.busy,
                onSyncNow = onSyncNow,
            )
        }

        if (state.session.isAdmin) {
            item { SectionHeader("Store") }
            item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = s.storeName,
                    onValueChange = { v -> onUpdate { it.copy(storeName = v) } },
                    label = { Text("Store name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = s.currencySymbol,
                        onValueChange = { v -> onUpdate { it.copy(currencySymbol = v.take(3)) } },
                        label = { Text("Currency") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = s.lowStockDefault.toString(),
                        onValueChange = { v ->
                            onUpdate { it.copy(lowStockDefault = v.filter(Char::isDigit).toIntOrNull() ?: 0) }
                        },
                        label = { Text("Low stock at") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = s.receiptFooter,
                    onValueChange = { v -> onUpdate { it.copy(receiptFooter = v) } },
                    label = { Text("Receipt footer") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.deviceCode,
                    onValueChange = { v ->
                        onUpdate { it.copy(deviceCode = v.filter(Char::isLetterOrDigit).take(2)) }
                    },
                    label = { Text("Device code") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            if (s.deviceCode.isBlank()) {
                                "Leave blank if this is your only device. Receipts print as 1, 2, 3…"
                            } else {
                                "Receipts on this device print as " +
                                    "${s.deviceCode.uppercase()}-0001. Give each phone its own code."
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            }

            item { SectionHeader("Reminders") }
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Scheduled reminders",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Add as many one-time or everyday reminders as the store needs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    s.reminders.forEach { reminder ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(reminder.title.ifBlank { "Store reminder" }, fontWeight = FontWeight.Bold)
                                        Text(
                                            buildString {
                                                append(if (reminder.repeat == ReminderRepeat.DAILY) "Every day" else LocalDate.ofEpochDay(reminder.dateEpochDay).format(DateTimeFormatter.ofPattern("d MMM yyyy")))
                                                append(" · ")
                                                append(formatClock(reminder.hour, reminder.minute))
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Switch(
                                        checked = reminder.enabled,
                                        onCheckedChange = { enabled ->
                                            onUpdate { current ->
                                                current.copy(reminders = current.reminders.map {
                                                    if (it.id == reminder.id) it.copy(enabled = enabled) else it
                                                })
                                            }
                                        },
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { editingReminder = reminder }) {
                                        Icon(Icons.Default.Edit, contentDescription = null)
                                        Text("Edit")
                                    }
                                    TextButton(onClick = {
                                        onUpdate { current ->
                                            current.copy(reminders = current.reminders.filterNot { it.id == reminder.id })
                                        }
                                    }) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { editingReminder = ScheduledReminder() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Add reminder")
                    }

                    HorizontalDivider()
                    ToggleRow(
                        title = "Daily low-stock alert",
                        subtitle = "Checks inventory once every 24 hours",
                        checked = s.lowStockAlertEnabled,
                        onCheckedChange = { enabled ->
                            onUpdate { it.copy(lowStockAlertEnabled = enabled) }
                        },
                    )
                    if (s.lowStockAlertEnabled) {
                        Text(
                            "Alert window",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    reminderTimeTarget = ReminderTimeTarget.LOW_STOCK_START
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("From ${formatClock(s.lowStockStartHour, s.lowStockStartMinute)}")
                            }
                            OutlinedButton(
                                onClick = {
                                    reminderTimeTarget = ReminderTimeTarget.LOW_STOCK_END
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Until ${formatClock(s.lowStockEndHour, s.lowStockEndMinute)}")
                            }
                        }
                        Text(
                            "The daily check starts at the first time and will not notify outside " +
                                "this window.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider()
                    ToggleRow(
                        title = "Notify admins for every sale",
                        subtitle = "Shows receipt number, total, date, time and cashier",
                        checked = s.salesNotificationsEnabled,
                        onCheckedChange = { enabled ->
                            onUpdate { it.copy(salesNotificationsEnabled = enabled) }
                        },
                    )
                }
            }

            item { SectionHeader("Price tiers") }
            item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = s.studentLabel,
                        onValueChange = { v -> onUpdate { it.copy(studentLabel = v) } },
                        label = { Text("Tier 1 name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = s.regularLabel,
                        onValueChange = { v -> onUpdate { it.copy(regularLabel = v) } },
                        label = { Text("Tier 2 name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("Default tier at the till", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = s.defaultTier == PriceTier.STUDENT,
                        onClick = { onUpdate { it.copy(defaultTier = PriceTier.STUDENT) } },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(s.studentLabel) }
                    SegmentedButton(
                        selected = s.defaultTier == PriceTier.REGULAR,
                        onClick = { onUpdate { it.copy(defaultTier = PriceTier.REGULAR) } },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(s.regularLabel) }
                }
            }
            }

            item { SectionHeader("Payment methods") }
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "These choices appear in the payment dropdown at checkout.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    s.paymentMethods.forEach { method ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(method, modifier = Modifier.weight(1f))
                            if (!method.equals("Cash", ignoreCase = true)) {
                                IconButton(onClick = {
                                    onUpdate { current ->
                                        current.copy(paymentMethods = current.paymentMethods.filterNot {
                                            it.equals(method, ignoreCase = true)
                                        })
                                    }
                                }) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = "Remove $method")
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newPaymentMethod,
                            onValueChange = { newPaymentMethod = it.take(30) },
                            label = { Text("Add payment method") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                val method = newPaymentMethod.trim()
                                if (method.isNotBlank()) {
                                    onUpdate { current ->
                                        current.copy(paymentMethods = (current.paymentMethods + method)
                                            .distinctBy(String::lowercase))
                                    }
                                    newPaymentMethod = ""
                                }
                            },
                            enabled = newPaymentMethod.isNotBlank(),
                        ) { Text("Add") }
                    }
                }
            }
        }

        item { SectionHeader("Appearance") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = s.themeMode == mode,
                            onClick = { onUpdate { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        ) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) }
                    }
                }

                ToggleRow(
                    title = "Match my wallpaper",
                    subtitle = "Android 12+ picks colours from your wallpaper",
                    checked = s.dynamicColor,
                    onCheckedChange = { v -> onUpdate { it.copy(dynamicColor = v) } },
                )

                ToggleRow(
                    title = "Photo background product cards",
                    subtitle = "Show the product photo filling the tile, with the name, price and " +
                        "stock over a soft fade at the bottom",
                    checked = s.immersiveProductCards,
                    onCheckedChange = { enabled -> onUpdate { it.copy(immersiveProductCards = enabled) } },
                )

                SelectionDropdown(
                    label = "Current sale panel",
                    selected = s.cartPanelPosition,
                    options = CartPanelPosition.entries,
                    optionLabel = { it.label },
                    onSelected = { position -> onUpdate { it.copy(cartPanelPosition = position) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Docking only applies on tablets and other large screens. Phones always use " +
                        "the pop-up sheet, since a docked panel would leave no room for products.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!s.dynamicColor) {
                    Text("Accent colour", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Accents.forEachIndexed { index, accent ->
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(accent.seed)
                                    .border(
                                        width = if (s.accentIndex == index) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape,
                                    )
                                    .clickable { onUpdate { it.copy(accentIndex = index) } }
                            )
                        }
                    }
                }
            }
        }

        if (state.session.isAdmin) {
            item { SectionHeader("Staff") }
            item {
            ToggleRow(
                title = "Let staff see profit",
                subtitle = "When off, only admins see margins and take-home figures",
                checked = s.showProfitToStaff,
                onCheckedChange = { v -> onUpdate { it.copy(showProfitToStaff = v) } },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            }
            item {
            ActionRow(
                icon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                title = "Restore inventory CSV",
                subtitle = "Imports a Full inventory export and changes only matching products.",
                onClick = {
                    inventoryPicker.launch(
                        arrayOf("text/csv", "text/comma-separated-values", "application/vnd.ms-excel")
                    )
                },
            )
            ActionRow(
                icon = { Icon(Icons.Default.Group, contentDescription = null) },
                title = "Staff accounts",
                subtitle = if (state.cloudEnabled) "Add or disable staff logins"
                else "Needs Firebase — see FIREBASE_SETUP.md",
                onClick = onOpenStaff,
            )
            }

            item { SectionHeader("Data") }
            item {
            ActionRow(
                icon = { Icon(Icons.Default.Restore, contentDescription = null) },
                title = "Reload built-in price list",
                subtitle = "Re-applies the spreadsheet prices. Your photos, stock and regular prices are kept.",
                onClick = { confirmReseed = true },
            )
            if (state.session.isAdmin) {
                ActionRow(
                    icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                    title = "Clear all sales",
                    subtitle = "Deletes every sale, including voided and test ones, so reports " +
                        "start from zero. Products and prices are kept.",
                    onClick = { confirmResetSales = true },
                )
            }
            }
        }
        item { SectionHeader("Account") }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ItemThumbnail(
                    name = state.session.displayName.ifBlank { "Account" },
                    imagePath = state.session.profileImagePath,
                    size = 88.dp,
                    corner = 44.dp,
                )
                Text(state.session.displayName.ifBlank { "Account" }, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        profilePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.session.profileImagePath == null) "Add picture" else "Change")
                    }
                    if (state.session.profileImagePath != null) {
                        TextButton(onClick = onRemoveProfileImage) { Text("Remove") }
                    }
                }
            }
        }
        item {
            ActionRow(
                icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                title = if (state.pinEnabled) "Launch PIN is on" else "Set launch PIN",
                subtitle = if (state.pinEnabled) {
                    "This user enters a PIN whenever the app starts"
                } else {
                    "Optional, and saved separately for each user on this device"
                },
                onClick = {
                    if (state.pinEnabled) onRemovePin() else showPinDialog = true
                },
            )
        }
        if (state.pinEnabled) {
            item {
                ToggleRow(
                    title = "Unlock as soon as PIN is correct",
                    subtitle = "When off, press Unlock after entering the PIN",
                    checked = state.pinAutoUnlock,
                    onCheckedChange = onPinAutoUnlock,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                ToggleRow(
                    title = "Lock after inactivity",
                    subtitle = "Requires the PIN again after the selected idle time",
                    checked = s.inactivityLockEnabled,
                    onCheckedChange = { enabled -> onUpdate { it.copy(inactivityLockEnabled = enabled) } },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (s.inactivityLockEnabled) {
                item {
                    SelectionDropdown(
                        label = "Inactivity timeout",
                        selected = s.inactivityLockMinutes,
                        options = listOf(1, 5, 10, 15, 30, 60),
                        optionLabel = { "$it minute${if (it == 1) "" else "s"}" },
                        onSelected = { minutes -> onUpdate { it.copy(inactivityLockMinutes = minutes) } },
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    )
                }
            }
            item {
                ToggleRow(
                    title = "Lock when screen closes",
                    subtitle = "Locks after screen-off, Home, or leaving the app",
                    checked = s.lockWhenBackgrounded,
                    onCheckedChange = { enabled -> onUpdate { it.copy(lockWhenBackgrounded = enabled) } },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item {
            ActionRow(
                icon = { Icon(Icons.Default.Key, contentDescription = null) },
                title = "Change password",
                subtitle = "Sends a secure Firebase password-reset email",
                onClick = onResetPassword,
            )
        }
        item {
            ActionRow(
                icon = {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                title = "Delete account",
                subtitle = "Permanently removes this Firebase login",
                onClick = { confirmDeleteAccount = true },
            )
        }
        item {
            ActionRow(
                icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                title = "Sign out",
                subtitle = state.session.email.ifBlank { "Signed in on this device" },
                onClick = onSignOut,
            )
        }

        item { SectionHeader("App update") }
        item {
            ActionRow(
                icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                title = if (updateState.checking) "Checking for updates…" else "Check for updates",
                subtitle = "Installed version ${BuildConfig.VERSION_NAME} · Updates from GitHub Releases",
                onClick = onCheckForUpdates,
            )
        }

        item {
            Spacer(Modifier.height(24.dp))
            Text(
                "UhmK POS ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    updateState.available?.let { update ->
        AlertDialog(
            onDismissRequest = {
                if (!updateState.downloading) onDismissUpdate()
            },
            title = { Text("Version ${update.versionName} is available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(update.title, fontWeight = FontWeight.Bold)
                    Text(update.notes)
                    if (updateState.downloading) {
                        LinearProgressIndicator(
                            progress = { (updateState.progress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            updateState.progress?.let { "Downloading… $it%" } ?: "Downloading…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onDownloadAndInstallUpdate,
                    enabled = !updateState.downloading,
                ) {
                    Text(
                        when {
                            updateState.downloading -> "Downloading…"
                            updateState.downloadedApkPath != null -> "Install update"
                            else -> "Download and install"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissUpdate,
                    enabled = !updateState.downloading,
                ) { Text("Later") }
            },
        )
    }

    if (confirmReseed) {
        AlertDialog(
            onDismissRequest = { confirmReseed = false },
            title = { Text("Reload the price list?") },
            text = {
                Text(
                    "Costs and student prices go back to the values from your spreadsheet. " +
                        "Photos, stock counts and any regular prices you set are left alone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReseed = false
                    onReseed()
                }) { Text("Reload") }
            },
            dismissButton = { TextButton(onClick = { confirmReseed = false }) { Text("Cancel") } },
        )
    }

    if (confirmResetSales) {
        AlertDialog(
            onDismissRequest = { confirmResetSales = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Clear every recorded sale?") },
            text = {
                Text(
                    "Deletes all sales — completed, voided and returned — on this device and in " +
                        "the cloud, so reports start from zero. Meant for clearing test data " +
                        "before you go live.\n\n" +
                        "Your products, prices and costs are untouched. Stock counts are left as " +
                        "they are, so correct them in Stock if the test sales moved them.\n\n" +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmResetSales = false
                    onResetSales()
                }) { Text("Clear all sales") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetSales = false }) { Text("Cancel") }
            },
        )
    }

    reminderTimeTarget?.let { target ->
        val initial = when (target) {
            ReminderTimeTarget.LOW_STOCK_START ->
                s.lowStockStartHour to s.lowStockStartMinute
            ReminderTimeTarget.LOW_STOCK_END -> s.lowStockEndHour to s.lowStockEndMinute
        }
        val timeState = rememberTimePickerState(
            initialHour = initial.first,
            initialMinute = initial.second,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { reminderTimeTarget = null },
            title = { Text("Choose time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate {
                        when (target) {
                            ReminderTimeTarget.LOW_STOCK_START -> it.copy(
                                lowStockStartHour = timeState.hour,
                                lowStockStartMinute = timeState.minute,
                            )
                            ReminderTimeTarget.LOW_STOCK_END -> it.copy(
                                lowStockEndHour = timeState.hour,
                                lowStockEndMinute = timeState.minute,
                            )
                        }
                    }
                    reminderTimeTarget = null
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { reminderTimeTarget = null }) { Text("Cancel") }
            },
        )
    }

    editingReminder?.let { reminder ->
        ReminderEditorDialog(
            reminder = reminder,
            onSave = { saved ->
                onUpdate { current ->
                    val exists = current.reminders.any { it.id == saved.id }
                    current.copy(
                        reminders = if (exists) current.reminders.map {
                            if (it.id == saved.id) saved else it
                        } else current.reminders + saved
                    )
                }
                editingReminder = null
            },
            onDismiss = { editingReminder = null },
        )
    }

    if (confirmDeleteAccount) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAccount = false },
            title = { Text("Delete this account?") },
            text = {
                Text(
                    "This permanently removes ${state.session.email} from Firebase. Store sales " +
                        "and audit records remain. You may need to sign in again first for security."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAccount = false
                    onDeleteAccount()
                }) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAccount = false }) { Text("Cancel") }
            },
        )
    }

    if (showPinDialog) {
        var pin by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Set launch PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Use 4 to 6 digits for ${state.session.displayName}.")
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                        label = { Text("PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it.filter(Char::isDigit).take(6) },
                        label = { Text("Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetPin(pin, confirmation)
                        showPinDialog = false
                    },
                    enabled = pin.length in 4..6 && confirmation.isNotBlank(),
                ) { Text("Enable PIN") }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderEditorDialog(
    reminder: ScheduledReminder,
    onSave: (ScheduledReminder) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(reminder.id) { mutableStateOf(reminder) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scheduled reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { draft = draft.copy(note = it) },
                    label = { Text("Note") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectionDropdown(
                    label = "Repeat",
                    selected = draft.repeat,
                    options = ReminderRepeat.entries,
                    optionLabel = { if (it == ReminderRepeat.DAILY) "Every day" else "One time" },
                    onSelected = { draft = draft.copy(repeat = it) },
                )
                if (draft.repeat == ReminderRepeat.ONCE) {
                    OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Date: " + LocalDate.ofEpochDay(draft.dateEpochDay)
                                .format(DateTimeFormatter.ofPattern("d MMM yyyy"))
                        )
                    }
                }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Time: ${formatClock(draft.hour, draft.minute)}")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft.copy(enabled = true)) },
                enabled = draft.title.isNotBlank() || draft.note.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showDate) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(draft.dateEpochDay)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        draft = draft.copy(
                            dateEpochDay = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        )
                    }
                    showDate = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = picker) }
    }

    if (showTime) {
        val picker = rememberTimePickerState(
            initialHour = draft.hour,
            initialMinute = draft.minute,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Reminder time") },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(hour = picker.hour, minute = picker.minute)
                    showTime = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
        )
    }
}

private fun formatClock(hour: Int, minute: Int): String =
    LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        .format(DateTimeFormatter.ofPattern("h:mm a"))

@Composable
private fun CloudStatusCard(
    cloudEnabled: Boolean,
    lastSyncAt: Long,
    busy: Boolean,
    onSyncNow: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (cloudEnabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (cloudEnabled) Icons.Default.CloudDone else Icons.Default.CloudOff,
                    contentDescription = null,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    if (cloudEnabled) "Cloud sync on" else "Running on this device only",
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !cloudEnabled ->
                        "Add your google-services.json and rebuild to sync across phones and " +
                            "send notices to staff. Everything works without it — the data just " +
                            "stays on this device."

                    lastSyncAt > 0 -> "Last synced ${Clock.stamp(lastSyncAt)}"
                    else -> "Not synced yet"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (cloudEnabled) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onSyncNow, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (busy) "Syncing…" else "Sync now")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column {
        Spacer(Modifier.height(16.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = Color.Transparent)
    }
}
