package com.uhmk.pos

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.uhmk.pos.core.notify.NoticeNotifier
import com.uhmk.pos.core.prefs.StoreSettings
import com.uhmk.pos.core.ui.PosApp
import com.uhmk.pos.core.ui.theme.UhmKPosTheme
import com.uhmk.pos.core.prefs.Session
import com.uhmk.pos.feature.auth.PinLockScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

class MainActivity : ComponentActivity() {

    private val lockHandler = Handler(Looper.getMainLooper())
    private var inactivityLockEnabled = false
    private var lockWhenBackgrounded = false
    private var inactivityDelayMs = 5 * 60_000L
    private var lockAction: (() -> Unit)? = null
    private val inactivityLock = Runnable { lockAction?.invoke() }
    private val backgroundLock = Runnable { lockAction?.invoke() }

    private val requestedDestination = MutableStateFlow<String?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declining only means no notice pop-ups; the inbox still works. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as UhmKPosApp).container
        requestedDestination.value = intent.getStringExtra(EXTRA_DESTINATION)
        NoticeNotifier.createChannels(this)

        setContent {
            val settings by container.settingsStore.settings
                .collectAsState(initial = StoreSettings())
            val session by container.sessionStore.session.collectAsState(initial = Session())
            val pinFlow = remember(session.uid) { container.pinStore.pinHash(session.uid) }
            val pinHash by pinFlow.collectAsState(initial = "")
            val pinAutoFlow = remember(session.uid) { container.pinStore.autoUnlock(session.uid) }
            val pinAutoUnlock by pinAutoFlow.collectAsState(initial = true)
            val destination by requestedDestination.collectAsState()
            // Never persist the unlocked state. A recreated or relaunched activity must prove the PIN again.
            var unlockedUid by remember { mutableStateOf<String?>(null) }

            DisposableEffect(
                session.uid,
                pinHash,
                unlockedUid,
                settings.inactivityLockEnabled,
                settings.inactivityLockMinutes,
                settings.lockWhenBackgrounded,
            ) {
                inactivityLockEnabled = session.isSignedIn && pinHash.isNotBlank() &&
                    unlockedUid == session.uid && settings.inactivityLockEnabled
                lockWhenBackgrounded = session.isSignedIn && pinHash.isNotBlank() &&
                    unlockedUid == session.uid && settings.lockWhenBackgrounded
                inactivityDelayMs = settings.inactivityLockMinutes.coerceIn(1, 120) * 60_000L
                lockAction = {
                    if (session.isSignedIn && pinHash.isNotBlank()) unlockedUid = null
                }
                resetInactivityTimer()
                onDispose {
                    lockHandler.removeCallbacks(inactivityLock)
                    if (lockAction != null) lockAction = null
                }
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            LaunchedEffect(session.uid, session.role) {
                if (session.isAdmin && session.uid.isNotBlank()) {
                    combine(
                        container.itemRepository.observeMissingCostCount(),
                        container.pinStore.hasPin(session.uid),
                    ) { missing, hasPin -> missing to hasPin }
                        .collect { (missing, hasPin) ->
                            NoticeNotifier.updateAdminSetupAlerts(
                                this@MainActivity,
                                missingCostCount = missing,
                                hasPin = hasPin,
                                notices = container.noticeRepository,
                            )
                        }
                } else {
                    NoticeNotifier.clearAdminSetupAlerts(this@MainActivity)
                }
            }

            UhmKPosTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                accentIndex = settings.accentIndex,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (session.isSignedIn && pinHash.isNotBlank() &&
                        unlockedUid != session.uid) {
                        PinLockScreen(
                            userName = session.displayName.ifBlank { "User" },
                            autoUnlock = pinAutoUnlock,
                            onUnlock = { pin ->
                                val correct = container.pinStore.verify(session.uid, pin, pinHash)
                                if (correct) unlockedUid = session.uid
                                correct
                            },
                            onUseAnotherAccount = {
                                lifecycleScope.launch { container.authService.signOut() }
                            },
                        )
                    } else {
                        PosApp(
                            container = container,
                            settings = settings,
                            onLock = { unlockedUid = null },
                            canLock = pinHash.isNotBlank(),
                            requestedRoute = destination,
                            onRouteConsumed = { requestedDestination.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination.value = intent.getStringExtra(EXTRA_DESTINATION)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) resetInactivityTimer()
        return super.dispatchTouchEvent(event)
    }

    override fun onStart() {
        super.onStart()
        lockHandler.removeCallbacks(backgroundLock)
        resetInactivityTimer()
    }

    override fun onStop() {
        lockHandler.removeCallbacks(inactivityLock)
        if (lockWhenBackgrounded) {
            // A short grace period avoids locking for momentary system overlays.
            lockHandler.postDelayed(backgroundLock, 2_000L)
        }
        super.onStop()
    }

    override fun onDestroy() {
        lockHandler.removeCallbacksAndMessages(null)
        lockAction = null
        super.onDestroy()
    }

    private fun resetInactivityTimer() {
        lockHandler.removeCallbacks(inactivityLock)
        if (inactivityLockEnabled) lockHandler.postDelayed(inactivityLock, inactivityDelayMs)
    }

    companion object {
        const val EXTRA_DESTINATION = "uhmk_destination"
        const val DESTINATION_NOTICES = "notices"
        const val DESTINATION_SALES = "sales"
        const val DESTINATION_INVENTORY = "inventory"
        const val DESTINATION_SETTINGS = "settings"
    }
}
