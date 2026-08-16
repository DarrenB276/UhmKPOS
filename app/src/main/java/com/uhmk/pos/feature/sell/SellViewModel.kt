package com.uhmk.pos.feature.sell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.db.TicketWithLines
import com.uhmk.pos.core.model.Cart
import com.uhmk.pos.core.model.CartLine
import com.uhmk.pos.core.model.OrderType
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.prefs.Session
import com.uhmk.pos.core.prefs.SessionStore
import com.uhmk.pos.core.prefs.ProductPage
import com.uhmk.pos.core.prefs.SalesLayout
import com.uhmk.pos.core.prefs.SalesLayoutStore
import com.uhmk.pos.core.prefs.SettingsStore
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.repo.ItemRepository
import com.uhmk.pos.core.repo.SaleRepository
import com.uhmk.pos.core.repo.TicketRepository
import com.uhmk.pos.core.sync.SyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SellUiState(
    val allItems: List<ItemEntity> = emptyList(),
    val categories: List<String> = emptyList(),
    val query: String = "",
    val category: String? = null,
    val pages: List<ProductPage> = emptyList(),
    val selectedPageId: String = MAIN_PAGE_ID,
    val selectedTier: PriceTier = PriceTier.REGULAR,
    val pinnedItemIds: Set<String> = emptySet(),
    val cart: Cart = Cart(),
    val settings: StoreSettings = StoreSettings(),
    val session: Session = Session(),
    val checkingOut: Boolean = false,
    val heldTickets: List<TicketWithLines> = emptyList(),
    val activeTicketId: String? = null,
) {
    val visibleItems: List<ItemEntity>
        get() {
            val customPage = pages.firstOrNull { it.id == selectedPageId }
            return allItems.filter { item ->
                (customPage == null || item.id in customPage.itemIds) &&
                    (category == null || item.category == category) &&
                    (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true))
            }.sortedWith(
                compareByDescending<ItemEntity> { it.id in pinnedItemIds }
                    .thenBy { it.sortIndex }
                    .thenBy { it.name.lowercase() }
            )
        }

    val pageOptions: List<ProductPage>
        get() = listOf(ProductPage(MAIN_PAGE_ID, "Main", emptySet())) + pages

    /** Admins always see margins; staff only when the owner has switched it on. */
    val canSeeProfit: Boolean get() = session.isAdmin || settings.showProfitToStaff
}

