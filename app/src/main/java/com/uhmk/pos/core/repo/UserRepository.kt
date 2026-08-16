package com.uhmk.pos.core.repo

import com.uhmk.pos.core.db.UserDao
import com.uhmk.pos.core.db.UserEntity
import com.uhmk.pos.core.model.UserRole
import kotlinx.coroutines.flow.Flow

class UserRepository(private val dao: UserDao) {

    fun observeAll(): Flow<List<UserEntity>> = dao.observeAll()

    suspend fun getById(uid: String): UserEntity? = dao.getById(uid)
    suspend fun getByEmail(email: String): UserEntity? = dao.getByEmail(email.trim().lowercase())
    suspend fun count(): Int = dao.count()

    suspend fun save(user: UserEntity) =
        dao.upsert(user.copy(updatedAt = System.currentTimeMillis(), dirty = true))

    suspend fun saveAll(users: List<UserEntity>) = dao.upsertAll(users)

    suspend fun setActive(uid: String, active: Boolean) {
        val user = dao.getById(uid) ?: return
        save(user.copy(active = active))
    }

    suspend fun setRole(uid: String, role: UserRole) {
        val user = dao.getById(uid) ?: return
        save(user.copy(role = role))
    }

    suspend fun setFcmToken(uid: String, token: String) {
        val user = dao.getById(uid) ?: return
        if (user.fcmToken == token) return
        save(user.copy(fcmToken = token))
    }

    suspend fun delete(uid: String) = dao.delete(uid)

    /**
     * The local-mode owner account. Used when the app runs before Firebase is configured, so the
     * device still records who rang up each sale.
     */
    suspend fun ensureLocalAdmin(): UserEntity {
        dao.getById(LOCAL_ADMIN_UID)?.let { return it }
        val admin = UserEntity(
            uid = LOCAL_ADMIN_UID,
            email = "owner@local",
            displayName = "Owner",
            role = UserRole.ADMIN,
            active = true,
            dirty = false,
        )
        dao.upsert(admin)
        return admin
    }

    companion object {
        const val LOCAL_ADMIN_UID = "local-admin"
    }
}
