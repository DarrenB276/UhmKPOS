package com.uhmk.pos.core.notify

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.uhmk.pos.MainActivity
import com.uhmk.pos.R
import com.uhmk.pos.core.AppContainer
import com.uhmk.pos.core.db.NoticeEntity
import com.uhmk.pos.core.db.SaleEntity
import com.uhmk.pos.core.money.Money
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.repo.NoticeRepository
import com.uhmk.pos.core.time.Clock
import kotlinx.coroutines.flow.first

object NoticeNotifier {

    const val CHANNEL_NOTICES = "staff_notices"
    const val CHANNEL_SERVICE = "background_sync"
    const val CHANNEL_REMINDERS = "store_reminders"
    const val CHANNEL_SALES = "admin_sale_alerts"
    const val CHANNEL_SECURITY = "admin_setup_alerts"
    private const val GROUP = "com.uhmk.pos.NOTICES"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SECURITY,
                "Admin setup alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Missing item costs and account security reminders"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NOTICES,
                context.getString(R.string.notice_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notice_channel_desc)
                enableVibration(true)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SALES,
                "Sales alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "New-sale notifications for store administrators"
                enableVibration(true)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_channel_desc)
                enableVibration(true)
            }
        )

        manager.createNotificationChannel(
            // Deliberately low importance: the persistent "listening" notice is required by the
            // platform but should never make a sound or interrupt anyone at the counter.
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.sync_channel_desc)
                setShowBadge(false)
            }
        )
    }

    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("Listening for staff notices")
            .setContentText("Tap to open UhmK POS")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()

    // Permission is checked by canPost immediately below. Lint cannot follow that helper.
    @SuppressLint("MissingPermission")
    fun show(context: Context, notice: NoticeEntity) {
        if (!canPost(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_NOTICES)
            .setContentTitle("New message from ${notice.senderName}")
            .setContentText("Tap to read it in UhmK POS")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setContentIntent(openAppIntent(context, MainActivity.DESTINATION_NOTICES))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notice.id.hashCode(), notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun showSale(context: Context, sale: SaleEntity, settings: StoreSettings) {
        if (!canPost(context)) return
        val detail = "Receipt ${sale.receiptLabel} · ${Money.format(sale.netCentavos, settings.currencySymbol)} · ${Clock.stamp(sale.soldAt)}"
        val notification = NotificationCompat.Builder(context, CHANNEL_SALES)
            .setContentTitle("New sale by ${sale.cashierName}")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, MainActivity.DESTINATION_SALES))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(sale.id.hashCode(), notification)
        }
    }

    /**
     * Raises a reminder in the system tray and records it in the in-app notices list.
     *
     * The in-app copy is written whether or not notifications are permitted — if the phone is
     * silencing us, the list is the only place the reminder will ever be seen.
     */
    @SuppressLint("MissingPermission")
    suspend fun showReminder(
        context: Context,
        title: String,
        body: String,
        notificationId: Int,
        notices: NoticeRepository? = null,
        alertId: String = "alert-reminder-$notificationId",
    ) {
        val heading = title.ifBlank { "Store reminder" }
        notices?.postAlert(alertId, heading, body)

        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, MainActivity.DESTINATION_NOTICES))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    /**
     * Keeps the two standing admin warnings in step with reality, in the tray and in the app.
     *
     * Both are cleared the moment their cause is fixed, so the list never carries a warning about
     * something already dealt with.
     */
    @SuppressLint("MissingPermission")
    suspend fun updateAdminSetupAlerts(
        context: Context,
        missingCostCount: Int,
        hasPin: Boolean,
        notices: NoticeRepository? = null,
    ) {
        val manager = NotificationManagerCompat.from(context)

        if (missingCostCount > 0) {
            val detail = "$missingCostCount active item${if (missingCostCount == 1) " has" else "s have"} no cost/SRP entered. Profit is incomplete until this is fixed."
            notices?.postAlert(ALERT_MISSING_COST, "Some inventory costs are missing", detail)
            if (canPost(context)) {
                val notification = NotificationCompat.Builder(context, CHANNEL_SECURITY)
                    .setContentTitle("Some inventory costs are missing")
                    .setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent(context, MainActivity.DESTINATION_INVENTORY))
                    .build()
                runCatching { manager.notify(MISSING_COST_ID, notification) }
            }
        } else {
            manager.cancel(MISSING_COST_ID)
            notices?.clearAlert(ALERT_MISSING_COST)
        }

        if (!hasPin) {
            val detail = "Set a passcode in Account settings to protect the till and reports."
            notices?.postAlert(ALERT_NO_PIN, "Secure your admin account", detail)
            if (canPost(context)) {
                val notification = NotificationCompat.Builder(context, CHANNEL_SECURITY)
                    .setContentTitle("Secure your admin account")
                    .setContentText(detail)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent(context, MainActivity.DESTINATION_SETTINGS))
                    .build()
                runCatching { manager.notify(MISSING_PIN_ID, notification) }
            }
        } else {
            manager.cancel(MISSING_PIN_ID)
            notices?.clearAlert(ALERT_NO_PIN)
        }
    }

    fun clearAdminSetupAlerts(context: Context) {
        NotificationManagerCompat.from(context).apply {
            cancel(MISSING_COST_ID)
            cancel(MISSING_PIN_ID)
        }
    }

    /** Raises a notification for every notice stored after [since]. */
    suspend fun notifyNewSince(context: Context, container: AppContainer, since: Long) {
        val fresh = container.noticeRepository.observeAll().first()
            .filter { it.sentAt > since && !it.isRead }
        fresh.forEach { show(context, it) }
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context, destination: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            destination?.let { putExtra(MainActivity.EXTRA_DESTINATION, it) }
        }
        return PendingIntent.getActivity(
            context,
            destination?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val MISSING_COST_ID = 4810
    private const val MISSING_PIN_ID = 4811

    // Stable ids so re-running a check refreshes the existing entry instead of adding another.
    private const val ALERT_MISSING_COST = "alert-missing-cost"
    private const val ALERT_NO_PIN = "alert-no-pin"
}
