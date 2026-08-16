package com.uhmk.pos.feature.sales

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.model.SaleStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScreen(
    state: ReceiptUiState,
    receiptText: String,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onVoid: () -> Unit,
    onReturn: (String) -> Unit,
    onBack: () -> Unit,
) {
    var confirmVoid by remember { mutableStateOf(false) }
    var confirmReturn by remember { mutableStateOf(false) }
    var returnReason by remember { mutableStateOf("") }
    val sale = state.sale
    val currency = state.settings.currencySymbol

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sale?.let { "Receipt ${it.sale.receiptLabel}" } ?: "Receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sale != null && state.session.isAdmin &&
                        sale.sale.status == SaleStatus.COMPLETED) {
                        IconButton(onClick = { confirmVoid = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Void sale")
                        }
                    }
                },
            )
        },
    ) { inner ->
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            sale == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("That sale is no longer available.")
            }

            else -> Column(
                Modifier
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // The receipt is monospaced and fixed-width, so it scrolls sideways rather than
                // reflowing — what you see is exactly what prints.
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            receiptText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                    OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                }

                if (state.session.isAdmin) {
                    Spacer(Modifier.height(20.dp))
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Owner view", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Line("Order status", sale.sale.status.label, bold = true)
                            Line("Restocking cost", Money.format(sale.sale.costCentavos, currency))
                            Line(
                                "Take-home profit",
                                Money.format(sale.sale.profitCentavos, currency),
                                bold = true,
                            )
                            if (sale.sale.hasUnknownCost) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "${Money.format(sale.sale.unknownCostCentavos, currency)} of this " +
                                        "sale came from items with no cost set, so their profit is " +
                                        "not counted above.",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (sale.sale.returnReason.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text("Return reason: ${sale.sale.returnReason}")
                            }
                            if (sale.sale.status == SaleStatus.COMPLETED) {
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = { confirmReturn = true },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Return order") }
                                    TextButton(
                                        onClick = { confirmVoid = true },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Void sale") }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (confirmVoid) {
        AlertDialog(
            onDismissRequest = { confirmVoid = false },
            title = { Text("Void this sale?") },
            text = {
                Text(
                    "The sale is removed from every report and its units go back into stock. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmVoid = false
                    onVoid()
                }) { Text("Void sale") }
            },
            dismissButton = { TextButton(onClick = { confirmVoid = false }) { Text("Cancel") } },
        )
    }

    if (confirmReturn) {
        AlertDialog(
            onDismissRequest = { confirmReturn = false },
            title = { Text("Return this order?") },
            text = {
                Column {
                    Text(
                        "This keeps the receipt in Return history, removes it from sales reports, " +
                            "and puts all tracked items back into stock."
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = returnReason,
                        onValueChange = { returnReason = it },
                        label = { Text("Reason (optional)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReturn = false
                    onReturn(returnReason)
                }) { Text("Return order") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReturn = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
