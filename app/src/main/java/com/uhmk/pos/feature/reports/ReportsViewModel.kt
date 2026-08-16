package com.uhmk.pos.feature.reports

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.db.CategorySalesRow
import com.uhmk.pos.core.db.DailyPoint
import com.uhmk.pos.core.db.EmployeeSalesRow
import com.uhmk.pos.core.db.ItemSalesRow
import com.uhmk.pos.core.db.OrderTypeSalesRow
import com.uhmk.pos.core.db.PaymentSalesRow
import com.uhmk.pos.core.db.RangeTotals
import com.uhmk.pos.core.db.TierSalesRow
import com.uhmk.pos.core.export.CsvExporter
import com.uhmk.pos.core.prefs.SettingsStore
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.repo.ItemRepository
import com.uhmk.pos.core.repo.SaleRepository
import com.uhmk.pos.core.time.DateRange
import com.uhmk.pos.core.time.RangePreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class ReportView(val label: String) {
    ITEMS("By item"),
    CATEGORIES("By category"),
    EMPLOYEES("Employee"),
    PAYMENTS("Payment"),
    ORDER_TYPES("Order type"),
    TIERS("By price"),
}

data class ReportsUiState(
    val range: DateRange = DateRange.of(RangePreset.TODAY),
    val totals: RangeTotals = RangeTotals(),
    val breakdown: List<ItemSalesRow> = emptyList(),
    val categories: List<CategorySalesRow> = emptyList(),
    val tiers: List<TierSalesRow> = emptyList(),
    val employees: List<EmployeeSalesRow> = emptyList(),
    val payments: List<PaymentSalesRow> = emptyList(),
    val orderTypes: List<OrderTypeSalesRow> = emptyList(),
    val daily: List<DailyPoint> = emptyList(),
    val itemImages: Map<String, String?> = emptyMap(),
    val settings: StoreSettings = StoreSettings(),
    val view: ReportView = ReportView.ITEMS,
    val exporting: Boolean = false,
) {
    val bestSeller: ItemSalesRow? get() = breakdown.maxByOrNull { it.qtySold }
    val topEarner: ItemSalesRow? get() = breakdown.maxByOrNull { it.profit }
}

private data class ReportBreakdowns(
    val categories: List<CategorySalesRow>,
    val tiers: List<TierSalesRow>,
    val employees: List<EmployeeSalesRow>,
    val payments: List<PaymentSalesRow>,
    val orderTypes: List<OrderTypeSalesRow>,
)

private data class ReportVisuals(
    val daily: List<DailyPoint>,
    val itemImages: Map<String, String?>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(
    private val saleRepository: SaleRepository,
    private val itemRepository: ItemRepository,
    private val settingsStore: SettingsStore,
    private val appContext: Context,
) : ViewModel() {

    private val range = MutableStateFlow(DateRange.of(RangePreset.TODAY))
    private val view = MutableStateFlow(ReportView.ITEMS)
    private val exporting = MutableStateFlow(false)

    private val _shareIntent = MutableStateFlow<Intent?>(null)
    val shareIntent: StateFlow<Intent?> = _shareIntent.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val state: StateFlow<ReportsUiState> = combine(
        combine(range, view, ::Pair),
        combine(
            range.flatMapLatest { saleRepository.observeTotals(it.from, it.to) },
            range.flatMapLatest { saleRepository.observeItemBreakdown(it.from, it.to) },
            ::Pair,
        ),
        combine(
            range.flatMapLatest { saleRepository.observeCategoryBreakdown(it.from, it.to) },
            range.flatMapLatest { saleRepository.observeTierBreakdown(it.from, it.to) },
            range.flatMapLatest { saleRepository.observeEmployeeBreakdown(it.from, it.to) },
            range.flatMapLatest { saleRepository.observePaymentBreakdown(it.from, it.to) },
            range.flatMapLatest { saleRepository.observeOrderTypeBreakdown(it.from, it.to) },
        ) { categories, tiers, employees, payments, orderTypes ->
            ReportBreakdowns(categories, tiers, employees, payments, orderTypes)
        },
        combine(
            range.flatMapLatest { saleRepository.observeDailySeries(it.from, it.to) },
            itemRepository.observeAll(),
        ) { daily, items ->
            ReportVisuals(daily, items.associate { it.id to it.imagePath })
        },
        combine(settingsStore.settings, exporting, ::Pair),
    ) { (r, v), (totals, items), breakdowns, visuals, (settings, busy) ->
        ReportsUiState(
            range = r,
            totals = totals,
            breakdown = items,
            categories = breakdowns.categories,
            tiers = breakdowns.tiers,
            employees = breakdowns.employees,
            payments = breakdowns.payments,
            orderTypes = breakdowns.orderTypes,
            daily = visuals.daily,
            itemImages = visuals.itemImages,
            settings = settings,
            view = v,
            exporting = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun setPreset(preset: RangePreset) {
        range.value = DateRange.of(preset)
    }

    fun setCustomRange(from: LocalDate, to: LocalDate) {
        range.value = DateRange.custom(from, to)
    }

    fun setView(value: ReportView) {
        view.value = value
    }

    fun exportItemBreakdown() = export("uhmk-profit-by-item") {
        CsvExporter.itemBreakdownCsv(state.value.breakdown, state.value.range.label)
    }

    fun exportCategoryBreakdown() = export("uhmk-by-category") {
        CsvExporter.categoryBreakdownCsv(state.value.categories, state.value.range.label)
    }

    fun exportSaleDetail() = export("uhmk-sales") {
        val current = range.value
        CsvExporter.salesCsv(saleRepository.salesWithLinesInRange(current.from, current.to))
    }

    fun exportOrderHistory() = export("uhmk-order-history") {
        val current = range.value
        CsvExporter.orderHistoryCsv(saleRepository.historyWithLinesInRange(current.from, current.to))
    }

    fun exportInventory() = export("uhmk-inventory") {
        CsvExporter.inventoryCsv(itemRepository.observeAll().first())
    }

    private fun export(baseName: String, build: suspend () -> String) {
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            runCatching { build() }.fold(
                onSuccess = { csv ->
                    CsvExporter.writeAndShare(appContext, csv, baseName).fold(
                        onSuccess = { _shareIntent.value = it },
                        onFailure = { _error.value = it.message ?: "Could not write the CSV file" },
                    )
                },
                onFailure = { _error.value = it.message ?: "Could not build the report" },
            )
            exporting.value = false
        }
    }

    fun consumeShareIntent() {
        _shareIntent.value = null
    }

    fun consumeError() {
        _error.value = null
    }
}
