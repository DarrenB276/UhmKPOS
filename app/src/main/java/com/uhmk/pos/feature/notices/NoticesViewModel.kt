package com.uhmk.pos.feature.notices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.db.NoticeEntity
import com.uhmk.pos.core.prefs.Session
import com.uhmk.pos.core.prefs.SessionStore
import com.uhmk.pos.core.repo.NoticeRepository
import com.uhmk.pos.core.repo.UserRepository
import com.uhmk.pos.core.db.UserEntity
import com.uhmk.pos.core.sync.SyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NoticesUiState(
    val notices: List<NoticeEntity> = emptyList(),
    val unread: Int = 0,
    val session: Session = Session(),
    val sending: Boolean = false,
    val recipients: List<UserEntity> = emptyList(),
)

class NoticesViewModel(
    private val repository: NoticeRepository,
    userRepository: UserRepository,
    private val syncManager: SyncManager,
    sessionStore: SessionStore,
) : ViewModel() {

    private val sending = MutableStateFlow(false)

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    val state: StateFlow<NoticesUiState> = combine(
        repository.observeAll(),
        sessionStore.session,
        userRepository.observeAll(),
        sending,
    ) { notices, session, users, busy ->
        val visible = if (session.isAdmin) notices else notices.filter {
            it.targetUid.isBlank() || it.targetUid == session.uid
        }
        NoticesUiState(
            notices = visible,
            unread = visible.count { !it.isRead && !it.isDeleted },
            session = session,
            sending = busy,
            recipients = users.filter {
                it.active && it.uid != session.uid && it.email != "owner@local"
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoticesUiState())

    fun send(title: String, body: String, targetUid: String?, targetName: String) {
        if (title.isBlank() && body.isBlank()) return
        if (sending.value) return

        sending.value = true
        viewModelScope.launch {
            val sender = state.value.session.displayName.ifBlank { "Admin" }
            val notice = repository.compose(
                title = title,
                body = body,
                senderName = sender,
                targetUid = targetUid.orEmpty(),
                targetName = targetName.ifBlank { "Everyone" },
            )

            // Saved locally first, so the message is never lost if the push fails.
            val pushed = syncManager.pushNotice(notice)
            sending.value = false
            _result.value = when {
                pushed.isSuccess -> if (targetUid.isNullOrBlank()) "Sent to everyone" else "Sent to $targetName"
                syncManager.isCloudEnabled -> "Saved, but could not reach the cloud. It will retry."
                else -> "Saved on this device. Connect Firebase to reach staff phones."
            }
        }
    }

    fun markRead(id: String) = viewModelScope.launch { repository.markRead(id) }
    fun markAllRead() = viewModelScope.launch { repository.markAllRead() }
    fun delete(id: String) = viewModelScope.launch {
        if (!state.value.session.isAdmin) {
            _result.value = "Only an admin can delete messages"
            return@launch
        }
        val tombstone = repository.delete(id) ?: return@launch
        val pushed = if (syncManager.isCloudEnabled) syncManager.pushNotice(tombstone)
        else Result.success(Unit)
        _result.value = if (pushed.isSuccess) "Message deleted" else {
            "Deleted here; cloud removal will retry on the next sync"
        }
    }

    fun consumeResult() {
        _result.value = null
    }
}
