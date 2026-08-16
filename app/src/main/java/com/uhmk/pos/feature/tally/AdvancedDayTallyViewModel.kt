package com.uhmk.pos.feature.tally

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.db.AuditLogEntity
import com.uhmk.pos.core.db.ItemEntity
import com.uhmk.pos.core.db.SaleWithLines
import com.uhmk.pos.core.model.CartLine
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.prefs.PinStore
import com.uhmk.pos.core.prefs.Session
import com.uhmk.pos.core.prefs.SessionStore
import com.uhmk.pos.core.prefs.SettingsStore
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.repo.ItemRepository
import com.uhmk.pos.core.repo.SaleRepository
import com.uhmk.pos.core.time.DateRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class TallyPeriod(val label: String) {
    DAY("One day"),
    RANGE("Date range"),
}

enum class TallySource {
    MANUAL,
    RECORDED_SALES,
    SAVED_TALLY,
    RANGE_TOTAL,
}

data class AdvancedDayTallyUiState(
    val period: TallyPeriod = TallyPeriod.DAY,
    val date: LocalDate = LocalDate.now(),
    val toDate: LocalDate = LocalDate.now(),
    val items: List<ItemEntity> = emptyList(),
    val categories: List<String> = emptyList(),
    val entries: Map<String, TallyEntry> = emptyMap(),
    val query: String = "",
    val category: String? = null,
    val onlyCounted: Boolean = false,
    val settings: StoreSettings = StoreSettings(),
    val session: Session = Session(),
    val source: TallySource = TallySource.MANUAL,
    val existingTallyId: String? = null,
    val auditLogs: List<AuditLogEntity> = emptyList(),
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
            val entry = entries[item.id] ?: return@mapNotNull null
            if (entry.isEmpty) null else item to entry
        }

    val lineCount: Int get() = counted.size
    val unitCount: Int get() = counted.sumOf { (_, entry) -> entry.total }
    val revenue: Long get() = counted.sumOf { (item, entry) ->
        entry.student * item.studentCentavos + entry.regular * item.regularCentavos
    }
    val capital: Long get() = counted.sumOf { (item, entry) ->
        if (item.costKnown) entry.total * item.costCentavos else 0L
    }
    val profit: Long get() = counted.sumOf { (item, entry) ->
        if (!item.costKnown) 0L else {
            entry.student * (item.studentCentavos - item.costCentavos) +
                entry.regular * (item.regularCentavos - item.costCentavos)
        }
    }
    val unknownRevenue: Long get() = counted.sumOf { (item, entry) ->
        if (item.costKnown) 0L
        else entry.student * item.studentCentavos + entry.regular * item.regularCentavos
    }
    val hasUnknownCost: Boolean get() = counted.any { (item, _) -> !item.costKnown }
    val isEmpty: Boolean get() = counted.isEmpty()
    val canSeeProfit: Boolean get() = session.isAdmin || settings.showProfitToStaff
    val isReadOnly: Boolean get() = period == TallyPeriod.RANGE ||
        source == TallySource.RECORDED_SALES || source == TallySource.RANGE_TOTAL
    val editingSavedTally: Boolean get() = source == TallySource.SAVED_TALLY
}

