package com.uhmk.pos.feature.inventory

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.prefs.SettingsStore
import com.uhmk.pos.core.repo.ItemRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ItemForm(
    val name: String = "",
    val category: String = "General",
    val sku: String = "",
    val cost: String = "",
    val zeroCost: Boolean = false,
    val student: String = "",
    val regular: String = "",
    val boxCost: String = "",
    val unitsPerBox: String = "1",
    val stock: String = "0",
    val lowStockAt: String = "5",
    val trackStock: Boolean = true,
    val imagePath: String? = null,
    val imageHash: String = "",
    val active: Boolean = true,
) {
    val costCentavos: Long get() = Money.parse(cost) ?: 0
    val studentCentavos: Long get() = Money.parse(student) ?: 0
    val regularCentavos: Long get() = Money.parse(regular) ?: 0
    val boxCostCentavos: Long get() = Money.parse(boxCost) ?: 0
    val units: Int get() = unitsPerBox.toIntOrNull() ?: 1

    /** Null until a cost is entered — the preview says so rather than showing the whole price. */
    val costKnown: Boolean get() = zeroCost || costCentavos > 0
    val studentProfit: Long? get() = if (costKnown) studentCentavos - costCentavos else null
    val regularProfit: Long? get() = if (costKnown) regularCentavos - costCentavos else null
    val studentProfitPerBox: Long? get() = studentProfit?.let { it * units }

    val nameError: String? get() = if (name.isBlank()) "Give the item a name" else null
    val priceError: String?
        get() = when {
            studentCentavos <= 0 -> "Set a selling price"
            costKnown && studentCentavos < costCentavos ->
                "Selling price is below cost — this sale would lose money"
            else -> null
        }
    val isValid: Boolean get() = nameError == null && studentCentavos > 0
}

data class ItemEditUiState(
    val form: ItemForm = ItemForm(),
    val isNew: Boolean = true,
    val loading: Boolean = true,
    val saved: Boolean = false,
    val currency: String = "₱",
    val knownCategories: List<String> = emptyList(),
)

class ItemEditViewModel(
    private val repository: ItemRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ItemEditUiState())
    val state: StateFlow<ItemEditUiState> = _state.asStateFlow()

    private var original: ItemEntity? = null

    fun load(itemId: String?) {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val categories = repository.observeCategories().first()
            val existing = itemId?.takeIf { it.isNotBlank() && it != NEW }?.let { repository.getById(it) }

            original = existing ?: repository.newItemTemplate().copy(
                lowStockAt = settings.lowStockDefault,
            )

            val base = original!!
            _state.value = ItemEditUiState(
                form = if (existing == null) {
                    ItemForm(lowStockAt = settings.lowStockDefault.toString())
                } else {
                    ItemForm(
                        name = base.name,
                        category = base.category,
                        sku = base.sku,
                        // Leave the field blank rather than showing 0.00 when no cost is on file.
                        cost = if (base.costKnown) Money.formatAmount(base.costCentavos) else "",
                        zeroCost = base.costKnown && base.costCentavos == 0L,
                        student = Money.formatAmount(base.studentCentavos),
                        regular = Money.formatAmount(base.regularCentavos),
                        boxCost = Money.formatAmount(base.boxCostCentavos),
                        unitsPerBox = base.unitsPerBox.toString(),
                        stock = base.stockQty.toString(),
                        lowStockAt = base.lowStockAt.toString(),
                        trackStock = base.trackStock,
                        imagePath = base.imagePath,
                        imageHash = base.imageHash,
                        active = base.active,
                    )
                },
                isNew = existing == null,
                loading = false,
                currency = settings.currencySymbol,
                knownCategories = categories,
            )
        }
    }

    fun edit(transform: (ItemForm) -> ItemForm) =
        _state.update { it.copy(form = transform(it.form)) }

    /**
     * Fills the unit cost from the box price, mirroring how the spreadsheet derives SRP.
     * Rounds to the nearest centavo so the figure stays honest rather than optimistic.
     */
    fun deriveUnitCostFromBox() {
        _state.update { s ->
            val units = s.form.units
            if (units <= 0 || s.form.boxCostCentavos <= 0) return@update s
            val perUnit = (s.form.boxCostCentavos + units / 2) / units
            s.copy(form = s.form.copy(cost = Money.formatAmount(perUnit)))
        }
    }

    fun pickImage(uri: Uri) {
        val id = original?.id ?: return
        viewModelScope.launch {
            val stored = repository.storeImage(uri, id)
            if (stored != null) edit { it.copy(imagePath = stored.path, imageHash = stored.hash) }
        }
    }

    // The hash goes too, otherwise the sync layer sees no change and the removal never leaves
    // this device.
    fun clearImage() = edit { it.copy(imagePath = null, imageHash = "") }

    fun save() {
        val base = original ?: return
        val form = _state.value.form
        if (!form.isValid) return

        viewModelScope.launch {
            repository.save(
                base.copy(
                    name = form.name.trim(),
                    category = form.category.trim().ifBlank { "General" },
                    sku = form.sku.trim(),
                    trackStock = form.trackStock,
                    costCentavos = form.costCentavos,
                    costKnown = form.costKnown,
                    studentCentavos = form.studentCentavos,
                    // An empty regular price falls back to the student price rather than zero,
                    // which would otherwise read as a 100% margin.
                    regularCentavos = if (form.regularCentavos > 0) form.regularCentavos else form.studentCentavos,
                    boxCostCentavos = form.boxCostCentavos,
                    unitsPerBox = form.units.coerceAtLeast(1),
                    stockQty = form.stock.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    lowStockAt = form.lowStockAt.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    imagePath = form.imagePath,
                    imageHash = form.imageHash,
                    active = form.active,
                )
            )
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete(onDone: () -> Unit) {
        val base = original ?: return
        viewModelScope.launch {
            repository.delete(base)
            onDone()
        }
    }

    companion object {
        const val NEW = "new"
    }
}
