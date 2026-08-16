package com.uhmk.pos.core.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.uhmk.pos.UhmKPosApp
import com.uhmk.pos.core.prefs.ReminderRepeat
import com.uhmk.pos.core.prefs.ScheduledReminder
import com.uhmk.pos.core.prefs.StoreSettings
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    private const val REMINDER_PREFIX = "uhmk-reminder-"
    private const val REMINDER_TAG = "uhmk-scheduled-reminders"
    private const val LOW_STOCK_DAILY = "uhmk-low-stock-daily"
    const val INPUT_REMINDER_ID = "reminder_id"

    fun scheduleAll(context: Context, settings: StoreSettings) {
        scheduleReminders(context, settings)
        scheduleLowStock(context, settings)
    }

    private fun scheduleReminders(context: Context, settings: StoreSettings) {
        val work = WorkManager.getInstance(context)
        work.cancelAllWorkByTag(REMINDER_TAG)
        settings.reminders.filter { it.enabled && (it.title.isNotBlank() || it.note.isNotBlank()) }
            .forEach { reminder ->
                val delay = if (reminder.repeat == ReminderRepeat.DAILY) {
                    delayToNext(reminder.hour, reminder.minute)
                } else {
                    delayTo(
                        LocalDate.ofEpochDay(reminder.dateEpochDay),
                        reminder.hour,
                        reminder.minute,
                    )
                }
                val input = workDataOf(INPUT_REMINDER_ID to reminder.id)
                if (reminder.repeat == ReminderRepeat.DAILY) {
                    val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(input)
                        .addTag(REMINDER_TAG)
                        .build()
                    work.enqueueUniquePeriodicWork(
                        REMINDER_PREFIX + reminder.id,
                        ExistingPeriodicWorkPolicy.REPLACE,
                        request,
                    )
                } else {
                    val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(input)
                        .addTag(REMINDER_TAG)
                        .build()
                    work.enqueueUniqueWork(
                        REMINDER_PREFIX + reminder.id,
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
                }
            }
    }

    private fun scheduleLowStock(context: Context, settings: StoreSettings) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(LOW_STOCK_DAILY)
        if (!settings.lowStockAlertEnabled) return

        val request = PeriodicWorkRequestBuilder<LowStockWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(
                delayToNext(settings.lowStockStartHour, settings.lowStockStartMinute),
                TimeUnit.MILLISECONDS,
            )
            .build()
        work.enqueueUniquePeriodicWork(
            LOW_STOCK_DAILY,
            ExistingPeriodicWorkPolicy.REPLACE,
            request,
        )
    }

    private fun delayToNext(hour: Int, minute: Int): Long {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis().coerceAtLeast(0)
    }

    private fun delayTo(date: LocalDate, hour: Int, minute: Int): Long {
        val now = java.time.ZonedDateTime.now(ZoneId.systemDefault())
        val target = date.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            .atZone(ZoneId.systemDefault())
        return Duration.between(now, target).toMillis().coerceAtLeast(0)
    }
}

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? UhmKPosApp ?: return Result.success()
        val settings = app.container.settingsStore.settings.first()
        val id = inputData.getString(ReminderScheduler.INPUT_REMINDER_ID)
            ?: return Result.success()
        val reminder = settings.reminders.firstOrNull { it.id == id && it.enabled }
            ?: return Result.success()
        NoticeNotifier.showReminder(
            applicationContext,
            reminder.title,
            reminder.note.ifBlank { "Open UhmK POS to view today's store tasks." },
            7301 + reminder.id.hashCode(),
            notices = app.container.noticeRepository,
            alertId = "alert-reminder-${reminder.id}",
        )
        if (reminder.repeat == ReminderRepeat.ONCE) {
            app.container.settingsStore.update { current ->
                current.copy(
                    reminders = current.reminders.map {
                        if (it.id == reminder.id) it.copy(enabled = false) else it
                    }
                )
            }
        }
        return Result.success()
    }
}

class LowStockWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? UhmKPosApp ?: return Result.success()
        val settings = app.container.settingsStore.settings.first()
        if (!settings.lowStockAlertEnabled || !insideAlertWindow(settings)) return Result.success()

        val low = app.container.database.itemDao().lowStockItems()
        if (low.isEmpty()) {
            // Nothing is short any more, so retire the standing warning instead of leaving it.
            app.container.noticeRepository.clearAlert(ALERT_LOW_STOCK)
            return Result.success()
        }
        val preview = low.take(5).joinToString { "${it.name} (${it.stockQty})" }
        val more = if (low.size > 5) " and ${low.size - 5} more" else ""
        NoticeNotifier.showReminder(
            applicationContext,
            "${low.size} low-stock item${if (low.size == 1) "" else "s"}",
            preview + more,
            7302,
            notices = app.container.noticeRepository,
            alertId = ALERT_LOW_STOCK,
        )
        return Result.success()
    }

    private companion object {
        const val ALERT_LOW_STOCK = "alert-low-stock"
    }

    private fun insideAlertWindow(settings: StoreSettings): Boolean {
        val now = LocalTime.now()
        val start = LocalTime.of(settings.lowStockStartHour, settings.lowStockStartMinute)
        val end = LocalTime.of(settings.lowStockEndHour, settings.lowStockEndMinute)
        return if (end >= start) now >= start && now <= end else now >= start || now <= end
    }
}
