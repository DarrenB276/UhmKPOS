package com.uhmk.pos.core.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.uhmk.pos.UhmKPosApp
import com.uhmk.pos.core.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps a Firestore listener open so notices from the admin arrive promptly.
 *
 * Real server-sent push would need Cloud Functions, which is Blaze-only. Running the listener in a
 * foreground service is the free-tier equivalent: the device watches the `notices` collection
 * itself and raises a local notification when something new lands.
 *
 * [com.uhmk.pos.core.sync.SyncWorker] backstops this every 15 minutes in case the service is killed.
 */
class NoticeListenerService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)
    private var listening: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NoticeNotifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as? UhmKPosApp)?.container

        if (container == null || !container.syncManager.isCloudEnabled) {
            // Nothing to listen to without a real Firebase project.
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        if (listening == null) listening = watch(container)
        return START_STICKY
    }

    private fun startAsForeground() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                NoticeNotifier.serviceNotification(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        }.onFailure {
            Log.w(TAG, "Could not enter foreground: ${it.message}")
            stopSelf()
        }
    }

    private fun watch(container: AppContainer) = scope.launch {
        launch { watchNotices(container) }
        launch { watchSales(container) }
    }

    private suspend fun watchNotices(container: AppContainer) {
        val syncStore = container.syncStore
        val since = maxOf(container.noticeRepository.latestUpdatedAt(), syncStore.lastNoticeSeen())

        container.syncManager.observeRemoteNotices(since)
            .catch { Log.w(TAG, "Notice stream ended: ${it.message}") }
            .collect { remote ->
                if (remote.isEmpty()) return@collect

                val known = container.noticeRepository
                val incoming = remote.filter {
                    val local = known.getById(it.id)
                    local == null || it.updatedAt > local.updatedAt
                }
                if (incoming.isEmpty()) return@collect

                known.saveAll(incoming)
                val session = container.sessionStore.session.first()
                incoming.filterNot { it.isDeleted }
                    .filter { session.isAdmin || it.targetUid.isBlank() || it.targetUid == session.uid }
                    .forEach { NoticeNotifier.show(this@NoticeListenerService, it) }
                syncStore.setLastNoticeSeen(incoming.maxOf { it.updatedAt })
            }
    }

    private suspend fun watchSales(container: AppContainer) {
        if (!container.sessionStore.session.first().isAdmin) return
        val syncStore = container.syncStore
        val savedCursor = syncStore.lastSaleAlertSeen()
        val since = if (savedCursor > 0) savedCursor else System.currentTimeMillis()
        var cursor = since
        if (savedCursor == 0L) syncStore.setLastSaleAlertSeen(since)

        container.syncManager.observeRemoteSales(since)
            .catch { Log.w(TAG, "Sale-alert stream ended: ${it.message}") }
            .collect { sales ->
                if (sales.isEmpty()) return@collect
                val settings = container.settingsStore.settings.first()
                sales.filter { it.updatedAt > cursor && it.voidedAt == null && it.returnedAt == null }
                    .forEach { sale ->
                        if (settings.salesNotificationsEnabled) {
                            NoticeNotifier.showSale(this@NoticeListenerService, sale, settings)
                        }
                    }
                cursor = maxOf(cursor, sales.maxOf { it.updatedAt })
                syncStore.setLastSaleAlertSeen(cursor)
            }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NoticeListener"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, NoticeListenerService::class.java)
                ContextCompatStart(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, NoticeListenerService::class.java)) }
        }

        private fun ContextCompatStart(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
