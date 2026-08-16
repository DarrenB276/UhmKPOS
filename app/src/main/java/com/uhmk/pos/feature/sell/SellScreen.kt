package com.uhmk.pos.feature.sell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.prefs.CartPanelPosition
import com.uhmk.pos.core.prefs.ProductPage
import com.uhmk.pos.core.model.CartLine
import com.uhmk.pos.core.model.OrderType
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.ui.PosWindowSize
import com.uhmk.pos.core.ui.productTileMinWidth
import com.uhmk.pos.core.ui.rememberWindowSize
import com.uhmk.pos.core.ui.components.AutoShrinkText
import com.uhmk.pos.core.ui.components.ItemThumbnail
import com.uhmk.pos.core.ui.components.MoneyText
import com.uhmk.pos.core.ui.components.QuantityStepper
import com.uhmk.pos.core.ui.components.SearchField
import com.uhmk.pos.core.ui.components.SelectionDropdown
import com.uhmk.pos.core.ui.theme.MoneyStyleLarge
import com.uhmk.pos.core.time.Clock
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellScreen(
    state: SellUiState,
    onQuery: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onPage: (String) -> Unit,
    onSaleTier: (PriceTier) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onAddPage: (String, Set<String>) -> Unit,
    onUpdatePage: (String, String, Set<String>) -> Unit,
    onDeletePage: (String) -> Unit,
    onAdd: (ItemEntity) -> Unit,
    onQty: (String, Int) -> Unit,
    onTier: (String, PriceTier) -> Unit,
    onDiscount: (Long) -> Unit,
    onTendered: (Long) -> Unit,
    onPaymentMethod: (String) -> Unit,
    onOrderType: (OrderType) -> Unit,
    onOrderLabel: (String) -> Unit,
    onNote: (String) -> Unit,
    onClearCart: () -> Unit,
    onHoldTicket: (String) -> Unit,
    onLoadTicket: (String) -> Unit,
    onDeleteTicket: (String) -> Unit,
    onCheckout: () -> Unit,
    contentPadding: PaddingValues,
) {
    var showCart by remember { mutableStateOf(false) }
    var showPageManager by remember { mutableStateOf(false) }
    var longPressedItem by remember { mutableStateOf<ItemEntity?>(null) }
    var showTickets by remember { mutableStateOf(false) }
    var showHoldTicket by remember { mutableStateOf(false) }
    var pendingTicketId by remember { mutableStateOf<String?>(null) }
    val currency = state.settings.currencySymbol

    // Tablets get everything on one line; phones stack and fold instead.
    val wideHeader = rememberWindowSize().supportsTwoPane

    // The two selector rows cost about 112dp, which on a phone is most of a row of products.
    // They fold away as soon as the grid is scrolled down and come straight back on the way up.
    // Search stays pinned so there is always a way to find something without scrolling first.
    val gridState = rememberLazyGridState()
    var selectorsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(gridState) {
        var lastIndex = 0
        var lastOffset = 0
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val down = index > lastIndex || (index == lastIndex && offset > lastOffset + 8)
                val up = index < lastIndex || (index == lastIndex && offset < lastOffset - 8)
                if (down && index > 0) selectorsVisible = false
                if (up) selectorsVisible = true
                lastIndex = index
                lastOffset = offset
            }
    }

    // Docking only makes sense where there is width for it. On a phone the setting is ignored and
    // the pop-up sheet is used, because a docked panel would leave no usable product grid.
    val dockedSide = if (wideHeader) state.settings.cartPanelPosition else CartPanelPosition.POPUP
    val cartDocked = dockedSide != CartPanelPosition.POPUP

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        bottomBar = {
            // A docked panel already shows the running total, so the bar would just repeat it.
            if (!cartDocked && !state.cart.isEmpty) {
                CartBar(
                    itemCount = state.cart.itemCount,
                    total = state.cart.net,
                    profit = state.cart.profit,
                    currency = currency,
                    showProfit = state.canSeeProfit,
                    onClick = { showCart = true },
                )
            }
        },
    ) { inner ->
        val browser: @Composable (Modifier) -> Unit = { browserModifier ->
        Column(
            browserModifier
                .padding(top = contentPadding.calculateTopPadding())
                .padding(bottom = inner.calculateBottomPadding())
        ) {
            val pageSelector: @Composable (Modifier) -> Unit = { mod ->
                SelectionDropdown(
                    label = "Product page",
                    selected = state.pageOptions.firstOrNull { it.id == state.selectedPageId }
                        ?: state.pageOptions.first(),
                    options = state.pageOptions,
                    optionLabel = { it.name },
                    onSelected = { onPage(it.id) },
                    modifier = mod,
                )
            }
            val categorySelector: @Composable (Modifier) -> Unit = { mod ->
                SelectionDropdown(
                    label = "Category",
                    selected = state.category,
                    options = listOf<String?>(null) + state.categories,
                    optionLabel = { it ?: "All products" },
                    onSelected = onCategory,
                    modifier = mod,
                )
            }
            val tierSelector: @Composable (Modifier) -> Unit = { mod ->
                SelectionDropdown(
                    label = "Price",
                    selected = state.selectedTier,
                    options = listOf(PriceTier.REGULAR, PriceTier.STUDENT),
                    optionLabel = {
                        if (it == PriceTier.REGULAR) state.settings.regularLabel
                        else state.settings.studentLabel
                    },
                    onSelected = onSaleTier,
                    modifier = mod,
                )
            }
            val pageEditButton: @Composable () -> Unit = {
                IconButton(onClick = { showPageManager = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Manage product pages")
                }
            }
            val ticketsButton: @Composable () -> Unit = {
                OutlinedButton(onClick = { showTickets = true }) {
                    Icon(Icons.Default.ConfirmationNumber, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Tickets ${state.heldTickets.size}")
                }
            }

            if (wideHeader) {
                // A tablet has width to spare and height to save, so the search and all three
                // selectors sit on one line instead of stacking into three full-width rows.
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchField(
                        value = state.query,
                        onValueChange = onQuery,
                        modifier = Modifier.weight(1.5f),
                    )
                    pageSelector(Modifier.weight(1f))
                    categorySelector(Modifier.weight(1f))
                    tierSelector(Modifier.weight(0.9f))
                    pageEditButton()
                    ticketsButton()
                }
            } else {
                SearchField(
                    value = state.query,
                    onValueChange = onQuery,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                AnimatedVisibility(visible = selectorsVisible) {
                    Column {
                        Row(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            pageSelector(Modifier.weight(1f))
                            pageEditButton()
                            ticketsButton()
                        }
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            categorySelector(Modifier.weight(1f))
                            tierSelector(Modifier.weight(1f))
                        }
                    }
                }
            }

            LazyVerticalGrid(
                // Narrower tiles on a phone mean three columns instead of two, which roughly
                // doubles how much of the catalogue is on screen at once.
                columns = GridCells.Adaptive(minSize = productTileMinWidth()),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.visibleItems, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        currency = currency,
                        inCartQty = state.cart.lineFor(item.id)?.qty ?: 0,
                        showProfit = state.canSeeProfit,
                        tier = state.selectedTier,
                        pinned = item.id in state.pinnedItemIds,
                        immersive = state.settings.immersiveProductCards,
                        onClick = { onAdd(item) },
                        onLongClick = { longPressedItem = item },
                    )
                }
            }
        }
        }

        val dockedPanel: @Composable () -> Unit = {
            CartPanel(
                state = state,
                modifier = Modifier
                    .width(360.dp)
                    .padding(top = contentPadding.calculateTopPadding())
                    .padding(bottom = inner.calculateBottomPadding()),
                onQty = onQty,
                onTier = onTier,
                onDiscount = onDiscount,
                onTendered = onTendered,
                onPaymentMethod = onPaymentMethod,
                onOrderType = onOrderType,
                onOrderLabel = onOrderLabel,
                onNote = onNote,
                onClearCart = onClearCart,
                onHoldTicket = { showHoldTicket = true },
                onCheckout = onCheckout,
            )
        }

        if (cartDocked) {
            Row(Modifier.fillMaxSize()) {
                if (dockedSide == CartPanelPosition.LEFT) {
                    dockedPanel()
                    VerticalDivider()
                }
                browser(Modifier.weight(1f))
                if (dockedSide == CartPanelPosition.RIGHT) {
                    VerticalDivider()
                    dockedPanel()
                }
            }
        } else {
            browser(Modifier.fillMaxSize())
        }
    }

    if (showCart && !cartDocked) {
        ModalBottomSheet(
            onDismissRequest = { showCart = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            CartSheet(
                state = state,
                onQty = onQty,
                onTier = onTier,
                onDiscount = onDiscount,
                onTendered = onTendered,
                onPaymentMethod = onPaymentMethod,
                onOrderType = onOrderType,
                onOrderLabel = onOrderLabel,
                onNote = onNote,
                onClearCart = {
                    onClearCart()
                    showCart = false
                },
                onHoldTicket = { showHoldTicket = true },
                onCheckout = {
                    onCheckout()
                    showCart = false
                },
            )
        }
    }

    if (showTickets) {
        HeldTicketsDialog(
            state = state,
            onOpen = {
                if (state.cart.isEmpty || state.activeTicketId == it) {
                    onLoadTicket(it)
                    showTickets = false
                    showCart = true
                } else {
                    pendingTicketId = it
                }
            },
            onDelete = onDeleteTicket,
            onDismiss = { showTickets = false },
        )
    }

    pendingTicketId?.let { ticketId ->
        AlertDialog(
            onDismissRequest = { pendingTicketId = null },
            title = { Text("Open another ticket?") },
            text = { Text("The current sale has not been held. Clear it and open the selected ticket?") },
            confirmButton = {
                TextButton(onClick = {
                    onClearCart()
                    onLoadTicket(ticketId)
                    pendingTicketId = null
                    showTickets = false
                    showCart = true
                }) { Text("Clear and open") }
            },
            dismissButton = { TextButton(onClick = { pendingTicketId = null }) { Text("Cancel") } },
        )
    }

    if (showHoldTicket) {
        HoldTicketDialog(
            initialName = state.cart.orderLabel,
            editing = state.activeTicketId != null,
            onSave = {
                onHoldTicket(it)
                showHoldTicket = false
                showCart = false
            },
            onDismiss = { showHoldTicket = false },
        )
    }

    longPressedItem?.let { item ->
        val pinned = item.id in state.pinnedItemIds
        AlertDialog(
            onDismissRequest = { longPressedItem = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, modifier = Modifier.weight(1f), maxLines = 2)
                    IconButton(onClick = { longPressedItem = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
            },
            text = { Text(if (pinned) "Remove this product from the top?" else "Keep this product at the top for quick access?") },
            confirmButton = {
                TextButton(onClick = {
                    onPin(item.id, !pinned)
                    longPressedItem = null
                }) {
                    Icon(Icons.Default.PushPin, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (pinned) "Unpin" else "Pin")
                }
            },
            dismissButton = {
                TextButton(onClick = { longPressedItem = null }) { Text("Close") }
            },
        )
    }

    if (showPageManager) {
        ProductPagesDialog(
            pages = state.pages,
            items = state.allItems,
            onAdd = onAddPage,
            onUpdate = onUpdatePage,
            onDelete = onDeletePage,
            onDismiss = { showPageManager = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(
    item: ItemEntity,
    currency: String,
    inCartQty: Int,
    showProfit: Boolean,
    tier: PriceTier,
    pinned: Boolean,
    immersive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val usePhotoBackground = immersive && !item.imagePath.isNullOrBlank() && File(item.imagePath).exists()
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            if (usePhotoBackground) {
                // The photo fills the whole tile and the text sits on a gradient that fades from
                // clear at the top to dark at the bottom. A flat 78%-opaque panel was covering the
                // lower third of every product outright, which is what made the photo feel hidden.
                ItemThumbnail(
                    name = item.name,
                    imagePath = item.imagePath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.92f),
                    corner = 0.dp,
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                // Clear across the top half so the product stays fully visible,
                                // deepening only where the text actually sits.
                                0.00f to Color.Transparent,
                                0.45f to Color.Black.copy(alpha = 0.10f),
                                0.70f to Color.Black.copy(alpha = 0.55f),
                                1.00f to Color.Black.copy(alpha = 0.82f),
                            )
                        )
                )
                ProductDetails(
                    item = item,
                    currency = currency,
                    showProfit = showProfit,
                    tier = tier,
                    onPhoto = true,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            } else {
                Column {
                    ItemThumbnail(
                        name = item.name,
                        imagePath = item.imagePath,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.35f),
                        corner = 0.dp,
                    )
                    ProductDetails(
                        item = item,
                        currency = currency,
                        showProfit = showProfit,
                        tier = tier,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (inCartQty > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("$inCartQty", fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (pinned) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.padding(6.dp).size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductDetails(
    item: ItemEntity,
    currency: String,
    showProfit: Boolean,
    tier: PriceTier,
    modifier: Modifier = Modifier,
    /** Sitting on a photo: the theme colours have no contrast guarantee, so use light ones. */
    onPhoto: Boolean = false,
) {
    val nameColor = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurface
    val priceColor = if (onPhoto) Color.White else MaterialTheme.colorScheme.primary
    val mutedColor = if (onPhoto) Color.White.copy(alpha = 0.82f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    val goodColor = if (onPhoto) Color(0xFF8CE8B0) else MaterialTheme.colorScheme.tertiary
    val badColor = if (onPhoto) Color(0xFFFFAFA5) else MaterialTheme.colorScheme.error

    Column(modifier) {
        Text(
            item.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = nameColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Was a hard 40dp, which reserved two lines even for names like "Rice". The grid row
            // still aligns its tiles, so short names now give their spare height back to the grid.
            modifier = Modifier.heightIn(min = 20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Money.format(item.priceFor(tier), currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = priceColor,
            )
            Spacer(Modifier.width(6.dp))
            if (showProfit) {
                val margin = item.profitFor(tier)
                Text(
                    if (margin != null) "+" + Money.formatAmount(margin) else "cost?",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (margin != null) goodColor else badColor,
                )
            }
        }
        Text(
            when {
                !item.trackStock -> "Service · no stock limit"
                item.stockQty <= 0 -> "Out of stock"
                else -> "${item.stockQty} left"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !item.trackStock -> mutedColor
                item.stockQty <= 0 || item.isLowStock -> badColor
                else -> mutedColor
            },
        )
    }
}

@Composable
private fun HeldTicketsDialog(
    state: SellUiState,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Held tickets") },
        text = {
            if (state.heldTickets.isEmpty()) {
                Text("No orders are set aside. Use Hold ticket from Current sale.")
            } else {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(state.heldTickets, key = { it.ticket.id }) { held ->
                        val qty = held.lines.sumOf { it.qty }
                        val gross = held.lines.sumOf { line ->
                            val item = state.allItems.firstOrNull { it.id == line.itemId }
                            (item?.priceFor(line.tier) ?: 0L) * line.qty
                        }
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(held.ticket.title, fontWeight = FontWeight.Bold)
                                    Text(
                                        "$qty item${if (qty == 1) "" else "s"} · " +
                                            Money.format((gross - held.ticket.discountCentavos).coerceAtLeast(0), state.settings.currencySymbol),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "${held.ticket.ownerName} · ${Clock.stamp(held.ticket.updatedAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onOpen(held.ticket.id) }) { Text("Open") }
                                IconButton(onClick = { onDelete(held.ticket.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete ticket")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun HoldTicketDialog(
    initialName: String,
    editing: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName, editing) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Update held ticket" else "Set order aside") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(50) },
                label = { Text("Ticket name (optional)") },
                supportingText = { Text("Example: Table 3 or Ana") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text(if (editing) "Update ticket" else "Hold ticket")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProductPagesDialog(
    pages: List<ProductPage>,
    items: List<ItemEntity>,
    onAdd: (String, Set<String>) -> Unit,
    onUpdate: (String, String, Set<String>) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var pageName by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var productQuery by remember { mutableStateOf("") }

    fun edit(page: ProductPage?) {
        editingId = page?.id ?: ""
        pageName = page?.name.orEmpty()
        selectedIds = page?.itemIds.orEmpty()
        productQuery = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (editingId == null) "Product pages" else if (editingId!!.isBlank()) "New page" else "Edit page")
        },
        text = {
            if (editingId == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Main", fontWeight = FontWeight.Bold)
                            Text("Always shows the complete catalogue", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    pages.forEach { page ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(page.name, fontWeight = FontWeight.SemiBold)
                                Text("${page.itemIds.size} products", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { edit(page) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit ${page.name}")
                            }
                            IconButton(onClick = { onDelete(page.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete ${page.name}")
                            }
                        }
                        HorizontalDivider()
                    }
                    OutlinedButton(onClick = { edit(null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add product page")
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pageName,
                        onValueChange = { pageName = it.take(40) },
                        label = { Text("Page name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SearchField(
                        value = productQuery,
                        onValueChange = { productQuery = it },
                        placeholder = "Find products for this page",
                    )
                    Text("${selectedIds.size} selected", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(
                            items.filter { productQuery.isBlank() || it.name.contains(productQuery, true) },
                            key = { it.id },
                        ) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (item.id in selectedIds) selectedIds - item.id
                                        else selectedIds + item.id
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = item.id in selectedIds,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                                    },
                                )
                                Text(item.name, modifier = Modifier.weight(1f), maxLines = 2)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editingId == null) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(
                    onClick = {
                        val id = editingId.orEmpty()
                        if (id.isBlank()) onAdd(pageName, selectedIds)
                        else onUpdate(id, pageName, selectedIds)
                        editingId = null
                    },
                    enabled = pageName.isNotBlank(),
                ) { Text("Save") }
            }
        },
        dismissButton = {
            if (editingId != null) {
                TextButton(onClick = { editingId = null }) { Text("Back") }
            }
        },
    )
}

@Composable
private fun CartBar(
    itemCount: Int,
    total: Long,
    profit: Long,
    currency: String,
    showProfit: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            // Sits directly above the navigation bar, which already handles the system inset.
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$itemCount item${if (itemCount == 1) "" else "s"}", fontWeight = FontWeight.SemiBold)
                if (showProfit) {
                    Text(
                        "Take-home " + Money.format(profit, currency),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            AutoShrinkText(
                text = Money.format(total, currency),
                style = MoneyStyleLarge,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun CartSheet(
    state: SellUiState,
    onQty: (String, Int) -> Unit,
    onTier: (String, PriceTier) -> Unit,
    onDiscount: (Long) -> Unit,
    onTendered: (Long) -> Unit,
    onPaymentMethod: (String) -> Unit,
    onOrderType: (OrderType) -> Unit,
    onOrderLabel: (String) -> Unit,
    onNote: (String) -> Unit,
    onClearCart: () -> Unit,
    onHoldTicket: () -> Unit,
    onCheckout: () -> Unit,
) {
    val currency = state.settings.currencySymbol
    val twoPane = rememberWindowSize().supportsTwoPane

    var discountText by remember(state.cart.discount) {
        mutableStateOf(if (state.cart.discount > 0) Money.formatAmount(state.cart.discount) else "")
    }
    var tenderedText by remember(state.cart.tendered) {
        mutableStateOf(if (state.cart.tendered > 0) Money.formatAmount(state.cart.tendered) else "")
    }
    var noteText by remember(state.cart.note) { mutableStateOf(state.cart.note) }
    var orderLabelText by remember(state.cart.orderLabel) { mutableStateOf(state.cart.orderLabel) }

    // A tablet has room for the order and the payment side by side, so nothing needs to fold.
    // A phone does not: with every field pinned there was no height left for the order itself,
    // so the fields collapse and the order keeps the screen.
    var detailsExpanded by remember(twoPane) { mutableStateOf(twoPane) }
    val listScroll = rememberScrollState()

    LaunchedEffect(listScroll, twoPane) {
        if (twoPane) return@LaunchedEffect
        var last = 0
        snapshotFlow { listScroll.value }.collect { value ->
            // Reading back through a long order folds the fields out of the way automatically.
            if (value > last + 8) detailsExpanded = false
            last = value
        }
    }

    val paymentFields: @Composable () -> Unit = {
        PaymentFields(
            state = state,
            currency = currency,
            discountText = discountText,
            tenderedText = tenderedText,
            noteText = noteText,
            orderLabelText = orderLabelText,
            onDiscountText = { discountText = it; onDiscount(Money.parse(it) ?: 0L) },
            onTenderedText = { tenderedText = it; onTendered(Money.parse(it) ?: 0L) },
            onExactCash = {
                tenderedText = Money.formatAmount(state.cart.net)
                onTendered(state.cart.net)
            },
            onNoteText = { noteText = it; onNote(it) },
            onOrderLabelText = { orderLabelText = it; onOrderLabel(it) },
            onPaymentMethod = onPaymentMethod,
            onOrderType = onOrderType,
        )
    }

    val actions: @Composable () -> Unit = {
        CartActions(state = state, onClearCart = onClearCart, onHoldTicket = onHoldTicket, onCheckout = onCheckout)
    }

    if (twoPane) {
        Row(
            Modifier
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Column(Modifier.weight(1.1f)) {
                CartHeader(state, onTier)
                Spacer(Modifier.height(8.dp))
                OrderLines(state, currency, listScroll, Modifier.weight(1f), onQty, onTier)
            }
            Spacer(Modifier.width(16.dp))
            VerticalDivider()
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))
                paymentFields()
                Spacer(Modifier.height(10.dp))
                TotalsBlock(state, currency, collapsible = false, expanded = true, onToggle = {})
                Spacer(Modifier.height(16.dp))
                actions()
                Spacer(Modifier.height(20.dp))
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            CartHeader(state, onTier)
            Spacer(Modifier.height(8.dp))
            OrderLines(state, currency, listScroll, Modifier.weight(1f), onQty, onTier)

            HorizontalDivider()
            AnimatedVisibility(visible = detailsExpanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    paymentFields()
                }
            }
            TotalsBlock(
                state = state,
                currency = currency,
                collapsible = true,
                expanded = detailsExpanded,
                onToggle = { detailsExpanded = !detailsExpanded },
            )
            Spacer(Modifier.height(12.dp))
            actions()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The current sale as a permanently docked column, for large screens.
 *
 * Unlike the pop-up sheet nothing folds here — the panel is always on screen, so the order and the
 * payment fields can both stay visible the way a counter terminal expects.
 */
@Composable
private fun CartPanel(
    state: SellUiState,
    modifier: Modifier = Modifier,
    onQty: (String, Int) -> Unit,
    onTier: (String, PriceTier) -> Unit,
    onDiscount: (Long) -> Unit,
    onTendered: (Long) -> Unit,
    onPaymentMethod: (String) -> Unit,
    onOrderType: (OrderType) -> Unit,
    onOrderLabel: (String) -> Unit,
    onNote: (String) -> Unit,
    onClearCart: () -> Unit,
    onHoldTicket: () -> Unit,
    onCheckout: () -> Unit,
) {
    val currency = state.settings.currencySymbol
    var discountText by remember(state.cart.discount) {
        mutableStateOf(if (state.cart.discount > 0) Money.formatAmount(state.cart.discount) else "")
    }
    var tenderedText by remember(state.cart.tendered) {
        mutableStateOf(if (state.cart.tendered > 0) Money.formatAmount(state.cart.tendered) else "")
    }
    var noteText by remember(state.cart.note) { mutableStateOf(state.cart.note) }
    var orderLabelText by remember(state.cart.orderLabel) { mutableStateOf(state.cart.orderLabel) }

    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        CartHeader(state, onTier)
        Spacer(Modifier.height(8.dp))

        if (state.cart.isEmpty) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Tap a product to start the order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            OrderLines(state, currency, rememberScrollState(), Modifier.weight(1f), onQty, onTier)
        }

        HorizontalDivider()
        Column(
            Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            PaymentFields(
                state = state,
                currency = currency,
                discountText = discountText,
                tenderedText = tenderedText,
                noteText = noteText,
                orderLabelText = orderLabelText,
                onDiscountText = { discountText = it; onDiscount(Money.parse(it) ?: 0L) },
                onTenderedText = { tenderedText = it; onTendered(Money.parse(it) ?: 0L) },
                onExactCash = {
                    tenderedText = Money.formatAmount(state.cart.net)
                    onTendered(state.cart.net)
                },
                onNoteText = { noteText = it; onNote(it) },
                onOrderLabelText = { orderLabelText = it; onOrderLabel(it) },
                onPaymentMethod = onPaymentMethod,
                onOrderType = onOrderType,
            )
            Spacer(Modifier.height(8.dp))
            TotalsBlock(state, currency, collapsible = false, expanded = true, onToggle = {})
            Spacer(Modifier.height(12.dp))
            CartActions(state, onClearCart, onHoldTicket, onCheckout)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CartHeader(state: SellUiState, onTier: (String, PriceTier) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Current sale", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1.35f)) {
            listOf(PriceTier.REGULAR, PriceTier.STUDENT).forEachIndexed { index, tier ->
                SegmentedButton(
                    selected = state.cart.lines.all { it.tier == tier },
                    onClick = { state.cart.lines.forEach { onTier(it.item.id, tier) } },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) {
                    Text(
                        "All ${if (tier == PriceTier.REGULAR) state.settings.regularLabel else state.settings.studentLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderLines(
    state: SellUiState,
    currency: String,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    onQty: (String, Int) -> Unit,
    onTier: (String, PriceTier) -> Unit,
) {
    Column(modifier.verticalScroll(scrollState)) {
        state.cart.lines.forEach { line ->
            CartLineRow(
                line = line,
                currency = currency,
                studentLabel = state.settings.studentLabel,
                regularLabel = state.settings.regularLabel,
                onQty = { onQty(line.item.id, it) },
                onTier = { onTier(line.item.id, it) },
            )
            HorizontalDivider()
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PaymentFields(
    state: SellUiState,
    currency: String,
    discountText: String,
    tenderedText: String,
    noteText: String,
    orderLabelText: String,
    onDiscountText: (String) -> Unit,
    onTenderedText: (String) -> Unit,
    onExactCash: () -> Unit,
    onNoteText: (String) -> Unit,
    onOrderLabelText: (String) -> Unit,
    onPaymentMethod: (String) -> Unit,
    onOrderType: (OrderType) -> Unit,
) {
    val change = state.cart.tendered - state.cart.net

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            listOf(OrderType.DINE_IN, OrderType.TAKEOUT).forEachIndexed { index, type ->
                SegmentedButton(
                    selected = state.cart.orderType == type,
                    onClick = { onOrderType(type) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(type.label, style = MaterialTheme.typography.labelSmall) }
            }
        }
        OutlinedTextField(
            value = orderLabelText,
            onValueChange = onOrderLabelText,
            label = { Text(if (state.cart.orderType == OrderType.DINE_IN) "Table/guest" else "Customer") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(6.dp))
    val paymentOptions = (state.settings.paymentMethods + state.cart.paymentMethod)
        .filter(String::isNotBlank).distinctBy(String::lowercase)
    SelectionDropdown(
        label = "Payment method",
        selected = state.cart.paymentMethod,
        options = paymentOptions,
        optionLabel = { it },
        onSelected = onPaymentMethod,
        modifier = Modifier.fillMaxWidth(),
    )

    if (state.cart.paymentMethod == "Cash") {
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = tenderedText,
                onValueChange = { onTenderedText(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Cash received") },
                prefix = { Text(currency) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            TextButton(onClick = onExactCash) { Text("Exact") }
        }
        if (tenderedText.isNotBlank()) {
            Text(
                if (change >= 0) "Change ${Money.format(change, currency)}"
                else "Short by ${Money.format(-change, currency)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (change >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = discountText,
            onValueChange = { onDiscountText(it.filter { c -> c.isDigit() || c == '.' }) },
            label = { Text("Discount") },
            prefix = { Text(currency) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedTextField(
            value = noteText,
            onValueChange = onNoteText,
            label = { Text("Order note") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}

/**
 * The totals, doubling as the handle for the payment fields on a phone.
 *
 * Tapping anywhere on the block — the chevron or any of the Subtotal / Total / Take-home rows —
 * folds the payment fields back out, so the control is wherever the cashier's eye already is.
 */
@Composable
private fun TotalsBlock(
    state: SellUiState,
    currency: String,
    collapsible: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "totalsArrow")

    Column(
        Modifier
            .fillMaxWidth()
            .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
            .animateContentSize()
            .padding(top = 6.dp)
    ) {
        if (collapsible) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) "Payment details" else "Tap to edit payment",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // When folded, the two things a cashier still has to see stay visible.
                if (!expanded) {
                    Text(
                        buildString {
                            append(state.cart.paymentMethod)
                            if (state.cart.paymentMethod == "Cash" && state.cart.tendered > 0) {
                                append(" · change ")
                                append(Money.format(state.cart.tendered - state.cart.net, currency))
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide payment fields" else "Show payment fields",
                    modifier = Modifier.rotate(arrow),
                )
            }
        }

        SummaryRow("Subtotal", state.cart.gross, currency)
        if (state.cart.effectiveDiscount > 0) {
            SummaryRow("Discount", -state.cart.effectiveDiscount, currency)
        }
        SummaryRow("Total", state.cart.net, currency, bold = true)
        if (state.canSeeProfit) {
            SummaryRow("Restocking cost", state.cart.cost, currency, muted = true)
            SummaryRow("Take-home profit", state.cart.profit, currency, bold = true, highlight = true)
        }
    }
}

@Composable
private fun CartActions(
    state: SellUiState,
    onClearCart: () -> Unit,
    onHoldTicket: () -> Unit,
    onCheckout: () -> Unit,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClearCart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Clear")
            }
            OutlinedButton(onClick = onHoldTicket, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ConfirmationNumber, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.activeTicketId == null) "Hold" else "Update")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onCheckout,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.checkingOut && !state.cart.isEmpty && state.cart.canCheckout,
        ) {
            Text(
                when {
                    state.checkingOut -> "Saving…"
                    state.cart.paymentMethod == "Cash" && !state.cart.canCheckout -> "Enter cash received"
                    else -> "Complete sale"
                }
            )
        }
    }
}

@Composable
private fun CartLineRow(
    line: CartLine,
    currency: String,
    studentLabel: String,
    regularLabel: String,
    onQty: (Int) -> Unit,
    onTier: (PriceTier) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ItemThumbnail(line.item.name, line.item.imagePath, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    line.item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Money.format(line.unitPrice, currency) + " each",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoneyText(line.gross, currency)
            IconButton(onClick = { onQty(0) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${line.item.name}")
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = line.tier == PriceTier.STUDENT,
                    onClick = { onTier(PriceTier.STUDENT) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(studentLabel, style = MaterialTheme.typography.labelSmall) }
                SegmentedButton(
                    selected = line.tier == PriceTier.REGULAR,
                    onClick = { onTier(PriceTier.REGULAR) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(regularLabel, style = MaterialTheme.typography.labelSmall) }
            }
            QuantityStepper(qty = line.qty, onChange = onQty)
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    amount: Long,
    currency: String,
    bold: Boolean = false,
    muted: Boolean = false,
    highlight: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (highlight) Modifier
                    .padding(vertical = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                else Modifier.padding(vertical = 3.dp)
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            Money.format(amount, currency),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
