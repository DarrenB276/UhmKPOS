package com.uhmk.pos

import android.app.Application
import com.uhmk.pos.core.AppContainer
import com.uhmk.pos.core.notify.NoticeListenerService
import com.uhmk.pos.core.notify.NoticeNotifier
import com.uhmk.pos.core.notify.ReminderScheduler
import com.uhmk.pos.core.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class UhmKPosApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NoticeNotifier.createChannels(this)

        appScope.launch {
            // First launch fills the catalogue from the store spreadsheet.
            container.itemRepository.seedIfEmpty()
            container.userRepository.ensureLocalAdmin()
            ReminderScheduler.scheduleAll(
                this@UhmKPosApp,
                container.settingsStore.settings.first(),
            )

            // Cloud work only makes sense once a real Firebase config is in place.
            if (container.syncManager.isCloudEnabled) {
                SyncWorker.schedule(this@UhmKPosApp)
                NoticeListenerService.start(this@UhmKPosApp)
                container.syncManager.syncAll()

                // Reconcile sessions saved by an older build after user profiles have been
                // downloaded. This repairs the old first-login-as-STAFF issue automatically.
                val session = container.sessionStore.session.first()
                if (session.isSignedIn && !session.offline) {
                    val profile = container.userRepository.getById(session.uid)
                    when {
                        profile == null -> Unit
                        !profile.active -> container.sessionStore.signOut()
                        profile.role != session.role || profile.displayName != session.displayName ->
                            container.sessionStore.signIn(
                                session.copy(
                                    displayName = profile.displayName,
                                    email = profile.email,
                                    role = profile.role,
                                )
                            )
                    }
                }
            }
        }
    }
}
