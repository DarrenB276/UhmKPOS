package com.uhmk.pos.feature.tally

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.db.AuditLogEntity
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.time.Clock
import com.uhmk.pos.core.ui.components.ItemThumbnail
import com.uhmk.pos.core.ui.components.QuantityStepper
import com.uhmk.pos.core.ui.components.SearchField
import com.uhmk.pos.core.ui.components.SelectionDropdown
import com.uhmk.pos.core.ui.theme.MoneyStyleLarge
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ADVANCED_DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedDayTallyScreen(
    state: AdvancedDayTallyUiState,
    onPeriod: (TallyPeriod) -> Unit,
    onDate: (LocalDate) -> Unit,
    onRange: (LocalDate, LocalDate) -> Unit,
    onQuery: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onToggleOnlyCounted: () -> Unit,
    onStudent: (String, Int) -> Unit,
    onRegular: (String, Int) -> Unit,
    onLoadFromSales: () -> Unit,
    onLoadSavedTally: () -> Unit,
    onRecord: (String?) -> Unit,
    onClear: () -> Unit,
    contentPadding: PaddingValues,
) {
    val currency = state.settings.currencySymbol
    var showDayPicker by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        bottomBar = {
            if (!state.isEmpty) AdvancedTallyTotalBar(state, currency)
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() + 16.dp,
            ),
        ) {
            // The header used to be seven stacked blocks — a headline, a subtitle and five
            // full-width controls — which filled most of a phone screen before the first product.
            // Everything now pairs up onto rows, and tablets fold it down further still.
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionDropdown(
                        label = "Tally period",
                        selected = state.period,
                        options = TallyPeriod.entries,
                        optionLabel = { it.label },
                        onSelected = onPeriod,
                        modifier = Modifier.weight(1f),
                    )
                    AssistChip(
                        onClick = {
                            if (state.period == TallyPeriod.DAY) showDayPicker = true
                            else showRangePicker = true
                        },
                        label = {
                            Text(
                                if (state.period == TallyPeriod.DAY) {
                                    state.date.format(ADVANCED_DAY_FORMAT)
                                } else {
                                    "${state.date.format(ADVANCED_DAY_FORMAT)} – " +
                                        state.toDate.format(ADVANCED_DAY_FORMAT)
                                }
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                    )
                }
            }

            item {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.period == TallyPeriod.DAY) {
                        OutlinedButton(
                            onClick = onLoadFromSales,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Show day's sales", maxLines = 1)
                        }
                        if (state.existingTallyId != null && state.session.isAdmin) {
                            OutlinedButton(
                                onClick = onLoadSavedTally,
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Edit saved", maxLines = 1)
                            }
                        }
                    }
                    FilterChip(
                        selected = state.onlyCounted,
                        onClick = onToggleOnlyCounted,
                        label = { Text("Counted (${state.lineCount})") },
                    )
                }
            }

            if (state.source != TallySource.MANUAL) {
                item { AdvancedTallyModeBanner(state) }
            }

            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchField(
                        value = state.query,
                        onValueChange = onQuery,
                        placeholder = "Find a product",
                        modifier = Modifier.weight(1.4f),
                    )
                    SelectionDropdown(
                        label = "Category",
                        selected = state.category,
                        options = listOf<String?>(null) + state.categories,
                        optionLabel = { it ?: "All products" },
                        onSelected = onCategory,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            items(state.visible, key = { it.id }) { item ->
                AdvancedTallyRow(
                    item = item,
                    entry = state.entryFor(item.id),
                    currency = currency,
                    studentLabel = state.settings.studentLabel,
                    regularLabel = state.settings.regularLabel,
                    readOnly = state.isReadOnly,
                    onStudent = { onStudent(item.id, it) },
                    onRegular = { onRegular(item.id, it) },
                )
            }

            if (!state.isEmpty && !state.isReadOnly && state.session.isAdmin) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Button(
                            onClick = {
                                if (state.editingSavedTally) showPinDialog = true else onRecord(null)
                            },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.editingSavedTally) "Save tally changes" else "Save tally for this date")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (state.editingSavedTally) {
                                "Saving replaces the old tally, restores its old stock movement, and logs the differences."
                            } else {
                                "Use this for counts that were not already entered as individual receipts."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!state.isEmpty) {
                item {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("Clear view") }
                }
            }

            if (state.period == TallyPeriod.DAY && state.auditLogs.isNotEmpty() && state.session.isAdmin) {
                item {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tally change log", style = MaterialTheme.typography.titleMedium)
                    }
                }
                items(state.auditLogs, key = { it.id }) { log -> AdvancedAuditCard(log) }
            }
        }
    }

    if (showDayPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDayPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDayPicker = false
                }) { Text("Select") }
            },
            dismissButton = { TextButton(onClick = { showDayPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState, showModeToggle = false) }
    }

    if (showRangePicker) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = state.toDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = pickerState.selectedStartDateMillis
                    val end = pickerState.selectedEndDateMillis ?: start
                    if (start != null && end != null) {
                        onRange(
                            Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(),
                            Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                    showRangePicker = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showRangePicker = false }) { Text("Cancel") } },
        ) { DateRangePicker(state = pickerState, showModeToggle = false) }
    }

    if (showPinDialog) {
        TallyPinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = {
                showPinDialog = false
                onRecord(it)
            },
        )
    }
}

