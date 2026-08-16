package com.uhmk.pos.feature.tally

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.model.CartLine
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.prefs.Session
import com.uhmk.pos.core.prefs.SessionStore
import com.uhmk.pos.core.prefs.SettingsStore
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.repo.ItemRepository
import com.uhmk.pos.core.repo.SaleRepository
import com.uhmk.pos.core.time.DateRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** How many of one product went out at each price for a selected period. */
data class TallyEntry(val student: Int = 0, val regular: Int = 0) {
    val total: Int get() = student + regular
    val isEmpty: Boolean get() = total == 0
}

data class DayTallyUiState(
    val date: LocalDate = LocalDate.now(),
    val items: List<ItemEntity> = emptyList(),
    val categories: List<String> = emptyList(),
    val entries: Map<String, TallyEntry> = emptyMap(),
    val query: String = "",
    val category: String? = null,
    val onlyCounted: Boolean = false,
    val settings: StoreSettings = StoreSettings(),
    val session: Session = Session(),
    val loadedFromSales: Boolean = false,
    val busy: Boolean = false,
) {
    val visible: List<ItemEntity>
        get() = items.filter { item ->
            (category == null || item.category == category) &&
                (!onlyCounted || (entries[item.id]?.isEmpty == false)) &&
                (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true))
        }

    fun entryFor(id: String): TallyEntry = entries[id] ?: TallyEntry()

    private val counted: List<Pair<ItemEntity, TallyEntry>>
        get() = items.mapNotNull { item ->
            val e = entries[item.id] ?: return@mapNotNull null
            if (e.isEmpty) null else item to e
        }

    val lineCount: Int get() = counted.size
    val unitCount: Int get() = counted.sumOf { (_, e) -> e.total }

    val revenue: Long
        get() = counted.sumOf { (item, e) ->
            e.student * item.studentCentavos + e.regular * item.regularCentavos
        }

    /** Capital, counting only items whose cost is known. */
    val capital: Long
        get() = counted.sumOf { (item, e) ->
            if (item.costKnown) e.total * item.costCentavos else 0L
        }

    val profit: Long
        get() = counted.sumOf { (item, e) ->
            if (!item.costKnown) 0L
            else e.student * (item.studentCentavos - item.costCentavos) +
                e.regular * (item.regularCentavos - item.costCentavos)
        }

    /** Revenue from items with no cost on file — profit on these is not knowable yet. */
    val unknownRevenue: Long
        get() = counted.sumOf { (item, e) ->
            if (item.costKnown) 0L
            else e.student * item.studentCentavos + e.regular * item.regularCentavos
        }

    val hasUnknownCost: Boolean get() = counted.any { (item, _) -> !item.costKnown }
    val isEmpty: Boolean get() = counted.isEmpty()
    val canSeeProfit: Boolean get() = session.isAdmin || settings.showProfitToStaff
}

/**
 * A day sheet: scroll the whole catalogue, punch in how many of each went out, and read the total
 * off the bottom — without opening every product one by one.
 *
 * It works two ways round. Punch the counts in by hand for a day that was not rung up item by
 * item, or pick a date and pull in what was actually recorded.
 */
