package com.uhmk.pos.core.repo

import com.uhmk.pos.core.db.NoticeDao
import com.uhmk.pos.core.db.NoticeEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class NoticeRepository(private val dao: NoticeDao) {

    fun observeAll(): Flow<List<NoticeEntity>> = dao.observeAll()
    fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    suspend fun getById(id: String): NoticeEntity? = dao.getById(id)
    suspend fun latestSentAt(): Long = dao.latestSentAt() ?: 0L
    suspend fun latestUpdatedAt(): Long = dao.latestUpdatedAt() ?: 0L

    /** Composes a notice locally; the sync layer pushes it to staff devices. */
    suspend fun compose(
        title: String,
        body: String,
        senderName: String,
        targetUid: String = "",
        targetName: String = "Everyone",
    ): NoticeEntity {
        val notice = NoticeEntity(
            id = "ntc-" + UUID.randomUUID().toString(),
            title = title.trim(),
            body = body.trim(),
            senderName = senderName,
            targetUid = targetUid,
            targetName = targetName,
            sentAt = System.currentTimeMillis(),
            // The author has obviously already seen it.
            readAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            dirty = true,
        )
        dao.upsert(notice)
        return notice
    }

    suspend fun saveAll(notices: List<NoticeEntity>) = dao.upsertAll(notices)

    /**
     * Records an app-raised alert so it shows in the notices list, not only in the system tray.
     *
     * The id is stable per alert, so re-running a check updates the existing row instead of
     * stacking up duplicates every fifteen minutes. Alerts are never marked dirty: they describe
     * the state of *this* device, and pushing "set a passcode" to every staff phone would be noise.
     */
    suspend fun postAlert(id: String, title: String, body: String): NoticeEntity {
        val now = System.currentTimeMillis()
        val existing = dao.getById(id)

        // Keep the original timestamp and read state so a standing alert does not jump back to the
        // top of the list, or mark itself unread again, on every re-check.
        val notice = NoticeEntity(
            id = id,
            title = title.trim(),
            body = body.trim(),
            senderName = "UhmK POS",
            targetName = "This device",
            sentAt = existing?.sentAt ?: now,
            readAt = if (existing?.title == title && existing.body == body) existing.readAt else null,
            updatedAt = now,
            deletedAt = null,
            kind = NoticeEntity.KIND_ALERT,
            dirty = false,
        )
        dao.upsert(notice)
        return notice
    }

    /**
     * Drops an alert once its cause is resolved, e.g. every cost has been filled in.
     * Removed outright rather than tombstoned — alerts never sync, so there is nothing to tell
     * other devices about.
     */
    suspend fun clearAlert(id: String) {
        if (dao.getById(id) == null) return
        dao.delete(id)
    }

    suspend fun markRead(id: String) = dao.markRead(id, System.currentTimeMillis())
    suspend fun markAllRead() = dao.markAllRead(System.currentTimeMillis())
    suspend fun delete(id: String): NoticeEntity? {
        val now = System.currentTimeMillis()
        dao.markDeleted(id, now)
        return dao.getById(id)
    }
}
