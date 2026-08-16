package com.uhmk.pos.feature.inventory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.ui.components.EmptyState
import com.uhmk.pos.core.ui.components.ItemThumbnail
import com.uhmk.pos.core.ui.components.SearchField
import com.uhmk.pos.core.ui.components.SelectionDropdown
import com.uhmk.pos.core.ui.components.StatCard

@Composable
fun InventoryScreen(
    state: InventoryUiState,
    onQuery: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onToggleLowStock: () -> Unit,
    onToggleNeedsCost: () -> Unit,
    onSort: (InventorySort) -> Unit,
    onSetCost: (String, String) -> Unit,
    onOpenItem: (String) -> Unit,
    onAddItem: () -> Unit,
    onOpenCategories: () -> Unit,
    contentPadding: PaddingValues,
) {
    val currency = state.settings.currencySymbol
    val f = state.filters
    var sortMenu by remember { mutableStateOf(false) }
    var costTarget by remember { mutableStateOf<ItemEntity?>(null) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        floatingActionButton = {
            if (state.session.isAdmin) {
                ExtendedFloatingActionButton(
                    onClick = onAddItem,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add item") },
                )
            }
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() + 88.dp,
            ),
        ) {
            item {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        label = "Items",
                        value = state.items.size.toString(),
                        caption = "${state.trackedCount} tracked",
                        modifier = Modifier.width(140.dp),
                    )
                    if (state.canSeeProfit) {
                        StatCard(
                            label = "Stock capital",
                            value = Money.format(state.stockCapital, currency),
                            caption = "known costs only",
                            modifier = Modifier.width(180.dp),
                        )
                        StatCard(
                            label = "Potential profit",
                            value = Money.format(state.potentialProfit, currency),
                            caption = "if all stock sells",
                            emphasis = true,
                            modifier = Modifier.width(190.dp),
                        )
                    }
                }
            }

            if (state.missingCostCount > 0 && state.session.isAdmin) {
                item { MissingCostCard(state.missingCostCount, f.needsCostOnly, onToggleNeedsCost) }
            }

            item {
                SearchField(
                    value = f.query,
                    onValueChange = onQuery,
                    placeholder = "Search name or SKU",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            AssistChip(
                                onClick = { sortMenu = true },
                                label = { Text(f.sort.label) },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                                },
                            )
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                InventorySort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            onSort(option)
                                            sortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        if (state.session.isAdmin) {
                            AssistChip(
                                onClick = onOpenCategories,
                                label = { Text("Manage categories") },
                                leadingIcon = { Icon(Icons.Default.Category, contentDescription = null) },
                            )
                        }
                        FilterChip(
                            selected = f.lowStockOnly,
                            onClick = onToggleLowStock,
                            label = { Text("Low stock (${state.lowStockCount})") },
                        )
                    }
                    SelectionDropdown(
                        label = "Category",
                        selected = f.category,
                        options = listOf<String?>(null) + state.categories,
                        optionLabel = { it ?: "All products" },
                        onSelected = onCategory,
                    )
                }
            }

            if (state.visible.isEmpty()) {
                item {
                    Spacer(Modifier.height(48.dp))
                    EmptyState(
                        title = "Nothing here",
                        body = if (state.items.isEmpty()) {
                            "Your catalogue is empty. Add your first item to get started."
                        } else {
                            "No items match those filters."
                        },
                    )
                }
            }

            items(state.visible, key = { it.id }) { item ->
                InventoryRow(
                    item = item,
                    currency = currency,
                    studentLabel = state.settings.studentLabel,
                    regularLabel = state.settings.regularLabel,
                    canSeeProfit = state.canSeeProfit,
                    canEdit = state.canEdit,
                    onClick = { if (state.canEdit) onOpenItem(item.id) },
                    onSetCost = { costTarget = item },
                )
            }
        }
    }

    costTarget?.let { target ->
        QuickCostDialog(
            item = target,
            currency = currency,
            onDismiss = { costTarget = null },
            onSave = { text ->
                onSetCost(target.id, text)
                costTarget = null
            },
        )
    }
}

@Composable
private fun MissingCostCard(count: Int, active: Boolean, onToggle: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "$count items have no cost yet",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Your catalogue import did not include costs, so profit for these items cannot be " +
                    "worked out. Sales still record normally — their revenue is just reported " +
                    "separately until you fill the cost in.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onToggle) {
                Text(if (active) "Show all items" else "Show only these")
            }
        }
    }
}

@Composable
private fun InventoryRow(
    item: ItemEntity,
    currency: String,
    studentLabel: String,
    regularLabel: String,
    canSeeProfit: Boolean,
    canEdit: Boolean,
    onClick: () -> Unit,
    onSetCost: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ItemThumbnail(item.name, item.imagePath, size = 56.dp)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(item.category)
                        if (item.sku.isNotBlank()) append(" · ").append(item.sku)
                        if (!item.trackStock) append(" · service")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "$studentLabel ${Money.format(item.studentCentavos, currency)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (item.hasTwoPrices) {
                        Text(
                            "$regularLabel ${Money.format(item.regularCentavos, currency)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val margin = item.studentProfit
                if (margin != null && canSeeProfit) {
                    Text(
                        "+${Money.formatAmount(margin)} per unit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                } else if (margin == null && canEdit) {
                    TextButton(
                        onClick = onSetCost,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) { Text("Set cost", style = MaterialTheme.typography.labelMedium) }
                } else if (margin == null && canSeeProfit) {
                    Text(
                        "cost not set",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (item.trackStock) {
                    Text(
                        item.stockQty.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isLowStock) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "in stock",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!item.active) {
                    Text(
                        "hidden",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickCostDialog(
    item: ItemEntity,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val entered = Money.parse(text)
    val margin = entered?.let { item.studentCentavos - it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cost of ${item.name}") },
        text = {
            Column {
                Text(
                    "What you pay your supplier for one unit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Unit cost") },
                    prefix = { Text(currency) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                if (margin != null) {
                    Text(
                        if (margin >= 0) {
                            "Profit would be ${Money.format(margin, currency)} per unit " +
                                "at the ${Money.format(item.studentCentavos, currency)} student price."
                        } else {
                            "That is above the selling price — every sale would lose " +
                                "${Money.format(-margin, currency)}."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (margin >= 0) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = entered != null && entered > 0) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
