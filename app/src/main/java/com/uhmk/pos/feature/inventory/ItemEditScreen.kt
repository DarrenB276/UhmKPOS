package com.uhmk.pos.feature.inventory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.ui.components.ItemThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditScreen(
    state: ItemEditUiState,
    onEdit: ((ItemForm) -> ItemForm) -> Unit,
    onPickImage: (android.net.Uri) -> Unit,
    onClearImage: () -> Unit,
    onDeriveCost: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val form = state.form
    val currency = state.currency
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onPickImage) }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New item" else "Edit item") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete item")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Box(Modifier.padding(16.dp)) {
                Button(
                    onClick = onSave,
                    enabled = form.isValid,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.isNew) "Add to inventory" else "Save changes") }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Photo ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.9f),
            ) {
                ItemThumbnail(
                    name = form.name.ifBlank { "New item" },
                    imagePath = form.imagePath,
                    modifier = Modifier.fillMaxSize(),
                    corner = 16.dp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (form.imagePath == null) "Add photo" else "Change photo")
                }
                if (form.imagePath != null) {
                    OutlinedButton(onClick = onClearImage) { Text("Remove") }
                }
            }
            Text(
                "No photo is fine — the item shows a coloured tile with its initials instead.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // ---- Identity ----
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> onEdit { it.copy(name = v) } },
                label = { Text("Item name") },
                isError = form.nameError != null,
                supportingText = form.nameError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.category,
                    onValueChange = { v -> onEdit { it.copy(category = v) } },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = form.sku,
                    onValueChange = { v -> onEdit { it.copy(sku = v) } },
                    label = { Text("SKU") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.knownCategories.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.knownCategories.forEach { cat ->
                        AssistChip(
                            onClick = { onEdit { it.copy(category = cat) } },
                            label = { Text(cat) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // ---- Box maths, mirroring the spreadsheet ----
            Text("Supply", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.boxCost,
                    onValueChange = { v -> onEdit { it.copy(boxCost = v.moneyChars()) } },
                    label = { Text("Price per box") },
                    prefix = { Text(currency) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = form.unitsPerBox,
                    onValueChange = { v -> onEdit { it.copy(unitsPerBox = v.filter(Char::isDigit)) } },
                    label = { Text("Units / box") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
            }
            OutlinedButton(onClick = onDeriveCost, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Calculate, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Work out unit cost from box price")
            }

            HorizontalDivider()

            // ---- Pricing ----
            Text("Pricing", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = form.cost,
                onValueChange = { v -> onEdit { it.copy(cost = v.moneyChars(), zeroCost = false) } },
                label = { Text("Unit cost / SRP") },
                prefix = { Text(currency) },
                supportingText = { Text("What you pay. This is capital, never counted as profit.") },
                singleLine = true,
                enabled = !form.zeroCost,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Zero-cost service", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Use for a cooking/service fee whose whole price is gross profit.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.zeroCost,
                    onCheckedChange = { checked ->
                        onEdit { it.copy(zeroCost = checked, cost = if (checked) "" else it.cost) }
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.student,
                    onValueChange = { v -> onEdit { it.copy(student = v.moneyChars()) } },
                    label = { Text("Student price") },
                    prefix = { Text(currency) },
                    isError = form.priceError != null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = form.regular,
                    onValueChange = { v -> onEdit { it.copy(regular = v.moneyChars()) } },
                    label = { Text("Regular price") },
                    prefix = { Text(currency) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                )
            }
            form.priceError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "Leave regular blank to match the student price.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Live profit preview ----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Profit preview", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (!form.costKnown) {
                        Text(
                            "Enter a unit cost above to see what this item earns. Until then the " +
                                "app reports its sales as revenue only, never as profit.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        PreviewRow(
                            "Profit per item (student)",
                            Money.format(form.studentProfit ?: 0, currency),
                        )
                        PreviewRow(
                            "Profit per item (regular)",
                            Money.format(form.regularProfit ?: 0, currency),
                        )
                        if (form.units > 1) {
                            PreviewRow(
                                "Profit per box (${form.units} units)",
                                Money.format(form.studentProfitPerBox ?: 0, currency),
                                bold = true,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ---- Stock ----
            Text("Stock", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.stock,
                    onValueChange = { v -> onEdit { it.copy(stock = v.filter(Char::isDigit)) } },
                    label = { Text("On hand") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = form.lowStockAt,
                    onValueChange = { v -> onEdit { it.copy(lowStockAt = v.filter(Char::isDigit)) } },
                    label = { Text("Warn at") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show on the sell screen", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Turn off to retire an item without deleting its sales history.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.active,
                    onCheckedChange = { v -> onEdit { it.copy(active = v) } },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${form.name}?") },
            text = {
                Text(
                    "Past sales keep their own copy of the name and price, so your reports stay " +
                        "correct. Only the catalogue entry is removed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PreviewRow(label: String, value: String, bold: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

private fun String.moneyChars(): String = filter { it.isDigit() || it == '.' }