private data class Catalogue(val items: List<ItemEntity>, val categories: List<String>)
private data class Dates(val period: TallyPeriod, val from: LocalDate, val to: LocalDate)
private data class TallyFilters(
    val entries: Map<String, TallyEntry>,
    val query: String,
    val category: String?,
    val onlyCounted: Boolean,
)
private data class TallyEditState(
    val source: TallySource,
    val busy: Boolean,
    val existingTallyId: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedDayTallyViewModel(
    private val itemRepository: ItemRepository,
    private val saleRepository: SaleRepository,
    settingsStore: SettingsStore,
    sessionStore: SessionStore,
    private val pinStore: PinStore,
) : ViewModel() {

    private val period = MutableStateFlow(TallyPeriod.DAY)
    private val date = MutableStateFlow(LocalDate.now())
    private val toDate = MutableStateFlow(LocalDate.now())
    private val entries = MutableStateFlow<Map<String, TallyEntry>>(emptyMap())
    private val originalEntries = MutableStateFlow<Map<String, TallyEntry>>(emptyMap())
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<String?>(null)
    private val onlyCounted = MutableStateFlow(false)
    private val source = MutableStateFlow(TallySource.MANUAL)
    private val existingTallyId = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val auditLogs = date.flatMapLatest {
        saleRepository.observeAuditForDate(it.toEpochDay())
    }

    private val baseState = combine(
        combine(itemRepository.observeActive(), itemRepository.observeCategories()) { items, cats ->
            Catalogue(items, cats)
        },
        combine(period, date, toDate, ::Dates),
        combine(entries, query, category, onlyCounted, ::TallyFilters),
        combine(settingsStore.settings, sessionStore.session, ::Pair),
        combine(source, busy, existingTallyId, ::TallyEditState),
    ) { catalogue, dates, filters, account, edit ->
        AdvancedDayTallyUiState(
            period = dates.period,
            date = dates.from,
            toDate = dates.to,
            items = catalogue.items,
            categories = catalogue.categories,
            entries = filters.entries,
            query = filters.query,
            category = filters.category?.takeIf { it in catalogue.categories },
            onlyCounted = filters.onlyCounted,
            settings = account.first,
            session = account.second,
            source = edit.source,
            existingTallyId = edit.existingTallyId,
            busy = edit.busy,
        )
    }

    val state: StateFlow<AdvancedDayTallyUiState> = combine(baseState, auditLogs) { base, logs ->
        base.copy(auditLogs = if (base.period == TallyPeriod.DAY) logs else emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdvancedDayTallyUiState())

    init {
        refreshExistingTally()
    }

    fun setPeriod(value: TallyPeriod) {
        if (period.value == value) return
        period.value = value
        resetSheet()
        if (value == TallyPeriod.RANGE) {
            existingTallyId.value = null
            toDate.value = date.value
            loadFromSales(date.value, date.value, TallySource.RANGE_TOTAL)
        } else {
            toDate.value = date.value
            refreshExistingTally()
        }
    }

    fun setDate(value: LocalDate) {
        period.value = TallyPeriod.DAY
        date.value = value
        toDate.value = value
        resetSheet()
        refreshExistingTally()
    }

    fun setRange(first: LocalDate, last: LocalDate) {
        val from = minOf(first, last)
        val to = maxOf(first, last)
        period.value = TallyPeriod.RANGE
        date.value = from
        toDate.value = to
        existingTallyId.value = null
        resetSheet()
        loadFromSales(from, to, TallySource.RANGE_TOTAL)
    }

    fun setQuery(value: String) = query.update { value }
    fun setCategory(value: String?) = category.update { value }
    fun toggleOnlyCounted() = onlyCounted.update { !it }

    fun setStudent(itemId: String, qty: Int) = edit(itemId) {
        it.copy(student = qty.coerceAtLeast(0))
    }

    fun setRegular(itemId: String, qty: Int) = edit(itemId) {
        it.copy(regular = qty.coerceAtLeast(0))
    }

    private fun edit(itemId: String, transform: (TallyEntry) -> TallyEntry) {
        if (source.value == TallySource.RECORDED_SALES || source.value == TallySource.RANGE_TOTAL) return
        entries.update { current ->
            val next = transform(current[itemId] ?: TallyEntry())
            if (next.isEmpty) current - itemId else current + (itemId to next)
        }
    }

    fun clear() {
        entries.value = emptyMap()
        originalEntries.value = emptyMap()
        source.value = TallySource.MANUAL
    }

    fun loadFromSales() {
        val selected = date.value
        loadFromSales(selected, selected, TallySource.RECORDED_SALES)
    }

    private fun loadFromSales(from: LocalDate, to: LocalDate, resultSource: TallySource) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val sales = saleRepository.salesWithLinesInRange(
                    DateRange.startOfDay(from),
                    DateRange.endOfDay(to),
                )
                val result = tallySales(sales, itemRepository.observeActive().first())
                entries.value = result.entries
                originalEntries.value = emptyMap()
                source.value = resultSource
                _message.value = if (result.entries.isEmpty()) {
                    if (sales.isEmpty()) "No sales recorded in that date selection"
                    else "Sales were found, but their retired products are no longer in the catalogue"
                } else {
                    buildString {
                        append("Loaded ${sales.size} sale${if (sales.size == 1) "" else "s"}")
                        if (from == to) append(" from $from") else append(" from $from to $to")
                        if (result.unmatched > 0) append(" · ${result.unmatched} retired units not shown")
                    }
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun loadSavedTallyForEdit() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val selected = date.value
                val saved = saleRepository.activeTallyForDay(
                    DateRange.startOfDay(selected),
                    DateRange.endOfDay(selected),
                )
                if (saved == null) {
                    existingTallyId.value = null
                    _message.value = "No saved tally exists for $selected"
                    return@launch
                }
                val result = tallySales(listOf(saved), itemRepository.observeActive().first())
                entries.value = result.entries
                originalEntries.value = result.entries
                existingTallyId.value = saved.sale.id
                source.value = TallySource.SAVED_TALLY
                _message.value = "Loaded saved tally. Your PIN will be required to save changes."
            } finally {
                busy.value = false
            }
        }
    }

    fun recordAsSale(pin: String? = null) {
        val snapshot = state.value
        if (snapshot.isEmpty || snapshot.isReadOnly || busy.value || !snapshot.session.isAdmin) return
        if (!snapshot.editingSavedTally && snapshot.existingTallyId != null) {
            _message.value = "A saved tally already exists for this date. Open it with Edit saved tally."
            return
        }
        if (snapshot.editingSavedTally && normalized(entries.value) == normalized(originalEntries.value)) {
            _message.value = "No tally changes to save"
            return
        }

        busy.value = true
        viewModelScope.launch {
            try {
                if (snapshot.editingSavedTally) {
                    val uid = snapshot.session.uid
                    val storedHash = pinStore.pinHash(uid).first()
                    if (storedHash.isBlank()) {
                        _message.value = "Set a launch PIN in Settings before editing a saved tally"
                        return@launch
                    }
                    if (pin.isNullOrBlank() || !pinStore.verify(uid, pin, storedHash)) {
                        _message.value = "Incorrect PIN. The tally was not changed."
                        return@launch
                    }
                }

                val lines = snapshot.items.flatMap { item ->
                    val entry = snapshot.entryFor(item.id)
                    buildList {
                        if (entry.student > 0) add(CartLine(item, PriceTier.STUDENT, entry.student))
                        if (entry.regular > 0) add(CartLine(item, PriceTier.REGULAR, entry.regular))
                    }
                }
                val changedIds = (originalEntries.value.keys + entries.value.keys).filter {
                    (originalEntries.value[it] ?: TallyEntry()) != (entries.value[it] ?: TallyEntry())
                }.toSet()
                val before = entriesSummary(snapshot.items, originalEntries.value, changedIds)
                val after = entriesSummary(snapshot.items, entries.value, changedIds)
                val soldAt = DateRange.startOfDay(snapshot.date) + 12 * 60 * 60 * 1000L

                saleRepository.saveTally(
                    lines = lines,
                    soldAt = soldAt,
                    existingSaleId = snapshot.existingTallyId.takeIf { snapshot.editingSavedTally },
                    actorId = snapshot.session.uid.ifBlank { "local-admin" },
                    actorName = snapshot.session.displayName.ifBlank { "Owner" },
                    businessDateEpochDay = snapshot.date.toEpochDay(),
                    beforeSummary = before,
                    afterSummary = after,
                ).fold(
                    onSuccess = {
                        entries.value = emptyMap()
                        originalEntries.value = emptyMap()
                        source.value = TallySource.MANUAL
                        _message.value = if (snapshot.editingSavedTally) {
                            "Saved the corrected tally for ${snapshot.date}"
                        } else {
                            "Saved ${lines.size} tally lines for ${snapshot.date}"
                        }
                        refreshExistingTally()
                    },
                    onFailure = { _message.value = it.message ?: "Could not save the tally" },
                )
            } finally {
                busy.value = false
            }
        }
    }

    private fun refreshExistingTally() {
        val selected = date.value
        viewModelScope.launch {
            val saved = saleRepository.activeTallyForDay(
                DateRange.startOfDay(selected),
                DateRange.endOfDay(selected),
            )
            if (date.value == selected && period.value == TallyPeriod.DAY) {
                existingTallyId.value = saved?.sale?.id
            }
        }
    }

    private fun resetSheet() {
        entries.value = emptyMap()
        originalEntries.value = emptyMap()
        source.value = TallySource.MANUAL
    }

    fun consumeMessage() {
        _message.value = null
    }
}

