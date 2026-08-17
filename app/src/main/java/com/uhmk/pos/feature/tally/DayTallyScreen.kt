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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.ui.components.ItemThumbnail
import com.uhmk.pos.core.ui.components.QuantityStepper
import com.uhmk.pos.core.ui.components.SearchField
import com.uhmk.pos.core.ui.components.SelectionDropdown
import com.uhmk.pos.core.ui.theme.MoneyStyleLarge
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayTallyScreen(
    state: DayTallyUiState,
    onDate: (LocalDate) -> Unit,
    onQuery: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onToggleOnlyCounted: () -> Unit,
    onStudent: (String, Int) -> Unit,
    onRegular: (String, Int) -> Unit,
    onLoadFromSales: () -> Unit,
    onRecord: () -> Unit,
    onClear: () -> Unit,
    contentPadding: PaddingValues,
) {
    val currency = state.settings.currencySymbol
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        bottomBar = {
            if (!state.isEmpty) {
                TallyTotalBar(state = state, currency = currency)
            }
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Day tally", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Count off what went out, or pull in a day that was already rung up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = { showPicker = true },
                        label = { Text(state.date.format(DAY_FORMAT)) },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                    )
                }
            }

            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onLoadFromSales,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Load that day")
                    }
                    if (!state.isEmpty) {
                        OutlinedButton(onClick = onClear) { Text("Clear") }
                    }
                }
            }

            if (state.loadedFromSales) {
                item {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            "Showing what was actually recorded on this date. Change any number to " +
                                "switch back to a hand count.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            item {
                SearchField(
                    value = state.query,
                    onValueChange = onQuery,
                    placeholder = "Find a product",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.onlyCounted,
                        onClick = onToggleOnlyCounted,
                        label = { Text("Counted only (${state.lineCount})") },
                    )
                    SelectionDropdown(
                        label = "Category",
                        selected = state.category,
                        options = listOf<String?>(null) + state.categories,
                        optionLabel = { it ?: "All products" },
                        onSelected = onCategory,
                    )
                }
            }

            items(state.visible, key = { it.id }) { item ->
                TallyRow(
                    item = item,
                    entry = state.entryFor(item.id),
                    currency = currency,
                    studentLabel = state.settings.studentLabel,
                    regularLabel = state.settings.regularLabel,
                    onStudent = { onStudent(item.id, it) },
                    onRegular = { onRegular(item.id, it) },
                )
            }

            if (!state.isEmpty && !state.loadedFromSales && state.session.isAdmin) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Button(
                            onClick = onRecord,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Record this tally as a sale")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Adds these counts to ${state.date.format(DAY_FORMAT)} so they show up " +
                                "in reports. Only do this for a day you did not ring up item by item.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        // The picker reports UTC midnight; read it back as a plain date so the
                        // day does not shift in the Philippines timezone.
                        onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        // Selecting a date is the common reporting path, so load it immediately.
                        // The manual-count fields remain editable if the owner needs corrections.
                        onLoadFromSales()
                    }
                    showPicker = false
                }) { Text("Select") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState, showModeToggle = false) }
    }
}

@Composable
private fun TallyRow(
    item: ItemEntity,
    entry: TallyEntry,
    currency: String,
    studentLabel: String,
    regularLabel: String,
    onStudent: (Int) -> Unit,
    onRegular: (Int) -> Unit,
) {
    val counted = !entry.isEmpty
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (counted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (item.hasTwoPrices) {
                            "${Money.format(item.studentCentavos, currency)} / " +
                                Money.format(item.regularCentavos, currency)
                        } else {
                            Money.format(item.studentCentavos, currency)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (counted) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${entry.student + entry.regular} sold",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            Money.format(
                                entry.student * item.studentCentavos +
                                    entry.regular * item.regularCentavos,
                                currency,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            TierRow(
                label = studentLabel,
                price = item.studentCentavos,
                currency = currency,
                qty = entry.student,
                onQty = onStudent,
            )
            if (item.hasTwoPrices) {
                Spacer(Modifier.height(5.dp))
                TierRow(
                    label = regularLabel,
                    price = item.regularCentavos,
                    currency = currency,
                    qty = entry.regular,
                    onQty = onRegular,
                )
            }
        }
    }
}

@Composable
private fun TierRow(
    label: String,
    price: Long,
    currency: String,
    qty: Int,
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
        // min = 0 so a line can be counted back down to nothing.
        QuantityStepper(qty = qty, onChange = onQty, min = 0)
    }
}

@Composable
private fun TallyTotalBar(state: DayTallyUiState, currency: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${state.unitCount} units · ${state.lineCount} products",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text("Sales total", fontWeight = FontWeight.SemiBold)
                }
                Text(Money.format(state.revenue, currency), style = MoneyStyleLarge)
            }
            Spacer(Modifier.height(8.dp))
            if (state.canSeeProfit) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Cost of goods ${Money.format(state.capital, currency)}",
                        style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Gross profit ${Money.format(state.profit, currency)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (state.hasUnknownCost && state.canSeeProfit) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Money.format(state.unknownRevenue, currency)} of this is from items with no " +
                        "cost set, so their profit is not included.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
