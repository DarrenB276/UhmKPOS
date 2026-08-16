package com.uhmk.pos.core

import android.content.Context
import com.uhmk.pos.core.db.AppDatabase
import com.uhmk.pos.core.prefs.SessionStore
import com.uhmk.pos.core.prefs.SettingsStore
import com.uhmk.pos.core.prefs.PinStore
import com.uhmk.pos.core.prefs.SalesLayoutStore
import com.uhmk.pos.core.repo.ItemRepository
import com.uhmk.pos.core.repo.NoticeRepository
import com.uhmk.pos.core.repo.SaleRepository
import com.uhmk.pos.core.repo.UserRepository
import com.uhmk.pos.core.repo.TicketRepository
import com.uhmk.pos.core.sync.SyncManager
import com.uhmk.pos.core.sync.SyncStore
import com.uhmk.pos.feature.auth.AuthService

/**
 * Manual dependency wiring.
 *
 * An app this size does not need an annotation-processing DI framework, and skipping one keeps
 * the build simple and fast. Everything is created lazily and lives for the process lifetime.
 */
class AppContainer(val context: Context) {

    val database: AppDatabase by lazy { AppDatabase.build(context) }

    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
    val sessionStore: SessionStore by lazy { SessionStore(context) }
    val pinStore: PinStore by lazy { PinStore(context) }
    val salesLayoutStore: SalesLayoutStore by lazy { SalesLayoutStore(context) }
    val syncStore: SyncStore by lazy { SyncStore(context) }

    val itemRepository: ItemRepository by lazy { ItemRepository(context, database.itemDao()) }
    val saleRepository: SaleRepository by lazy { SaleRepository(database) }
    val userRepository: UserRepository by lazy { UserRepository(database.userDao()) }
    val noticeRepository: NoticeRepository by lazy { NoticeRepository(database.noticeDao()) }
    val ticketRepository: TicketRepository by lazy { TicketRepository(database) }

    val syncManager: SyncManager by lazy {
        SyncManager(context, database, syncStore, sessionStore)
    }

    val authService: AuthService by lazy { AuthService(context, userRepository, sessionStore) }
}
