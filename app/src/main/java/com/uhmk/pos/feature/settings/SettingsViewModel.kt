package com.uhmk.pos.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.prefs.Session
import com.uhmk.pos.core.prefs.SessionStore
import com.uhmk.pos.core.prefs.PinStore
import com.uhmk.pos.core.prefs.SettingsStore
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.notify.ReminderScheduler
import com.uhmk.pos.feature.auth.AuthService
import com.uhmk.pos.core.repo.ItemRepository
import com.uhmk.pos.core.repo.SaleRepository
import com.uhmk.pos.core.sync.SyncManager
import com.uhmk.pos.core.sync.SyncStore
import com.uhmk.pos.core.update.AppUpdateInfo
import com.uhmk.pos.core.update.InstallLaunchResult
import com.uhmk.pos.core.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class SettingsUiState(
    val settings: StoreSettings = StoreSettings(),
    val session: Session = Session(),
    val lastSyncAt: Long = 0,
    val cloudEnabled: Boolean = false,
    val busy: Boolean = false,
    val pinEnabled: Boolean = false,
    val pinAutoUnlock: Boolean = true,
)

data class UpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int? = null,
    val available: AppUpdateInfo? = null,
    val downloadedApkPath: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val itemRepository: ItemRepository,
    private val saleRepository: SaleRepository,
    private val syncManager: SyncManager,
    private val appContext: Context,
    syncStore: SyncStore,
    private val sessionStore: SessionStore,
    private val pinStore: PinStore,
    private val authService: AuthService,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val updateManager = UpdateManager(appContext)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    val state: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        sessionStore.session,
        syncStore.lastSyncFlow,
        busy,
        sessionStore.session.flatMapLatest { session ->
            combine(pinStore.hasPin(session.uid), pinStore.autoUnlock(session.uid), ::Pair)
        },
    ) { settings, session, lastSync, isBusy, (hasPin, autoUnlock) ->
        SettingsUiState(
            settings = settings,
            session = session,
            lastSyncAt = lastSync,
            cloudEnabled = syncManager.isCloudEnabled,
            busy = isBusy,
            pinEnabled = hasPin,
            pinAutoUnlock = autoUnlock,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun update(transform: (StoreSettings) -> StoreSettings) = viewModelScope.launch {
        settingsStore.update(transform)
        ReminderScheduler.scheduleAll(appContext, settingsStore.settings.first())
    }

    fun syncNow() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            syncManager.syncAll().fold(
                onSuccess = {
                    _message.value = buildString {
                        append("Synced — ${it.itemsPushed + it.salesPushed} up, ")
                        append("${it.itemsPulled + it.salesPulled + it.noticesPulled} down")
                        if (it.itemConflictsResolved > 0) {
                            append(" · ${it.itemConflictsResolved} stale item edits merged safely")
                        }
                        if (it.knownCostsProtected > 0) {
                            append(" · ${it.knownCostsProtected} known costs protected")
                        }
                    }
                },
                onFailure = {
                    _message.value = if (syncManager.isCloudEnabled) {
                        "Sync failed: ${it.message}"
                    } else {
                        "Firebase is not connected yet"
                    }
                },
            )
            busy.value = false
        }
    }

    /** Re-applies the spreadsheet prices while keeping photos, stock and regular prices. */
    fun reseedCatalogue() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            val count = itemRepository.seedIfEmpty(force = true)
            busy.value = false
            _message.value = "Reloaded $count items from the built-in price list"
        }
    }

    fun importInventoryCsv(uri: Uri) {
        if (busy.value) return
        if (!state.value.session.isAdmin) {
            _message.value = "Only an admin can restore inventory"
            return
        }
        busy.value = true
        viewModelScope.launch {
            runCatching { itemRepository.importInventoryCsv(uri) }.fold(
                onSuccess = { plan ->
                    val sync = if (plan.items.isNotEmpty() && syncManager.isCloudEnabled) {
                        syncManager.syncAll()
                    } else null
                    _message.value = buildString {
                        append("Restored ${plan.items.size} inventory items")
                        if (plan.unchanged > 0) append(" · ${plan.unchanged} already matched")
                        if (plan.unmatched > 0) append(" · ${plan.unmatched} not found")
                        if (plan.invalidValues > 0) append(" · ${plan.invalidValues} invalid values skipped")
                        if (sync?.isFailure == true) append(" · saved here; cloud sync will retry")
                    }
                },
                onFailure = { _message.value = it.message ?: "Could not restore inventory CSV" },
            )
            busy.value = false
        }
    }

    /**
     * Clears every recorded sale, locally and in the cloud, for a clean start.
     *
     * The cloud copy has to go too — otherwise the next sync pulls the cleared sales straight back.
     */
    fun resetAllSales() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            val localRemoved = runCatching { saleRepository.resetAllSales() }.getOrDefault(0)
            val remote = syncManager.deleteAllRemoteSales()
            busy.value = false
            _message.value = when {
                remote.isFailure && syncManager.isCloudEnabled ->
                    "Cleared $localRemoved sales here, but the cloud copy could not be reached. " +
                        "Run this again while online or they may sync back."
                else -> "Cleared $localRemoved sales. Reports start from zero."
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun checkForUpdates() {
        if (_updateState.value.checking || _updateState.value.downloading) return
        _updateState.update { it.copy(checking = true) }
        viewModelScope.launch {
            updateManager.checkForUpdate().fold(
                onSuccess = { update ->
                    _updateState.value = if (update == null) {
                        UpdateUiState()
                    } else {
                        UpdateUiState(available = update)
                    }
                    if (update == null) _message.value = "You're using the latest version"
                },
                onFailure = {
                    _updateState.value = UpdateUiState()
                    _message.value = "Could not check for updates: ${it.message}"
                },
            )
        }
    }

    fun downloadAndInstallUpdate() {
        val current = _updateState.value
        val update = current.available ?: return
        if (current.downloading || current.checking) return

        val downloaded = current.downloadedApkPath
            ?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0L }
        if (downloaded != null) {
            launchInstaller(downloaded)
            return
        }

        _updateState.update { it.copy(downloading = true, progress = null) }
        viewModelScope.launch {
            updateManager.download(update) { progress ->
                _updateState.update { it.copy(progress = progress) }
            }.fold(
                onSuccess = { apk ->
                    _updateState.update {
                        it.copy(
                            downloading = false,
                            progress = 100,
                            downloadedApkPath = apk.absolutePath,
                        )
                    }
                    launchInstaller(apk)
                },
                onFailure = {
                    _updateState.update { it.copy(downloading = false, progress = null) }
                    _message.value = "Could not download the update: ${it.message}"
                },
            )
        }
    }

    fun dismissUpdate() {
        if (!_updateState.value.downloading) _updateState.value = UpdateUiState()
    }

    private fun launchInstaller(apk: File) {
        runCatching { updateManager.openInstaller(apk) }.fold(
            onSuccess = { result ->
                _message.value = when (result) {
                    InstallLaunchResult.INSTALLER_OPENED -> "Android's installer is ready"
                    InstallLaunchResult.PERMISSION_REQUIRED ->
                        "Allow UhmK POS to install updates, then return and press Install update"
                }
            },
            onFailure = { _message.value = "Could not open Android's installer: ${it.message}" },
        )
    }

    fun setPin(pin: String, confirmation: String) {
        if (pin != confirmation) {
            _message.value = "PINs do not match"
            return
        }
        if (pin.length !in 4..6 || !pin.all(Char::isDigit)) {
            _message.value = "PIN must be 4 to 6 digits"
            return
        }
        viewModelScope.launch {
            pinStore.setPin(state.value.session.uid, pin)
            _message.value = "Launch PIN enabled for ${state.value.session.displayName}"
        }
    }

    fun removePin() = viewModelScope.launch {
        pinStore.clearPin(state.value.session.uid)
        _message.value = "Launch PIN removed"
    }

    fun setPinAutoUnlock(enabled: Boolean) = viewModelScope.launch {
        pinStore.setAutoUnlock(state.value.session.uid, enabled)
    }

    fun setProfileImage(uri: Uri) = viewModelScope.launch {
        val uid = state.value.session.uid.takeIf(String::isNotBlank) ?: return@launch
        val stored = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(appContext.filesDir, "profile_images").apply { mkdirs() }
                val target = File(dir, "$uid-${UUID.randomUUID().toString().take(8)}.jpg")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                state.value.session.profileImagePath?.let { old -> runCatching { File(old).delete() } }
                target.absolutePath
            }.getOrNull()
        }
        if (stored != null) {
            sessionStore.setProfileImage(stored)
            _message.value = "Profile picture updated"
        } else {
            _message.value = "Could not save that picture"
        }
    }

    fun removeProfileImage() = viewModelScope.launch {
        state.value.session.profileImagePath?.let { path ->
            withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
        }
        sessionStore.setProfileImage(null)
        _message.value = "Profile picture removed"
    }

    fun resetPassword() = viewModelScope.launch {
        val email = state.value.session.email
        authService.sendPasswordReset(email).fold(
            onSuccess = { _message.value = "Password reset link sent to $email" },
            onFailure = { _message.value = it.message ?: "Could not send the reset link" },
        )
    }

    fun deleteAccount() = viewModelScope.launch {
        busy.value = true
        val deletingSession = state.value.session
        authService.deleteCurrentAccount().fold(
            onSuccess = {
                deletingSession.profileImagePath?.let { path ->
                    withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
                }
                sessionStore.clearProfileFor(deletingSession.uid)
                _message.value = "Account deleted"
            },
            onFailure = {
                _message.value = if (it.message.orEmpty().contains("recent", true)) {
                    "For security, sign out and sign back in, then delete the account again."
                } else it.message ?: "Could not delete the account"
            },
        )
        busy.value = false
    }
}
