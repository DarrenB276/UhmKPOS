package com.uhmk.pos.feature.sales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.db.SaleEntity
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.model.OrderType
import com.uhmk.pos.core.model.SaleStatus
import com.uhmk.pos.core.time.Clock
import com.uhmk.pos.core.time.RangePreset
import com.uhmk.pos.core.ui.components.EmptyState
import com.uhmk.pos.core.ui.components.StatCard
import com.uhmk.pos.core.ui.components.SelectionDropdown
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesScreen(
    state: SalesUiState,
    onPreset: (RangePreset) -> Unit,
    onCustomRange: (LocalDate, LocalDate) -> Unit,
    onStatusFilter: (OrderHistoryFilter) -> Unit,
    onOpenReceipt: (String) -> Unit,
    onBack: () -> Unit,
) {
    val currency = state.settings.currencySymbol
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = 24.dp)) {
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectionDropdown(
                        label = "Date period",
                        selected = state.range.preset,
                        options = RangePreset.entries,
                        optionLabel = { it.label },
                        onSelected = { preset ->
                            if (preset == RangePreset.CUSTOM) showPicker = true else onPreset(preset)
                        },
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = { showPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text(state.range.label, modifier = Modifier.padding(start = 8.dp))
                    }
                    SelectionDropdown(
                        label = "Order status",
                        selected = state.statusFilter,
                        options = OrderHistoryFilter.entries,
                        optionLabel = { it.label },
                        onSelected = onStatusFilter,
                    )
                }
            }

            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        label = "Sales",
                        value = state.totals.saleCount.toString(),
                        caption = "${state.totals.itemsSold} items",
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Net sales",
                        value = Money.format(state.totals.net, currency),
                        caption = state.range.label,
                        emphasis = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (state.sales.isEmpty()) {
                item {
                    Spacer(Modifier.height(48.dp))
                    EmptyState(
                        title = "No orders",
                        body = "No completed, voided, or returned orders in this period.",
                    )
                }
            }

            items(state.sales, key = { it.id }) { sale ->
                SaleRow(
                    sale = sale,
                    currency = currency,
                    showProfit = state.session.isAdmin || state.settings.showProfitToStaff,
                    onClick = { onOpenReceipt(sale.id) },
                )
            }
        }
    }

    if (showPicker) {
        val startDate = com.uhmk.pos.core.time.DateRange.toLocalDate(state.range.from)
        val endDate = com.uhmk.pos.core.time.DateRange.toLocalDate(state.range.to)
        val pickerState = androidx.compose.material3.rememberDateRangePickerState(
            initialSelectedStartDateMillis = startDate.atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = endDate.atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = pickerState.selectedStartDateMillis
                    val end = pickerState.selectedEndDateMillis ?: start
                    if (start != null && end != null) {
                        onCustomRange(
                            Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(),
                            Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                    showPicker = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DateRangePicker(state = pickerState, showModeToggle = false)
        }
    }
}

@Composable
private fun SaleRow(
    sale: SaleEntity,
    currency: String,
    showProfit: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ReceiptLong,
                contentDescription = null,
                tint = when (sale.status) {
                    SaleStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                    SaleStatus.VOIDED -> MaterialTheme.colorScheme.error
                    SaleStatus.RETURNED -> MaterialTheme.colorScheme.tertiary
                },
            )
            Spacer(Modifier.height(0.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    "Receipt ${sale.receiptLabel}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (sale.status != SaleStatus.COMPLETED) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (sale.status == SaleStatus.VOIDED) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            sale.status.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(
                    buildString {
                        append(Clock.stamp(sale.soldAt)).append(" · ").append(sale.cashierName)
                        val type = OrderType.from(sale.orderType)
                        if (type != OrderType.UNSPECIFIED) append(" · ").append(type.label)
                        if (sale.orderLabel.isNotBlank()) append(" · ").append(sale.orderLabel)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showProfit) {
                    Text(
                        buildString {
                            append("Gross profit ").append(Money.format(sale.profitCentavos, currency))
                            if (sale.hasUnknownCost) append(" (+ cost not set)")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                Money.format(sale.netCentavos, currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