class SellViewModel(
    private val itemRepository: ItemRepository,
    private val saleRepository: SaleRepository,
    private val ticketRepository: TicketRepository,
    private val salesLayoutStore: SalesLayoutStore,
    private val syncManager: SyncManager,
    settingsStore: SettingsStore,
    sessionStore: SessionStore,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<String?>(null)
    private val selectedPageId = MutableStateFlow(MAIN_PAGE_ID)
    private val selectedTier = MutableStateFlow<PriceTier?>(null)
    private val cart = MutableStateFlow(Cart())
    private val checkingOut = MutableStateFlow(false)
    private val activeTicketId = MutableStateFlow<String?>(null)

    private val _events = MutableStateFlow<SellEvent?>(null)
    val events: StateFlow<SellEvent?> = _events.asStateFlow()

    val state: StateFlow<SellUiState> = combine(
        combine(
            itemRepository.observeActive(),
            itemRepository.observeCategories(),
            salesLayoutStore.layout,
            ticketRepository.observeAll(),
        ) { items, categories, layout, tickets -> ShelfData(items, categories, layout, tickets) },
        combine(query, category, selectedPageId, selectedTier) { q, cat, page, tier ->
            SalesFilters(q, cat, page, tier)
        },
        combine(settingsStore.settings, sessionStore.session, checkingOut, ::Triple),
        combine(cart, activeTicketId, ::Pair),
    ) { shelf, filters, (settings, session, busy), (currentCart, ticketId) ->
        val items = shelf.items
        val cats = shelf.categories
        val layout = shelf.layout
        SellUiState(
            allItems = items,
            categories = cats,
            query = filters.query,
            // Drop a category filter that no longer exists.
            category = filters.category?.takeIf { it in cats },
            pages = layout.pages,
            selectedPageId = filters.pageId.takeIf { id ->
                id == MAIN_PAGE_ID || layout.pages.any { it.id == id }
            } ?: MAIN_PAGE_ID,
            selectedTier = filters.tier ?: settings.defaultTier,
            pinnedItemIds = layout.pinnedItemIds,
            cart = currentCart,
            settings = settings,
            session = session,
            checkingOut = busy,
            heldTickets = shelf.tickets,
            activeTicketId = ticketId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SellUiState())

    fun setQuery(value: String) = query.update { value }

    fun setCategory(value: String?) = category.update { value }

    fun setPage(id: String) = selectedPageId.update { id }

    /** Changes the shelf price and every current cart line in one tap. */
    fun setSaleTier(tier: PriceTier) {
        selectedTier.value = tier
        cart.update { current ->
            current.copy(lines = current.lines.map { it.copy(tier = tier) })
        }
    }

    fun setPinned(itemId: String, pinned: Boolean) = viewModelScope.launch {
        salesLayoutStore.setPinned(itemId, pinned)
    }

    fun addPage(name: String, itemIds: Set<String>) = viewModelScope.launch {
        selectedPageId.value = salesLayoutStore.addPage(name, itemIds)
    }

    fun updatePage(id: String, name: String, itemIds: Set<String>) = viewModelScope.launch {
        salesLayoutStore.updatePage(id, name, itemIds)
    }

    fun deletePage(id: String) = viewModelScope.launch {
        salesLayoutStore.deletePage(id)
        if (selectedPageId.value == id) selectedPageId.value = MAIN_PAGE_ID
    }

    fun add(item: ItemEntity, tier: PriceTier? = null) {
        cart.update { current ->
            val defaultTier = tier ?: state.value.selectedTier
            val existing = current.lineFor(item.id)
            val lines = if (existing == null) {
                current.lines + CartLine(item, defaultTier, 1)
            } else {
                current.lines.map {
                    if (it.item.id == item.id) it.copy(qty = it.qty + 1) else it
                }
            }
            current.copy(lines = lines)
        }
    }

    fun setQty(itemId: String, qty: Int) {
        cart.update { current ->
            val lines = if (qty <= 0) {
                current.lines.filterNot { it.item.id == itemId }
            } else {
                current.lines.map { if (it.item.id == itemId) it.copy(qty = qty) else it }
            }
            current.copy(lines = lines)
        }
    }

    fun setTier(itemId: String, tier: PriceTier) {
        cart.update { current ->
            current.copy(lines = current.lines.map {
                if (it.item.id == itemId) it.copy(tier = tier) else it
            })
        }
    }

    fun remove(itemId: String) = setQty(itemId, 0)

    fun setDiscount(centavos: Long) = cart.update { it.copy(discount = centavos.coerceAtLeast(0)) }

    fun setNote(note: String) = cart.update { it.copy(note = note) }

    fun setTendered(centavos: Long) = cart.update { it.copy(tendered = centavos.coerceAtLeast(0)) }

    fun setPaymentMethod(method: String) = cart.update {
        it.copy(
            paymentMethod = method,
            // Non-cash payments are always for the exact bill unless edited as cash later.
            tendered = if (method == "Cash") it.tendered else it.net,
        )
    }

    fun setOrderType(type: OrderType) = cart.update { it.copy(orderType = type) }

    fun setOrderLabel(label: String) = cart.update { it.copy(orderLabel = label) }

    fun clearCart() {
        cart.value = Cart()
        activeTicketId.value = null
    }

    fun holdTicket(title: String) {
        val snapshot = state.value
        if (snapshot.cart.isEmpty || checkingOut.value) return
        viewModelScope.launch {
            runCatching {
                ticketRepository.hold(
                    cart = snapshot.cart,
                    title = title,
                    session = snapshot.session,
                    existingId = snapshot.activeTicketId,
                )
            }.fold(
                onSuccess = {
                    cart.value = Cart()
                    activeTicketId.value = null
                    _events.value = SellEvent.Info("Ticket set aside")
                },
                onFailure = { _events.value = SellEvent.Failed(it.message ?: "Could not hold ticket") },
            )
        }
    }

    fun loadTicket(id: String) = viewModelScope.launch {
        val restored = ticketRepository.load(id)
        if (restored == null) {
            _events.value = SellEvent.Failed("This ticket no longer has available products")
        } else {
            cart.value = restored
            activeTicketId.value = id
            _events.value = SellEvent.Info("Ticket opened — add items or complete payment")
        }
    }

    fun deleteTicket(id: String) = viewModelScope.launch {
        ticketRepository.delete(id)
        if (activeTicketId.value == id) {
            activeTicketId.value = null
            cart.value = Cart()
        }
        _events.value = SellEvent.Info("Ticket deleted")
    }

    fun checkout() {
        val snapshot = state.value
        val current = snapshot.cart
        if (current.isEmpty || !current.canCheckout || checkingOut.value) return

        // Warn rather than block: a physical count can legitimately differ from the app's number,
        // and refusing the sale at the counter is worse than recording it.
        val shortfall = current.lines.filter { it.item.trackStock && it.qty > it.item.stockQty }

        checkingOut.value = true
        viewModelScope.launch {
            val session = snapshot.session
            val result = saleRepository.recordSale(
                lines = current.lines,
                discountCentavos = current.effectiveDiscount,
                cashierId = session.uid.ifBlank { "local-admin" },
                cashierName = session.displayName.ifBlank { "Owner" },
                note = current.note,
                paymentMethod = current.paymentMethod,
                tenderedCentavos = if (current.paymentMethod == "Cash") current.tendered else current.net,
                orderType = current.orderType,
                orderLabel = current.orderLabel,
                deviceCode = snapshot.settings.deviceCode,
            )
            checkingOut.value = false

            result.fold(
                onSuccess = { saleId ->
                    activeTicketId.value?.let { ticketRepository.delete(it) }
                    activeTicketId.value = null
                    cart.value = Cart()
                    _events.value = SellEvent.Completed(
                        saleId = saleId,
                        profit = current.profit,
                        oversold = shortfall.map { it.item.name },
                    )
                    // Push promptly so other signed-in admin devices receive their sale alert.
                    if (syncManager.isCloudEnabled) {
                        viewModelScope.launch { syncManager.syncAll() }
                    }
                },
                onFailure = { _events.value = SellEvent.Failed(it.message ?: "Could not save the sale") },
            )
        }
    }

    fun consumeEvent() {
        _events.value = null
    }
}

private data class SalesFilters(
    val query: String,
    val category: String?,
    val pageId: String,
    val tier: PriceTier?,
)

private data class ShelfData(
    val items: List<ItemEntity>,
    val categories: List<String>,
    val layout: SalesLayout,
    val tickets: List<TicketWithLines>,
)

const val MAIN_PAGE_ID = "main"

sealed interface SellEvent {
    data class Completed(val saleId: String, val profit: Long, val oversold: List<String>) : SellEvent
    data class Failed(val message: String) : SellEvent
    data class Info(val message: String) : SellEvent
}
