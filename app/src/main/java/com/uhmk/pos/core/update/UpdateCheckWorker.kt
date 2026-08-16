package com.uhmk.pos.core.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.uhmk.pos.core.notify.NoticeNotifier
import java.util.concurrent.TimeUnit

/** Schedules a prompt online check and a battery-friendly background check every six hours. */
object UpdateCheckScheduler {
    private const val PERIODIC_NAME = "uhmk-app-update-periodic"
    private const val STARTUP_NAME = "uhmk-app-update-startup"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = WorkManager.getInstance(context)

        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setInitialDelay(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        work.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )

        val startup = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .build()
        work.enqueueUniqueWork(STARTUP_NAME, ExistingWorkPolicy.KEEP, startup)
    }
}

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!UpdateNotificationPolicy.shouldCheck(preferences.getLong(LAST_CHECKED_AT, 0L), now)) {
            return Result.success()
        }

        return UpdateManager(applicationContext).checkForUpdate().fold(
            onSuccess = { update ->
                preferences.edit().putLong(LAST_CHECKED_AT, now).apply()
                if (update == null) {
                    NoticeNotifier.clearUpdateAvailable(applicationContext)
                } else {
                    val key = UpdateNotificationPolicy.notificationKey(update)
                    val previous = preferences.getString(LAST_NOTIFIED_VERSION, null)
                    if (UpdateNotificationPolicy.shouldNotify(previous, update) &&
                        NoticeNotifier.showUpdateAvailable(applicationContext, update)
                    ) {
                        preferences.edit().putString(LAST_NOTIFIED_VERSION, key).apply()
                    }
                }
                Result.success()
            },
            onFailure = {
                if (runAttemptCount < 3) Result.retry() else Result.success()
            },
        )
    }

    private companion object {
        const val PREFERENCES = "app_update_notifications"
        const val LAST_CHECKED_AT = "last_checked_at"
        const val LAST_NOTIFIED_VERSION = "last_notified_version"
    }
}

internal object UpdateNotificationPolicy {
    // Slightly shorter than the six-hour periodic interval so normal scheduler jitter never
    // suppresses a due check, while repeated app launches still avoid hammering GitHub.
    internal const val MIN_CHECK_INTERVAL_MS = 5L * 60L * 60L * 1_000L

    fun shouldCheck(lastCheckedAt: Long, now: Long): Boolean =
        lastCheckedAt <= 0L || now < lastCheckedAt || now - lastCheckedAt >= MIN_CHECK_INTERVAL_MS

    fun notificationKey(update: AppUpdateInfo): String =
        update.versionCode?.let { "code:$it" } ?: "name:${update.versionName}"

    fun shouldNotify(lastNotifiedKey: String?, update: AppUpdateInfo): Boolean =
        lastNotifiedKey != notificationKey(update)
}
