package com.uhmk.pos.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uhmk.pos.core.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore(name = "uhmk_session")

data class Session(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val role: UserRole = UserRole.STAFF,
    /** True when signed in without Firebase, i.e. the device is running standalone. */
    val offline: Boolean = false,
    /** Device-private copy selected by this account. */
    val profileImagePath: String? = null,
) {
    val isSignedIn: Boolean get() = uid.isNotBlank()
    val isAdmin: Boolean get() = role.isAdmin
}

class SessionStore(private val context: Context) {

    private object Keys {
        val uid = stringPreferencesKey("uid")
        val name = stringPreferencesKey("display_name")
        val email = stringPreferencesKey("email")
        val role = stringPreferencesKey("role")
        val offline = booleanPreferencesKey("offline")
        val profileImagePath = stringPreferencesKey("profile_image_path")
    }

    private fun profileKey(uid: String) = stringPreferencesKey("profile_image_$uid")

    val session: Flow<Session> = context.sessionStore.data.map { p ->
        Session(
            uid = p[Keys.uid] ?: "",
            displayName = p[Keys.name] ?: "",
            email = p[Keys.email] ?: "",
            role = UserRole.from(p[Keys.role]),
            offline = p[Keys.offline] ?: false,
            profileImagePath = (p[Keys.uid] ?: "").takeIf(String::isNotBlank)?.let { uid ->
                p[profileKey(uid)] ?: p[Keys.profileImagePath]
            },
        )
    }

    suspend fun signIn(session: Session) {
        context.sessionStore.edit { p ->
            p[Keys.uid] = session.uid
            p[Keys.name] = session.displayName
            p[Keys.email] = session.email
            p[Keys.role] = session.role.name
            p[Keys.offline] = session.offline
            session.profileImagePath?.let { p[profileKey(session.uid)] = it }
            p.remove(Keys.profileImagePath)
        }
    }

    suspend fun setProfileImage(path: String?) {
        context.sessionStore.edit { p ->
            val uid = p[Keys.uid].orEmpty()
            if (uid.isBlank()) return@edit
            path?.let { p[profileKey(uid)] = it } ?: p.remove(profileKey(uid))
            p.remove(Keys.profileImagePath)
        }
    }

    suspend fun clearProfileFor(uid: String) {
        if (uid.isBlank()) return
        context.sessionStore.edit { it.remove(profileKey(uid)) }
    }

    suspend fun signOut() {
        // Keep per-account profile pictures on this device while clearing the active login.
        context.sessionStore.edit { p ->
            p.remove(Keys.uid)
            p.remove(Keys.name)
            p.remove(Keys.email)
            p.remove(Keys.role)
            p.remove(Keys.offline)
            p.remove(Keys.profileImagePath)
        }
    }
}
