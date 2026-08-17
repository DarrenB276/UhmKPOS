package com.uhmk.pos.feature.sales

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.db.RangeTotals
import com.uhmk.pos.core.db.SaleEntity
import com.uhmk.pos.core.db.SaleWithLines
import com.uhmk.pos.core.model.SaleStatus
import com.uhmk.pos.core.export.CsvExporter
import com.uhmk.pos.core.export.ReceiptFormatter
import com.uhmk.pos.core.prefs.Session
import com.uhmk.pos.core.prefs.SessionStore
import com.uhmk.pos.core.prefs.SettingsStore
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.repo.SaleRepository
import com.uhmk.pos.core.time.DateRange
import com.uhmk.pos.core.time.RangePreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class OrderHistoryFilter(val label: String) {
    ALL("All orders"),
    COMPLETED("Completed"),
    VOIDED("Voided"),
    RETURNED("Returned"),
}

data class SalesUiState(
    val range: DateRange = DateRange.of(RangePreset.TODAY),
    val sales: List<SaleEntity> = emptyList(),
    val totals: RangeTotals = RangeTotals(),
    val settings: StoreSettings = StoreSettings(),
    val session: Session = Session(),
    val statusFilter: OrderHistoryFilter = OrderHistoryFilter.ALL,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SalesViewModel(
    private val saleRepository: SaleRepository,
    settingsStore: SettingsStore,
    sessionStore: SessionStore,
) : ViewModel() {

    private val range = MutableStateFlow(DateRange.of(RangePreset.TODAY))
    private val statusFilter = MutableStateFlow(OrderHistoryFilter.ALL)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<SalesUiState> = combine(
        range,
        range.flatMapLatest { saleRepository.observeHistoryInRange(it.from, it.to) },
        range.flatMapLatest { saleRepository.observeTotals(it.from, it.to) },
        combine(settingsStore.settings, sessionStore.session, ::Pair),
        statusFilter,
    ) { r, history, totals, (settings, session), filter ->
        val sales = history.filter { sale ->
            when (filter) {
                OrderHistoryFilter.ALL -> true
                OrderHistoryFilter.COMPLETED -> sale.status == SaleStatus.COMPLETED
                OrderHistoryFilter.VOIDED -> sale.status == SaleStatus.VOIDED
                OrderHistoryFilter.RETURNED -> sale.status == SaleStatus.RETURNED
            }
        }
        SalesUiState(r, sales, totals, settings, session, filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SalesUiState())

    fun setPreset(preset: RangePreset) {
        range.value = DateRange.of(preset)
    }

    fun setCustomRange(from: LocalDate, to: LocalDate) {
        range.value = DateRange.custom(from, to)
    }

    fun setStatusFilter(filter: OrderHistoryFilter) {
        statusFilter.value = filter
    }

    fun voidSale(saleId: String) = viewModelScope.launch {
        saleRepository.voidSale(saleId).fold(
            onSuccess = { _message.value = "Sale voided and stock returned" },
            onFailure = { _message.value = it.message ?: "Could not void that sale" },
        )
    }

    fun returnSale(saleId: String, reason: String) = viewModelScope.launch {
        saleRepository.returnSale(saleId, reason).fold(
            onSuccess = { _message.value = "Order returned and stock restored" },
            onFailure = { _message.value = it.message ?: "Could not return that order" },
        )
    }

    fun consumeMessage() {
        _message.value = null
    }
}

data class ReceiptUiState(
    val sale: SaleWithLines? = null,
    val settings: StoreSettings = StoreSettings(),
    val session: Session = Session(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptViewModel(
    private val saleRepository: SaleRepository,
    private val settingsStore: SettingsStore,
    private val sessionStore: SessionStore,
    private val syncManager: com.uhmk.pos.core.sync.SyncManager,
    private val appContext: Context,
) : ViewModel() {

    private val saleId = MutableStateFlow("")

    private val _shareIntent = MutableStateFlow<Intent?>(null)
    val shareIntent: StateFlow<Intent?> = _shareIntent.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<ReceiptUiState> = combine(
        saleId.flatMapLatest { id ->
            if (id.isBlank()) kotlinx.coroutines.flow.flowOf(null) else saleRepository.observeSale(id)
        },
        settingsStore.settings,
        sessionStore.session,
    ) { sale, settings, session ->
        ReceiptUiState(sale = sale, settings = settings, session = session, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptUiState())

    fun load(id: String) {
        saleId.value = id
    }

    fun receiptText(): String {
        val s = state.value
        val sale = s.sale ?: return ""
        return ReceiptFormatter.render(sale, s.settings, showProfit = false)
    }

    /** Shares the receipt as plain text — works with chat apps and thermal printer apps alike. */
    fun shareReceipt() {
        val text = receiptText()
        if (text.isBlank()) return
        _shareIntent.value = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Receipt ${state.value.sale?.sale?.receiptLabel.orEmpty()}")
        }
    }

    /** Saves the receipt as a .txt file, for keeping or printing later. */
    fun exportReceipt() {
        val text = receiptText()
        if (text.isBlank()) return
        viewModelScope.launch {
            CsvExporter.writeAndShare(
                appContext,
                text,
                "receipt-${state.value.sale?.sale?.receiptLabel ?: "0"}",
                mime = "text/plain",
                extension = "txt",
            ).onSuccess { _shareIntent.value = it }
        }
    }

    fun voidSale(onDone: () -> Unit) {
        val id = state.value.sale?.sale?.id ?: return
        viewModelScope.launch {
            saleRepository.voidSale(id).fold(
                onSuccess = {
                    _message.value = "Sale voided and stock returned"
                    onDone()
                },
                onFailure = { _message.value = it.message ?: "Could not void that sale" },
            )
        }
    }

    /**
     * Erases a receipt outright, locally and in the cloud.
     *
     * Different from voiding: a void keeps the receipt as a record of a mistake, which is what an
     * audit trail wants. This is for entries that never represented trade at all — test orders from
     * setting the till up — where leaving them in history is just noise. Stock is left where it is,
     * because counts have usually been corrected by hand since.
     */
    fun deleteSale(onDone: () -> Unit) {
        val sale = state.value.sale?.sale ?: return
        if (!state.value.session.isAdmin) {
            _message.value = "Only an admin can delete a receipt"
            return
        }
        viewModelScope.launch {
            val removed = saleRepository.deleteSales(listOf(sale.id))
            val cloud = syncManager.deleteRemoteSales(listOf(sale.id))
            _message.value = when {
                removed == 0 -> "That receipt was already gone"
                cloud.isFailure && syncManager.isCloudEnabled ->
                    "Receipt ${sale.receiptLabel} deleted here, but the cloud copy could not be " +
                        "reached. Delete it again while online or it may sync back."
                else -> "Receipt ${sale.receiptLabel} deleted"
            }
            onDone()
        }
    }

    fun returnSale(reason: String, onDone: () -> Unit) {
        val id = state.value.sale?.sale?.id ?: return
        viewModelScope.launch {
            saleRepository.returnSale(id, reason).fold(
                onSuccess = {
                    _message.value = "Order returned and stock restored"
                    onDone()
                },
                onFailure = { _message.value = it.message ?: "Could not return that order" },
            )
        }
    }

    fun consumeShareIntent() {
        _shareIntent.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}
