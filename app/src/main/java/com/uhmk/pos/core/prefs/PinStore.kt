package com.uhmk.pos.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.pinDataStore: DataStore<Preferences> by preferencesDataStore(name = "uhmk_pins")

/** Device-local, per-user PIN hashes. The digits themselves are never stored. */
class PinStore(private val context: Context) {
    private fun key(uid: String) = stringPreferencesKey("pin_${uid.ifBlank { "signed_out" }}")
    private fun autoKey(uid: String) =
        booleanPreferencesKey("pin_auto_unlock_${uid.ifBlank { "signed_out" }}")

    fun pinHash(uid: String): Flow<String> = context.pinDataStore.data.map { it[key(uid)] ?: "" }
    fun hasPin(uid: String): Flow<Boolean> = pinHash(uid).map { it.isNotBlank() }
    fun autoUnlock(uid: String): Flow<Boolean> =
        context.pinDataStore.data.map { it[autoKey(uid)] ?: true }

    suspend fun setPin(uid: String, pin: String) {
        require(uid.isNotBlank()) { "Sign in before setting a PIN" }
        require(pin.length in 4..6 && pin.all(Char::isDigit)) { "PIN must be 4 to 6 digits" }
        context.pinDataStore.edit { it[key(uid)] = hash(uid, pin) }
    }

    suspend fun clearPin(uid: String) {
        if (uid.isBlank()) return
        context.pinDataStore.edit { it.remove(key(uid)) }
    }

    suspend fun setAutoUnlock(uid: String, enabled: Boolean) {
        if (uid.isBlank()) return
        context.pinDataStore.edit { it[autoKey(uid)] = enabled }
    }

    fun verify(uid: String, pin: String, storedHash: String): Boolean =
        storedHash.isNotBlank() && MessageDigest.isEqual(
            hash(uid, pin).toByteArray(),
            storedHash.toByteArray(),
        )

    private fun hash(uid: String, pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("uhmk-pos:$uid:$pin".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
