package com.uhmk.pos.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "uhmk_sync")

/** Remembers how far the last successful pull got, so the next one only asks for newer rows. */
class SyncStore(private val context: Context) {

    private val lastSyncKey = longPreferencesKey("last_sync_at")
    private val lastNoticeSeenKey = longPreferencesKey("last_notice_seen")
    private val lastSaleAlertSeenKey = longPreferencesKey("last_sale_alert_seen")

    val lastSyncFlow: Flow<Long> = context.syncDataStore.data.map { it[lastSyncKey] ?: 0L }

    suspend fun lastSyncAt(): Long = lastSyncFlow.first()

    suspend fun setLastSyncAt(value: Long) {
        context.syncDataStore.edit { it[lastSyncKey] = value }
    }

    suspend fun lastNoticeSeen(): Long =
        context.syncDataStore.data.map { it[lastNoticeSeenKey] ?: 0L }.first()

    suspend fun setLastNoticeSeen(value: Long) {
        context.syncDataStore.edit { it[lastNoticeSeenKey] = value }
    }

    suspend fun lastSaleAlertSeen(): Long =
        context.syncDataStore.data.map { it[lastSaleAlertSeenKey] ?: 0L }.first()

    suspend fun setLastSaleAlertSeen(value: Long) {
        context.syncDataStore.edit { it[lastSaleAlertSeenKey] = value }
    }
}
