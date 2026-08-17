package com.uhmk.pos.feature.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.model.OrderType
import com.uhmk.pos.core.time.RangePreset
import com.uhmk.pos.core.ui.components.EmptyState
import com.uhmk.pos.core.ui.components.ItemThumbnail
import com.uhmk.pos.core.ui.components.SelectionDropdown
import com.uhmk.pos.core.ui.components.StatCard
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

private enum class ReportChartType(val label: String) {
    LINE("Line"), BAR("Bar"), PIE("Pie")
}

private enum class ReportChartMetric(val label: String) {
    SALES("Sales"), TAKE_HOME("Gross profit")
}

private enum class ReportChartGroup(val label: String) {
    DAYS("Days"), WEEKS("Weeks"), MONTHS("Months"), YEARS("Years")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    state: ReportsUiState,
    onPreset: (RangePreset) -> Unit,
    onCustomRange: (LocalDate, LocalDate) -> Unit,
    onView: (ReportView) -> Unit,
    onOpenSales: () -> Unit,
    onExportItems: () -> Unit,
    onExportCategories: () -> Unit,
    onExportSales: () -> Unit,
    onExportHistory: () -> Unit,
    onExportInventory: () -> Unit,
    contentPadding: PaddingValues,
) {
    val currency = state.settings.currencySymbol
    var showPicker by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
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
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(state.range.label)
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StatCard(
                    label = "Gross profit",
                    value = Money.format(state.totals.profit, currency),
                    caption = state.totals.marginPercent?.let { "%.1f%% margin".format(it) }
                        ?: "Nothing sold in this period",
                    emphasis = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = "Net sales",
                        value = Money.format(state.totals.net, currency),
                        caption = "what customers paid",
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Cost of goods",
                        value = Money.format(state.totals.cost, currency),
                        caption = "goes back to restock",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = "Sales",
                        value = state.totals.saleCount.toString(),
                        caption = "${state.totals.itemsSold} items",
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Discounts",
                        value = Money.format(state.totals.discount, currency),
                        caption = "given away",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (state.totals.hasUnknownCost) {
            item { UnknownCostBanner(state, currency) }
        }

        item {
            Card(
                onClick = onOpenSales,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Order history and receipts", fontWeight = FontWeight.Bold)
                        Text(
                            "Completed, voided, and returned orders with receipts",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (state.daily.isNotEmpty()) {
            item {
                ReportChartCard(state = state, currency = currency)
            }
        }

        item {
            SelectionDropdown(
                label = "Report breakdown",
                selected = state.view,
                options = ReportView.entries,
                optionLabel = { it.label },
                onSelected = onView,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when (state.view) {
            ReportView.ITEMS -> itemsSection(state, currency)
            ReportView.CATEGORIES -> categoriesSection(state, currency)
            ReportView.EMPLOYEES -> employeesSection(state, currency)
            ReportView.PAYMENTS -> paymentsSection(state, currency)
            ReportView.ORDER_TYPES -> orderTypesSection(state, currency)
            ReportView.TIERS -> tiersSection(state, currency)
        }

        item {
            Column(Modifier.padding(16.dp)) {
                Text("Export", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ExportButton("Profit by item", state.exporting, onExportItems)
                Spacer(Modifier.height(8.dp))
                ExportButton("Sales by category", state.exporting, onExportCategories)
                Spacer(Modifier.height(8.dp))
                ExportButton("Every sale line", state.exporting, onExportSales)
                Spacer(Modifier.height(8.dp))
                ExportButton("Orders and receipt history", state.exporting, onExportHistory)
                Spacer(Modifier.height(8.dp))
                ExportButton("Full inventory", state.exporting, onExportInventory)
            }
        }
    }

    if (showPicker) {
        val startDate = com.uhmk.pos.core.time.DateRange.toLocalDate(state.range.from)
        val endDate = com.uhmk.pos.core.time.DateRange.toLocalDate(state.range.to)
        val pickerState = rememberDateRangePickerState(
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
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DateRangePicker(state = pickerState, showModeToggle = false) }
    }
}

@Composable
private fun UnknownCostBanner(state: ReportsUiState, currency: String) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Profit is understated", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.totals.unknownQty} units sold have no cost on file, worth " +
                    "${Money.format(state.totals.unknownNet, currency)} in revenue. Their profit is " +
                    "left out of the figure above rather than guessed. Set their cost in Stock to " +
                    "fold them in.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsSection(
    state: ReportsUiState,
    currency: String,
) {
    if (state.breakdown.isEmpty()) {
        item {
            Spacer(Modifier.height(24.dp))
            EmptyState(
                title = "No sales yet",
                body = "Sales in this period break down here, item by item.",
            )
            Spacer(Modifier.height(24.dp))
        }
        return
    }

    items(state.breakdown, key = { it.itemId }) { row ->
        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            shape = RoundedCornerShape(15.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ItemThumbnail(
                        name = row.itemName,
                        imagePath = state.itemImages[row.itemId],
                        size = 54.dp,
                        corner = 12.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.itemName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${row.category} · ${row.qtySold} sold",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        Money.format(row.net, currency),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    ReportAmount(
                        label = "Net sales",
                        value = Money.format(row.net, currency),
                        modifier = Modifier.weight(1f),
                    )
                    ReportAmount(
                        label = "Cost of goods",
                        value = if (row.costKnown) Money.format(row.cost, currency) else "Cost missing",
                        modifier = Modifier.weight(1f),
                    )
                    ReportAmount(
                        label = "Gross profit",
                        value = if (row.costKnown) Money.format(row.profit, currency) else "Unknown",
                        modifier = Modifier.weight(1f),
                        highlight = row.costKnown,
                    )
                }
            }
        }
    }

    item {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("ITEM TOTAL", style = MaterialTheme.typography.labelSmall)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${state.breakdown.sumOf { it.qtySold }} units",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${Money.format(state.totals.net, currency)} sales · " +
                            "${Money.format(state.totals.profit, currency)} gross profit",
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportAmount(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.employeesSection(
    state: ReportsUiState,
    currency: String,
) {
    if (state.employees.isEmpty()) {
        item {
            Spacer(Modifier.height(24.dp))
            EmptyState(title = "No sales yet", body = "Sales by employee appear here.")
            Spacer(Modifier.height(24.dp))
        }
        return
    }
    items(state.employees, key = { it.cashierId }) { row ->
        val share = if (state.totals.net > 0) row.net.toFloat() / state.totals.net else 0f
        BreakdownCard(
            title = row.cashierName,
            value = Money.format(row.net, currency),
            caption = "${row.saleCount} receipts · ${row.itemsSold} units · " +
                "gross profit ${Money.format(row.profit, currency)}",
            share = share,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.paymentsSection(
    state: ReportsUiState,
    currency: String,
) {
    if (state.payments.isEmpty()) {
        item {
            Spacer(Modifier.height(24.dp))
            EmptyState(title = "No sales yet", body = "Cash, GCash, and other payments appear here.")
            Spacer(Modifier.height(24.dp))
        }
        return
    }
    items(state.payments, key = { it.paymentMethod }) { row ->
        val share = if (state.totals.net > 0) row.net.toFloat() / state.totals.net else 0f
        BreakdownCard(
            title = row.paymentMethod,
            value = Money.format(row.net, currency),
            caption = "${row.saleCount} receipts · ${row.itemsSold} units · " +
                "gross profit ${Money.format(row.profit, currency)}",
            share = share,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.orderTypesSection(
    state: ReportsUiState,
    currency: String,
) {
    if (state.orderTypes.isEmpty()) {
        item {
            Spacer(Modifier.height(24.dp))
            EmptyState(title = "No sales yet", body = "Dine-in and takeout totals appear here.")
            Spacer(Modifier.height(24.dp))
        }
        return
    }
    items(state.orderTypes, key = { it.orderType }) { row ->
        val share = if (state.totals.net > 0) row.net.toFloat() / state.totals.net else 0f
        BreakdownCard(
            title = OrderType.from(row.orderType).label,
            value = Money.format(row.net, currency),
            caption = "${row.saleCount} receipts · ${row.itemsSold} units · " +
                "gross profit ${Money.format(row.profit, currency)}",
            share = share,
        )
    }
}

@Composable
private fun BreakdownCard(
    title: String,
    value: String,
    caption: String,
    share: Float,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(value, fontWeight = FontWeight.Bold)
            }
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(7.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(share.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.categoriesSection(
    state: ReportsUiState,
    currency: String,
) {
    if (state.categories.isEmpty()) {
        item {
            Spacer(Modifier.height(24.dp))
            EmptyState(title = "No sales yet", body = "Category totals appear once you sell something.")
            Spacer(Modifier.height(24.dp))
        }
        return
    }
    items(state.categories, key = { it.category }) { row ->
        val share = if (state.totals.net > 0) row.net.toFloat() / state.totals.net else 0f
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.category, fontWeight = FontWeight.SemiBold)
                Text(Money.format(row.net, currency), fontWeight = FontWeight.Bold)
            }
            Text(
                "${row.qtySold} units · gross profit ${Money.format(row.profit, currency)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(share.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.tiersSection(
    state: ReportsUiState,
    currency: String,
) {
    if (state.tiers.isEmpty()) {
        item {
            Spacer(Modifier.height(24.dp))
            EmptyState(title = "No sales yet", body = "The student/regular split appears here.")
            Spacer(Modifier.height(24.dp))
        }
        return
    }
    items(state.tiers, key = { it.tier }) { row ->
        val label = if (row.tier == "STUDENT") state.settings.studentLabel else state.settings.regularLabel
        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, fontWeight = FontWeight.Bold)
                    Text(Money.format(row.net, currency), fontWeight = FontWeight.Bold)
                }
                Text(
                    "${row.qtySold} units · gross profit ${Money.format(row.profit, currency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExportButton(label: String, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Download, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (busy) "Preparing…" else label)
    }
}

@Composable
private fun ReportChartCard(state: ReportsUiState, currency: String) {
    var chartType by remember { mutableStateOf(ReportChartType.LINE) }
    var metric by remember { mutableStateOf(ReportChartMetric.SALES) }
    var group by remember { mutableStateOf(ReportChartGroup.DAYS) }
    val dailyPoints = state.daily.map { point ->
        point.day to if (metric == ReportChartMetric.SALES) point.net else point.profit
    }
    val points = groupReportPoints(dailyPoints, group)

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Sales chart", fontWeight = FontWeight.Bold)
                    Text(
                        if (chartType == ReportChartType.PIE) "Share by category"
                        else "${metric.label} by ${group.label.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    Money.format(
                        if (metric == ReportChartMetric.SALES) state.totals.net
                        else state.totals.profit,
                        currency,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionDropdown(
                    label = "Chart",
                    selected = chartType,
                    options = ReportChartType.entries,
                    optionLabel = { it.label },
                    onSelected = { chartType = it },
                    modifier = Modifier.weight(1f),
                )
                if (chartType != ReportChartType.PIE) {
                    SelectionDropdown(
                        label = "Value",
                        selected = metric,
                        options = ReportChartMetric.entries,
                        optionLabel = { it.label },
                        onSelected = { metric = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (chartType != ReportChartType.PIE) {
                Spacer(Modifier.height(8.dp))
                SelectionDropdown(
                    label = "Group dates by",
                    selected = group,
                    options = ReportChartGroup.entries,
                    optionLabel = { it.label },
                    onSelected = { group = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))
            when (chartType) {
                ReportChartType.LINE -> TrendLineChart(points, currency)
                ReportChartType.BAR -> ProfitBars(points, currency)
                ReportChartType.PIE -> CategoryPieChart(state, currency)
            }
        }
    }
}

private fun groupReportPoints(
    points: List<Pair<String, Long>>,
    group: ReportChartGroup,
): List<Pair<String, Long>> {
    if (group == ReportChartGroup.DAYS) return points
    val parsed = points.mapNotNull { (day, amount) ->
        runCatching { LocalDate.parse(day) }.getOrNull()?.let { it to amount }
    }
    return parsed.groupBy { (date, _) ->
        when (group) {
            ReportChartGroup.DAYS -> date.toString()
            ReportChartGroup.WEEKS -> date.with(
                TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)
            ).toString()
            ReportChartGroup.MONTHS -> YearMonth.from(date).toString()
            ReportChartGroup.YEARS -> date.year.toString()
        }
    }.toSortedMap().map { (key, rows) ->
        val label = when (group) {
            ReportChartGroup.WEEKS -> "Week ${LocalDate.parse(key).format(DateTimeFormatter.ofPattern("d MMM"))}"
            ReportChartGroup.MONTHS -> YearMonth.parse(key).format(DateTimeFormatter.ofPattern("MMM yyyy"))
            else -> key
        }
        label to rows.sumOf { it.second }
    }
}

@Composable
private fun TrendLineChart(points: List<Pair<String, Long>>, currency: String) {
    val values = points.takeLast(30)
    val highest = values.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp),
    ) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 12.dp.toPx()
        val chartHeight = bottom - top

        repeat(4) { index ->
            val y = top + chartHeight * index / 3f
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }

        if (values.isNotEmpty()) {
            val step = if (values.size == 1) 0f else (right - left) / (values.size - 1)
            val path = Path()
            values.forEachIndexed { index, (_, amount) ->
                val x = if (values.size == 1) (left + right) / 2f else left + step * index
                val fraction = (amount.coerceAtLeast(0L).toFloat() / highest).coerceIn(0f, 1f)
                val y = bottom - chartHeight * fraction
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (values.size > 1) {
                drawPath(path, lineColor, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
            }
            values.forEachIndexed { index, (_, amount) ->
                val x = if (values.size == 1) (left + right) / 2f else left + step * index
                val fraction = (amount.coerceAtLeast(0L).toFloat() / highest).coerceIn(0f, 1f)
                val y = bottom - chartHeight * fraction
                drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x, y))
            }
        }
    }
    ChartFooter(values, highest, currency)
}

@Composable
private fun ChartFooter(
    points: List<Pair<String, Long>>,
    peak: Long,
    currency: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            points.firstOrNull()?.first.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Peak ${Money.format(peak, currency)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            points.lastOrNull()?.first.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact daily bar chart for the selected metric. */
@Composable
private fun ProfitBars(points: List<Pair<String, Long>>, currency: String) {
    val max = points.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    Row(
        Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        points.takeLast(14).forEach { (day, profit) ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val fraction = (profit.toFloat() / max).coerceIn(0.02f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((90 * fraction).dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.height(4.dp))
                Text(day.takeLast(2), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    ChartFooter(points.takeLast(14), max, currency)
}

@Composable
private fun CategoryPieChart(state: ReportsUiState, currency: String) {
    val sorted = state.categories.filter { it.net > 0 }.sortedByDescending { it.net }
    val slices = if (sorted.size <= 5) {
        sorted.map { it.category to it.net }
    } else {
        sorted.take(5).map { it.category to it.net } +
            ("Other" to sorted.drop(5).sumOf { it.net })
    }
    val total = max(1L, slices.sumOf { it.second })
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    val holeColor = MaterialTheme.colorScheme.surfaceVariant

    if (slices.isEmpty()) {
        EmptyState(title = "No category sales", body = "Category shares appear after a sale.")
        return
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Canvas(Modifier.size(150.dp)) {
            var start = -90f
            slices.forEachIndexed { index, (_, amount) ->
                val sweep = amount * 360f / total
                drawArc(
                    color = colors[index % colors.size],
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = true,
                )
                start += sweep
            }
            drawCircle(
                color = holeColor,
                radius = size.minDimension * 0.24f,
                center = center,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            slices.forEachIndexed { index, (label, amount) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors[index % colors.size]),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Money.format(amount, currency),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