class DayTallyViewModel(
    private val itemRepository: ItemRepository,
    private val saleRepository: SaleRepository,
    settingsStore: SettingsStore,
    sessionStore: SessionStore,
) : ViewModel() {

    private val date = MutableStateFlow(LocalDate.now())
    private val entries = MutableStateFlow<Map<String, TallyEntry>>(emptyMap())
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<String?>(null)
    private val onlyCounted = MutableStateFlow(false)
    private val loadedFromSales = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<DayTallyUiState> = combine(
        combine(itemRepository.observeActive(), itemRepository.observeCategories(), ::Pair),
        combine(date, entries, ::Pair),
        combine(query, category, onlyCounted, ::Triple),
        combine(settingsStore.settings, sessionStore.session, ::Pair),
        combine(loadedFromSales, busy, ::Pair),
    ) { (items, cats), (d, e), (q, cat, only), (settings, session), (loaded, isBusy) ->
        DayTallyUiState(
            date = d,
            items = items,
            categories = cats,
            entries = e,
            query = q,
            category = cat?.takeIf { it in cats },
            onlyCounted = only,
            settings = settings,
            session = session,
            loadedFromSales = loaded,
            busy = isBusy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayTallyUiState())

    fun setDate(value: LocalDate) {
        date.value = value
        // The counts on screen belong to the old date; clear rather than silently carry them over.
        entries.value = emptyMap()
        loadedFromSales.value = false
    }

    fun setQuery(value: String) = query.update { value }
    fun setCategory(value: String?) = category.update { value }
    fun toggleOnlyCounted() = onlyCounted.update { !it }

    fun setStudent(itemId: String, qty: Int) = edit(itemId) { it.copy(student = qty.coerceAtLeast(0)) }
    fun setRegular(itemId: String, qty: Int) = edit(itemId) { it.copy(regular = qty.coerceAtLeast(0)) }

    private fun edit(itemId: String, transform: (TallyEntry) -> TallyEntry) {
        entries.update { current ->
            val next = transform(current[itemId] ?: TallyEntry())
            if (next.isEmpty) current - itemId else current + (itemId to next)
        }
        loadedFromSales.value = false
    }

    fun clear() {
        entries.value = emptyMap()
        loadedFromSales.value = false
    }

    /** Fills the sheet from sales actually recorded on the selected date. */
    fun loadFromSales() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            val day = date.value
            val sales = saleRepository.salesWithLinesInRange(
                DateRange.startOfDay(day),
                DateRange.endOfDay(day),
            )
            val currentItems = state.value.items
            val tally = mutableMapOf<String, TallyEntry>()
            var unmatched = 0
            sales.flatMap { it.lines }.forEach { line ->
                // v1 used IDs from the original hand-built list; v2+ uses catalogue handles. Receipts
                // preserve the old name, so resolve by name when an upgraded ID no longer exists.
                val item = currentItems.firstOrNull { it.id == line.itemId }
                    ?: currentItems.bestNameMatch(line.itemName)
                if (item == null) {
                    unmatched += line.qty
                    return@forEach
                }
                val current = tally[item.id] ?: TallyEntry()
                tally[item.id] = when (line.tier) {
                    PriceTier.STUDENT -> current.copy(student = current.student + line.qty)
                    PriceTier.REGULAR -> current.copy(regular = current.regular + line.qty)
                }
            }
            entries.value = tally
            loadedFromSales.value = true
            busy.value = false
            _message.value = if (tally.isEmpty()) {
                if (sales.isEmpty()) "No sales recorded on that date"
                else "Sales were found, but their retired products are no longer in the catalogue"
            } else {
                buildString {
                    append("Loaded ${sales.size} sale${if (sales.size == 1) "" else "s"} from $day")
                    if (unmatched > 0) append(" · $unmatched retired units not shown")
                }
            }
        }
    }

    /**
     * Turns a hand-punched sheet into a real sale, so the day shows up in reports.
     * Deliberately blocked when the sheet was loaded from sales — that would double-count.
     */
    fun recordAsSale() {
        val snapshot = state.value
        if (snapshot.isEmpty || snapshot.loadedFromSales || busy.value) return

        busy.value = true
        viewModelScope.launch {
            val lines = mutableListOf<CartLine>()
            snapshot.items.forEach { item ->
                val e = snapshot.entryFor(item.id)
                if (e.student > 0) lines += CartLine(item, PriceTier.STUDENT, e.student)
                if (e.regular > 0) lines += CartLine(item, PriceTier.REGULAR, e.regular)
            }

            // Stamp it at midday on the chosen date so it lands in that day's reports.
            val soldAt = DateRange.startOfDay(snapshot.date) + 12 * 60 * 60 * 1000L
            val session = snapshot.session

            saleRepository.recordSale(
                lines = lines,
                discountCentavos = 0,
                cashierId = session.uid.ifBlank { "local-admin" },
                cashierName = session.displayName.ifBlank { "Owner" },
                note = "Day tally for ${snapshot.date}",
                soldAt = soldAt,
                deviceCode = snapshot.settings.deviceCode,
            ).fold(
                onSuccess = {
                    entries.value = emptyMap()
                    _message.value = "Recorded ${lines.size} lines into ${snapshot.date}"
                },
                onFailure = { _message.value = it.message ?: "Could not record the tally" },
            )
            busy.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

/** Conservative fuzzy match used only for receipts made before the catalogue upgrade. */
private fun List<ItemEntity>.bestNameMatch(oldName: String): ItemEntity? {
    val old = oldName.catalogueKey()
    if (old.isBlank()) return null
    firstOrNull { it.name.catalogueKey() == old }?.let { return it }
    return map { it to it.name.catalogueKey() }
        .filter { (_, key) ->
            old.length >= 6 && key.length >= 6 && (old.startsWith(key) || key.startsWith(old))
        }
        .minByOrNull { (_, key) -> kotlin.math.abs(key.length - old.length) }
        ?.first
}

private fun String.catalogueKey(): String = lowercase()
    .replace(Regex("\\([^)]*\\)"), " ")
    .replace(Regex("\\b\\d+\\s*(pcs?|pk|packs?|s|kg|g|ml)\\b"), " ")
    .replace("seaweeds", "seaweed")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