private data class AdvancedTallyResult(
    val entries: Map<String, TallyEntry>,
    val unmatched: Int,
)

private fun tallySales(sales: List<SaleWithLines>, items: List<ItemEntity>): AdvancedTallyResult {
    val tally = mutableMapOf<String, TallyEntry>()
    var unmatched = 0
    sales.flatMap { it.lines }.forEach { line ->
        val item = items.firstOrNull { it.id == line.itemId } ?: items.advancedBestNameMatch(line.itemName)
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
    return AdvancedTallyResult(tally, unmatched)
}

private fun normalized(value: Map<String, TallyEntry>): Map<String, TallyEntry> =
    value.filterValues { !it.isEmpty }

private fun entriesSummary(
    items: List<ItemEntity>,
    entries: Map<String, TallyEntry>,
    ids: Set<String>,
): String {
    if (ids.isEmpty()) return "No changes"
    return ids.mapNotNull { id ->
        val item = items.firstOrNull { it.id == id } ?: return@mapNotNull null
        val entry = entries[id] ?: TallyEntry()
        "${item.name}: S ${entry.student}, R ${entry.regular}"
    }.sorted().joinToString("; ").ifBlank { "No counted items" }
}

private fun List<ItemEntity>.advancedBestNameMatch(oldName: String): ItemEntity? {
    val old = oldName.advancedCatalogueKey()
    if (old.isBlank()) return null
    firstOrNull { it.name.advancedCatalogueKey() == old }?.let { return it }
    return map { it to it.name.advancedCatalogueKey() }
        .filter { (_, key) ->
            old.length >= 6 && key.length >= 6 && (old.startsWith(key) || key.startsWith(old))
        }
        .minByOrNull { (_, key) -> kotlin.math.abs(key.length - old.length) }
        ?.first
}

private fun String.advancedCatalogueKey(): String = lowercase()
    .replace(Regex("\\([^)]*\\)"), " ")
    .replace(Regex("\\b\\d+\\s*(pcs?|pk|packs?|s|kg|g|ml)\\b"), " ")
    .replace("seaweeds", "seaweed")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