@Composable
private fun AdvancedTallyModeBanner(state: AdvancedDayTallyUiState) {
    val message = when (state.source) {
        TallySource.RECORDED_SALES -> "Showing every receipt recorded on this day. This view is read-only to prevent duplicate sales."
        TallySource.RANGE_TOTAL -> "Showing combined sales from the selected date range. Change the range to recalculate it."
        TallySource.SAVED_TALLY -> "Editing the saved tally only. Your PIN and the exact changes will be recorded when you save."
        TallySource.MANUAL -> return
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun AdvancedTallyRow(
    item: ItemEntity,
    entry: TallyEntry,
    currency: String,
    studentLabel: String,
    regularLabel: String,
    readOnly: Boolean,
    onStudent: (Int) -> Unit,
    onRegular: (Int) -> Unit,
) {
    val counted = !entry.isEmpty
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (counted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemThumbnail(
                    name = item.name,
                    imagePath = item.imagePath,
                    size = 72.dp,
                    corner = 14.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (counted) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.category.ifBlank { "Uncategorised" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (counted) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${entry.total} sold", style = MaterialTheme.typography.labelSmall)
                        Text(
                            Money.format(
                                entry.student * item.studentCentavos + entry.regular * item.regularCentavos,
                                currency,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            AdvancedTierRow(studentLabel, item.studentCentavos, currency, entry.student, readOnly, onStudent)
            if (item.hasTwoPrices) {
                Spacer(Modifier.height(5.dp))
                AdvancedTierRow(regularLabel, item.regularCentavos, currency, entry.regular, readOnly, onRegular)
            }
        }
    }
}

@Composable
private fun AdvancedTierRow(
    label: String,
    price: Long,
    currency: String,
    qty: Int,
    readOnly: Boolean,
    onQty: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label ${Money.format(price, currency)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (readOnly) {
            Text("× $qty", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        } else {
            QuantityStepper(qty = qty, onChange = onQty, min = 0)
        }
    }
}

@Composable
private fun AdvancedTallyTotalBar(state: AdvancedDayTallyUiState, currency: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${state.unitCount} units · ${state.lineCount} products", style = MaterialTheme.typography.labelMedium)
                    Text("Sales total", fontWeight = FontWeight.SemiBold)
                }
                Text(Money.format(state.revenue, currency), style = MoneyStyleLarge)
            }
            if (state.canSeeProfit) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Capital ${Money.format(state.capital, currency)}", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Take-home ${Money.format(state.profit, currency)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (state.hasUnknownCost && state.canSeeProfit) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Money.format(state.unknownRevenue, currency)} has no saved cost and is excluded from take-home.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun AdvancedAuditCard(log: AuditLogEntity) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (log.action == "TALLY_EDITED") "Tally edited" else "Tally created",
                fontWeight = FontWeight.Bold,
            )
            Text("${log.actorName} · ${Clock.stamp(log.occurredAt)}", style = MaterialTheme.typography.labelSmall)
            Text("Before: ${log.beforeSummary}", style = MaterialTheme.typography.bodySmall)
            Text("After: ${log.afterSummary}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TallyPinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm with your PIN") },
        text = {
            Column {
                Text("This protects old tally records and records who made the change.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        if (value.length <= 6 && value.all(Char::isDigit)) pin = value
                    },
                    label = { Text("4–6 digit PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = pin.length in 4..6) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
