package com.uhmk.pos.feature.inventory

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.uhmk.pos.core.db.CategoryCount
import com.uhmk.pos.core.money.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    state: InventoryUiState,
    onRename: (String, String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf<CategoryCount?>(null) }
    val currency = state.settings.currencySymbol

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding(),
                bottom = 24.dp,
            )
        ) {
            item {
                Text(
                    "Categories come from your catalogue import. Renaming one moves every item in " +
                        "it at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            items(state.categoryCounts, key = { it.category }) { row ->
                val itemsIn = state.items.filter { it.category == row.category }
                val stockValue = itemsIn.filter { it.costKnown }.sumOf { it.stockQty * it.costCentavos }
                val missingCost = itemsIn.count { !it.costKnown }

                Card(
                    onClick = { onOpenCategory(row.category) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.category.ifBlank { "Uncategorised" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${row.itemCount} items · stock worth ${Money.format(stockValue, currency)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (missingCost > 0) {
                                Text(
                                    "$missingCost need a cost",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        IconButton(onClick = { renaming = row }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename ${row.category}")
                        }
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        var text by remember(target) { mutableStateOf(target.category) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename category") },
            text = {
                Column {
                    Text(
                        "All ${target.itemCount} items in \"${target.category}\" will move to the new name.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Category name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(target.category, text)
                        renaming = null
                    },
                    enabled = text.isNotBlank() && text != target.category,
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
