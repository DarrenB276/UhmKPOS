package com.uhmk.pos.core.notify

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.uhmk.pos.UhmKPosApp
import com.uhmk.pos.core.db.NoticeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receives true server-sent push.
 *
 * Nothing sends to this yet, because pushing FCM requires a trusted sender and Cloud Functions is
 * a Blaze-plan feature. It is wired up in advance so that upgrading is a Cloud Function deploy
 * rather than an app change — the token is already registered and the handler already works.
 */
class PosFcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val container = (application as? UhmKPosApp)?.container ?: return
        scope.launch {
            val session = container.sessionStore.session.first()
            if (session.uid.isBlank()) return@launch
            container.userRepository.setFcmToken(session.uid, token)
            container.syncManager.registerFcmToken(session.uid, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val container = (application as? UhmKPosApp)?.container ?: return
        val data = message.data

        val notice = NoticeEntity(
            id = data["id"] ?: message.messageId ?: System.currentTimeMillis().toString(),
            title = data["title"] ?: message.notification?.title.orEmpty(),
            body = data["body"] ?: message.notification?.body.orEmpty(),
            senderName = data["senderName"] ?: "Admin",
            sentAt = data["sentAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
            readAt = null,
            dirty = false,
        )

        scope.launch {
            if (container.noticeRepository.getById(notice.id) != null) return@launch
            container.noticeRepository.saveAll(listOf(notice))
            NoticeNotifier.show(applicationContext, notice)
        }
    }
}
