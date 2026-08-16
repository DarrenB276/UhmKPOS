package com.uhmk.pos.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.db.CategoryCount
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.prefs.Session
import com.uhmk.pos.core.prefs.SessionStore
import com.uhmk.pos.core.prefs.SettingsStore
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.repo.ItemRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class InventorySort(val label: String) {
    NAME("Name"),
    PROFIT("Profit"),
    STOCK("Stock"),
    VALUE("Stock value"),
    CATEGORY("Category"),
}

data class InventoryFilters(
    val query: String = "",
    val category: String? = null,
    val lowStockOnly: Boolean = false,
    val needsCostOnly: Boolean = false,
    val sort: InventorySort = InventorySort.NAME,
)

data class InventoryUiState(
    val items: List<ItemEntity> = emptyList(),
    val categories: List<String> = emptyList(),
    val categoryCounts: List<CategoryCount> = emptyList(),
    val filters: InventoryFilters = InventoryFilters(),
    val settings: StoreSettings = StoreSettings(),
    val session: Session = Session(),
) {
    val visible: List<ItemEntity>
        get() = items
            .filter { item ->
                (filters.category == null || item.category == filters.category) &&
                    (!filters.lowStockOnly || item.isLowStock) &&
                    (!filters.needsCostOnly || !item.costKnown) &&
                    (filters.query.isBlank() ||
                        item.name.contains(filters.query.trim(), ignoreCase = true) ||
                        item.sku.contains(filters.query.trim(), ignoreCase = true))
            }
            .sortedWith(
                when (filters.sort) {
                    InventorySort.NAME -> compareBy { it.name }
                    InventorySort.PROFIT -> compareByDescending { it.studentProfit ?: Long.MIN_VALUE }
                    InventorySort.STOCK -> compareBy { it.stockQty }
                    InventorySort.VALUE -> compareByDescending { it.stockQty * it.costCentavos }
                    InventorySort.CATEGORY -> compareBy<ItemEntity> { it.category }.thenBy { it.name }
                }
            )

    /** Capital sitting on the shelves, counting only items whose cost is known. */
    val stockCapital: Long get() = items.filter { it.costKnown }.sumOf { it.stockQty * it.costCentavos }

    /** Profit still to be made if known-cost stock all sells at the student price. */
    val potentialProfit: Long
        get() = items.sumOf { item -> (item.studentProfit ?: 0L) * item.stockQty }

    val lowStockCount: Int get() = items.count { it.isLowStock }
    val missingCostCount: Int get() = items.count { it.active && !it.costKnown }
    val trackedCount: Int get() = items.count { it.trackStock }
    val canSeeProfit: Boolean get() = session.isAdmin || settings.showProfitToStaff
    val canEdit: Boolean get() = session.isAdmin
}

class InventoryViewModel(
    private val repository: ItemRepository,
    settingsStore: SettingsStore,
    sessionStore: SessionStore,
) : ViewModel() {

    private val filters = MutableStateFlow(InventoryFilters())

    val state: StateFlow<InventoryUiState> = combine(
        combine(repository.observeAll(), repository.observeCategories(), ::Pair),
        repository.observeCategoryCounts(),
        filters,
        combine(settingsStore.settings, sessionStore.session, ::Pair),
    ) { (items, cats), counts, f, (settings, session) ->
        InventoryUiState(
            items = items,
            categories = cats,
            categoryCounts = counts,
            filters = f.copy(category = f.category?.takeIf { it in cats }),
            settings = settings,
            session = session,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryUiState())

    fun setQuery(value: String) = filters.update { it.copy(query = value) }
    fun setCategory(value: String?) = filters.update { it.copy(category = value) }
    fun toggleLowStockOnly() = filters.update { it.copy(lowStockOnly = !it.lowStockOnly) }
    fun toggleNeedsCostOnly() = filters.update { it.copy(needsCostOnly = !it.needsCostOnly) }
    fun setSort(value: InventorySort) = filters.update { it.copy(sort = value) }

    /** Inline cost entry from the "needs cost" list, so the gap can be closed without opening each item. */
    fun setCost(itemId: String, text: String) {
        val centavos = Money.parse(text) ?: return
        viewModelScope.launch { repository.setCost(itemId, centavos) }
    }

    fun adjustStock(itemId: String, delta: Int) = viewModelScope.launch {
        repository.addStock(itemId, delta)
    }

    fun renameCategory(from: String, to: String) = viewModelScope.launch {
        repository.renameCategory(from, to)
    }

    fun delete(item: ItemEntity) = viewModelScope.launch { repository.delete(item) }
}
