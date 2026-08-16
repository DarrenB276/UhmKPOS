package com.uhmk.pos.core.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.uhmk.pos.core.AppContainer
import com.uhmk.pos.feature.auth.AuthViewModel
import com.uhmk.pos.feature.inventory.InventoryViewModel
import com.uhmk.pos.feature.inventory.ItemEditViewModel
import com.uhmk.pos.feature.notices.NoticesViewModel
import com.uhmk.pos.feature.reports.ReportsViewModel
import com.uhmk.pos.feature.sales.ReceiptViewModel
import com.uhmk.pos.feature.sales.SalesViewModel
import com.uhmk.pos.feature.sell.SellViewModel
import com.uhmk.pos.feature.settings.SettingsViewModel
import com.uhmk.pos.feature.tally.AdvancedDayTallyViewModel

/** Single place that knows how to build every ViewModel, since there is no DI framework. */
fun appViewModelFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {

    initializer {
        SellViewModel(
            itemRepository = container.itemRepository,
            saleRepository = container.saleRepository,
            ticketRepository = container.ticketRepository,
            salesLayoutStore = container.salesLayoutStore,
            syncManager = container.syncManager,
            settingsStore = container.settingsStore,
            sessionStore = container.sessionStore,
        )
    }

    initializer {
        InventoryViewModel(
            repository = container.itemRepository,
            settingsStore = container.settingsStore,
            sessionStore = container.sessionStore,
        )
    }

    initializer {
        ItemEditViewModel(
            repository = container.itemRepository,
            settingsStore = container.settingsStore,
        )
    }

    initializer {
        ReportsViewModel(
            saleRepository = container.saleRepository,
            itemRepository = container.itemRepository,
            settingsStore = container.settingsStore,
            appContext = container.context,
        )
    }

    initializer {
        AdvancedDayTallyViewModel(
            itemRepository = container.itemRepository,
            saleRepository = container.saleRepository,
            settingsStore = container.settingsStore,
            sessionStore = container.sessionStore,
            pinStore = container.pinStore,
        )
    }

    initializer {
        SalesViewModel(
            saleRepository = container.saleRepository,
            settingsStore = container.settingsStore,
            sessionStore = container.sessionStore,
        )
    }

    initializer {
        ReceiptViewModel(
            saleRepository = container.saleRepository,
            settingsStore = container.settingsStore,
            sessionStore = container.sessionStore,
            appContext = container.context,
        )
    }

    initializer {
        NoticesViewModel(
            repository = container.noticeRepository,
            userRepository = container.userRepository,
            syncManager = container.syncManager,
            sessionStore = container.sessionStore,
        )
    }

    initializer {
        SettingsViewModel(
            settingsStore = container.settingsStore,
            itemRepository = container.itemRepository,
            saleRepository = container.saleRepository,
            syncManager = container.syncManager,
            appContext = container.context,
            syncStore = container.syncStore,
            sessionStore = container.sessionStore,
            pinStore = container.pinStore,
            authService = container.authService,
        )
    }

    initializer {
        AuthViewModel(
            authService = container.authService,
            userRepository = container.userRepository,
            syncManager = container.syncManager,
            appContext = container.context,
        )
    }
}
