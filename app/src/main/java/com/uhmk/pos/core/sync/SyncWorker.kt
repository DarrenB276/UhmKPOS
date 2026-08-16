package com.uhmk.pos.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.uhmk.pos.UhmKPosApp
import com.uhmk.pos.core.notify.NoticeNotifier
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync.
 *
 * Doubles as the safety net for staff notices: if the listener service was killed, this still
 * pulls anything new and raises a notification, so a message is never silently lost.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? UhmKPosApp)?.container ?: return Result.success()
        if (!container.syncManager.isCloudEnabled) return Result.success()

        val before = container.noticeRepository.latestSentAt()

        return container.syncManager.syncAll().fold(
            onSuccess = {
                if (it.noticesPulled > 0) {
                    NoticeNotifier.notifyNewSince(applicationContext, container, before)
                }
                Result.success()
            },
            // Retry rather than fail: a POS is often on flaky shop wifi.
            onFailure = { if (runAttemptCount < 5) Result.retry() else Result.success() },
        )
    }

    companion object {
        private const val NAME = "uhmk-periodic-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
