package com.uhmk.pos.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uhmk.pos.core.model.PriceTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "uhmk_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ReminderRepeat { ONCE, DAILY }

/**
 * Where the current sale sits on a big screen.
 *
 * Ignored on phones: there is no width to dock a panel without squeezing the product grid to
 * uselessness, so a phone always gets the pop-up sheet regardless of this setting.
 */
enum class CartPanelPosition(val label: String) {
    POPUP("Pop-up (default)"),
    LEFT("Docked left"),
    RIGHT("Docked right"),
}

data class ScheduledReminder(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val title: String = "Store reminder",
    val note: String = "",
    val repeat: ReminderRepeat = ReminderRepeat.DAILY,
    val dateEpochDay: Long = LocalDate.now().toEpochDay(),
    val hour: Int = 9,
    val minute: Int = 0,
)

/** Everything the shop owner can rebrand or retune without a rebuild. */
data class StoreSettings(
    val storeName: String = "UhmK Store",
    val currencySymbol: String = "₱",
    val studentLabel: String = "Student",
    val regularLabel: String = "Regular",
    val defaultTier: PriceTier = PriceTier.REGULAR,
    val lowStockDefault: Int = 5,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentIndex: Int = 0,
    val receiptFooter: String = "Thank you!",
    /**
     * Short code identifying this device on receipts, e.g. "A".
     *
     * Blank on a single-device store, where plain numbers are unambiguous. Set it on each phone
     * once a second one starts taking orders, otherwise both print Receipt #1.
     */
    val deviceCode: String = "",
    /** Large screens only; phones always use the pop-up sheet. */
    val cartPanelPosition: CartPanelPosition = CartPanelPosition.POPUP,
    val showProfitToStaff: Boolean = false,
    val reminderEnabled: Boolean = false,
    val reminderTitle: String = "Store reminder",
    val reminderNote: String = "",
    val reminderRepeat: ReminderRepeat = ReminderRepeat.DAILY,
    val reminderDateEpochDay: Long = java.time.LocalDate.now().toEpochDay(),
    val reminderHour: Int = 9,
    val reminderMinute: Int = 0,
    /** Multiple independent reminders. Legacy single-reminder fields above are migration inputs. */
    val reminders: List<ScheduledReminder> = emptyList(),
    val lowStockAlertEnabled: Boolean = true,
    val lowStockStartHour: Int = 10,
    val lowStockStartMinute: Int = 30,
    val lowStockEndHour: Int = 21,
    val lowStockEndMinute: Int = 30,
    val salesNotificationsEnabled: Boolean = false,
    /** Cash is always available; the rest can be changed by the owner. */
    val paymentMethods: List<String> = listOf("Cash", "GCash", "QRPh", "BPI", "GoTyme"),
    /** Photo-backed product cards with a translucent text panel. */
    val immersiveProductCards: Boolean = true,
    /** Lock an unlocked till after this many minutes without a touch. */
    val inactivityLockEnabled: Boolean = true,
    val inactivityLockMinutes: Int = 5,
    /** Lock after the device screen turns off or the app is left in the background. */
    val lockWhenBackgrounded: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val storeName = stringPreferencesKey("store_name")
        val currency = stringPreferencesKey("currency_symbol")
        val studentLabel = stringPreferencesKey("student_label")
        val regularLabel = stringPreferencesKey("regular_label")
        val defaultTier = stringPreferencesKey("default_tier")
        val lowStock = intPreferencesKey("low_stock_default")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val accent = intPreferencesKey("accent_index")
        val footer = stringPreferencesKey("receipt_footer")
        val staffProfit = booleanPreferencesKey("show_profit_to_staff")
        val reminderEnabled = booleanPreferencesKey("reminder_enabled")
        val reminderTitle = stringPreferencesKey("reminder_title")
        val reminderNote = stringPreferencesKey("reminder_note")
        val reminderRepeat = stringPreferencesKey("reminder_repeat")
        val reminderDate = longPreferencesKey("reminder_date_epoch_day")
        val reminderHour = intPreferencesKey("reminder_hour")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val remindersJson = stringPreferencesKey("scheduled_reminders_json")
        val lowStockAlertEnabled = booleanPreferencesKey("low_stock_alert_enabled")
        val lowStockStartHour = intPreferencesKey("low_stock_start_hour")
        val lowStockStartMinute = intPreferencesKey("low_stock_start_minute")
        val lowStockEndHour = intPreferencesKey("low_stock_end_hour")
        val lowStockEndMinute = intPreferencesKey("low_stock_end_minute")
        val salesNotificationsEnabled = booleanPreferencesKey("sales_notifications_enabled")
        val paymentMethodsJson = stringPreferencesKey("payment_methods_json")
        val immersiveProductCards = booleanPreferencesKey("immersive_product_cards")
        val inactivityLockEnabled = booleanPreferencesKey("inactivity_lock_enabled")
        val inactivityLockMinutes = intPreferencesKey("inactivity_lock_minutes")
        val lockWhenBackgrounded = booleanPreferencesKey("lock_when_backgrounded")
        val deviceCode = stringPreferencesKey("device_code")
        val cartPanelPosition = stringPreferencesKey("cart_panel_position")
    }

    private fun Preferences.toSettings(): StoreSettings {
        val d = StoreSettings()
        val legacyReminderEnabled = this[Keys.reminderEnabled] ?: d.reminderEnabled
        val reminders = this[Keys.remindersJson]?.let(::decodeReminders) ?: if (legacyReminderEnabled) {
            listOf(
                ScheduledReminder(
                    id = "legacy-reminder",
                    enabled = true,
                    title = this[Keys.reminderTitle] ?: d.reminderTitle,
                    note = this[Keys.reminderNote] ?: d.reminderNote,
                    repeat = runCatching {
                        ReminderRepeat.valueOf(this[Keys.reminderRepeat] ?: "")
                    }.getOrDefault(d.reminderRepeat),
                    dateEpochDay = this[Keys.reminderDate] ?: d.reminderDateEpochDay,
                    hour = this[Keys.reminderHour] ?: d.reminderHour,
                    minute = this[Keys.reminderMinute] ?: d.reminderMinute,
                )
            )
        } else emptyList()
        return StoreSettings(
            storeName = this[Keys.storeName] ?: d.storeName,
            currencySymbol = this[Keys.currency] ?: d.currencySymbol,
            studentLabel = this[Keys.studentLabel] ?: d.studentLabel,
            regularLabel = this[Keys.regularLabel] ?: d.regularLabel,
            defaultTier = this[Keys.defaultTier]?.let(PriceTier::from) ?: d.defaultTier,
            lowStockDefault = this[Keys.lowStock] ?: d.lowStockDefault,
            themeMode = runCatching { ThemeMode.valueOf(this[Keys.theme] ?: "") }
                .getOrDefault(d.themeMode),
            dynamicColor = this[Keys.dynamic] ?: d.dynamicColor,
            accentIndex = this[Keys.accent] ?: d.accentIndex,
            receiptFooter = this[Keys.footer] ?: d.receiptFooter,
            deviceCode = this[Keys.deviceCode] ?: d.deviceCode,
            cartPanelPosition = runCatching {
                CartPanelPosition.valueOf(this[Keys.cartPanelPosition] ?: "")
            }.getOrDefault(d.cartPanelPosition),
            showProfitToStaff = this[Keys.staffProfit] ?: d.showProfitToStaff,
            reminderEnabled = legacyReminderEnabled,
            reminderTitle = this[Keys.reminderTitle] ?: d.reminderTitle,
            reminderNote = this[Keys.reminderNote] ?: d.reminderNote,
            reminderRepeat = runCatching {
                ReminderRepeat.valueOf(this[Keys.reminderRepeat] ?: "")
            }.getOrDefault(d.reminderRepeat),
            reminderDateEpochDay = this[Keys.reminderDate] ?: d.reminderDateEpochDay,
            reminderHour = this[Keys.reminderHour] ?: d.reminderHour,
            reminderMinute = this[Keys.reminderMinute] ?: d.reminderMinute,
            reminders = reminders,
            lowStockAlertEnabled = this[Keys.lowStockAlertEnabled]
                ?: d.lowStockAlertEnabled,
            lowStockStartHour = this[Keys.lowStockStartHour] ?: d.lowStockStartHour,
            lowStockStartMinute = this[Keys.lowStockStartMinute] ?: d.lowStockStartMinute,
            lowStockEndHour = this[Keys.lowStockEndHour] ?: d.lowStockEndHour,
            lowStockEndMinute = this[Keys.lowStockEndMinute] ?: d.lowStockEndMinute,
            salesNotificationsEnabled = this[Keys.salesNotificationsEnabled]
                ?: d.salesNotificationsEnabled,
            paymentMethods = decodePaymentMethods(this[Keys.paymentMethodsJson])
                .ifEmpty { d.paymentMethods },
            immersiveProductCards = this[Keys.immersiveProductCards]
                ?: d.immersiveProductCards,
            inactivityLockEnabled = this[Keys.inactivityLockEnabled]
                ?: d.inactivityLockEnabled,
            inactivityLockMinutes = (this[Keys.inactivityLockMinutes]
                ?: d.inactivityLockMinutes).coerceIn(1, 120),
            lockWhenBackgrounded = this[Keys.lockWhenBackgrounded]
                ?: d.lockWhenBackgrounded,
        )
    }

    val settings: Flow<StoreSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (StoreSettings) -> StoreSettings) {
        context.dataStore.edit { p ->
            val next = transform(p.toSettings())
            p[Keys.storeName] = next.storeName
            p[Keys.currency] = next.currencySymbol
            p[Keys.studentLabel] = next.studentLabel
            p[Keys.regularLabel] = next.regularLabel
            p[Keys.defaultTier] = next.defaultTier.name
            p[Keys.lowStock] = next.lowStockDefault
            p[Keys.theme] = next.themeMode.name
            p[Keys.dynamic] = next.dynamicColor
            p[Keys.accent] = next.accentIndex
            p[Keys.footer] = next.receiptFooter
            // Uppercase and trimmed to one or two characters so receipts stay short and tidy.
            p[Keys.deviceCode] = next.deviceCode.trim().uppercase().take(2)
            p[Keys.cartPanelPosition] = next.cartPanelPosition.name
            p[Keys.staffProfit] = next.showProfitToStaff
            p[Keys.reminderEnabled] = next.reminderEnabled
            p[Keys.reminderTitle] = next.reminderTitle
            p[Keys.reminderNote] = next.reminderNote
            p[Keys.reminderRepeat] = next.reminderRepeat.name
            p[Keys.reminderDate] = next.reminderDateEpochDay
            p[Keys.reminderHour] = next.reminderHour.coerceIn(0, 23)
            p[Keys.reminderMinute] = next.reminderMinute.coerceIn(0, 59)
            p[Keys.remindersJson] = encodeReminders(next.reminders)
            p[Keys.lowStockAlertEnabled] = next.lowStockAlertEnabled
            p[Keys.lowStockStartHour] = next.lowStockStartHour.coerceIn(0, 23)
            p[Keys.lowStockStartMinute] = next.lowStockStartMinute.coerceIn(0, 59)
            p[Keys.lowStockEndHour] = next.lowStockEndHour.coerceIn(0, 23)
            p[Keys.lowStockEndMinute] = next.lowStockEndMinute.coerceIn(0, 59)
            p[Keys.salesNotificationsEnabled] = next.salesNotificationsEnabled
            p[Keys.paymentMethodsJson] = encodePaymentMethods(next.paymentMethods)
            p[Keys.immersiveProductCards] = next.immersiveProductCards
            p[Keys.inactivityLockEnabled] = next.inactivityLockEnabled
            p[Keys.inactivityLockMinutes] = next.inactivityLockMinutes.coerceIn(1, 120)
            p[Keys.lockWhenBackgrounded] = next.lockWhenBackgrounded
        }
    }

    private fun encodeReminders(reminders: List<ScheduledReminder>): String =
        JSONArray().apply {
            reminders.forEach { reminder ->
                put(
                    JSONObject()
                        .put("id", reminder.id)
                        .put("enabled", reminder.enabled)
                        .put("title", reminder.title)
                        .put("note", reminder.note)
                        .put("repeat", reminder.repeat.name)
                        .put("date", reminder.dateEpochDay)
                        .put("hour", reminder.hour.coerceIn(0, 23))
                        .put("minute", reminder.minute.coerceIn(0, 59))
                )
            }
        }.toString()

    private fun decodeReminders(raw: String): List<ScheduledReminder> = runCatching {
        val array = JSONArray(raw)
        buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    ScheduledReminder(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        enabled = item.optBoolean("enabled", true),
                        title = item.optString("title", "Store reminder"),
                        note = item.optString("note", ""),
                        repeat = runCatching {
                            ReminderRepeat.valueOf(item.optString("repeat"))
                        }.getOrDefault(ReminderRepeat.DAILY),
                        dateEpochDay = item.optLong("date", LocalDate.now().toEpochDay()),
                        hour = item.optInt("hour", 9).coerceIn(0, 23),
                        minute = item.optInt("minute", 0).coerceIn(0, 59),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encodePaymentMethods(methods: List<String>): String =
        JSONArray(normalizePaymentMethods(methods)).toString()

    private fun decodePaymentMethods(raw: String?): List<String> = runCatching {
        if (raw.isNullOrBlank()) return@runCatching emptyList()
        val array = JSONArray(raw)
        normalizePaymentMethods(buildList {
            repeat(array.length()) { add(array.optString(it)) }
        })
    }.getOrDefault(emptyList())

    private fun normalizePaymentMethods(methods: List<String>): List<String> =
        (listOf("Cash") + methods)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .take(20)
}
