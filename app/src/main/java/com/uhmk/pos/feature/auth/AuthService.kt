package com.uhmk.pos.feature.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.uhmk.pos.core.db.UserEntity
import com.uhmk.pos.core.model.UserRole
import com.uhmk.pos.core.prefs.Session
import com.uhmk.pos.core.prefs.SessionStore
import com.uhmk.pos.core.repo.UserRepository
import com.uhmk.pos.core.sync.FirebaseGate
import kotlinx.coroutines.tasks.await

/**
 * Sign-in, with a local fallback.
 *
 * When Firebase is not configured the app still has to be usable, so [signInLocally] issues an
 * owner session backed only by the on-device database.
 */
class AuthService(
    private val context: Context,
    private val userRepository: UserRepository,
    private val sessionStore: SessionStore,
) {

    val isCloudEnabled: Boolean get() = FirebaseGate.isConfigured(context)

    suspend fun signIn(email: String, password: String): Result<Session> {
        if (!isCloudEnabled) {
            return Result.failure(
                IllegalStateException("Firebase is not set up yet. Use \"Continue on this device\" for now.")
            )
        }

        return runCatching {
            val auth = FirebaseAuth.getInstance()
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val firebaseUser = result.user ?: error("Sign-in returned no user")

            // Read the role directly after authentication. Waiting for background sync used to
            // make a real owner look like staff on their first login and hide Reports.
            val local = userRepository.getById(firebaseUser.uid)
            val remote = FirebaseFirestore.getInstance()
                .collection("users")
                .document(firebaseUser.uid)
                .get()
                .await()
            val role = if (remote.exists()) {
                UserRole.from(remote.getString("role"))
            } else {
                local?.role ?: UserRole.STAFF
            }
            val name = remote.getString("displayName")
                ?: local?.displayName
                ?: firebaseUser.displayName
                ?: email.substringBefore("@")
            val active = if (remote.exists()) remote.getBoolean("active") ?: true
                else local?.active ?: true

            if (!active) {
                auth.signOut()
                error("This account has been disabled by the store admin.")
            }

            userRepository.saveAll(
                listOf(
                    UserEntity(
                        uid = firebaseUser.uid,
                        email = remote.getString("email") ?: email.trim().lowercase(),
                        displayName = name,
                        role = role,
                        active = active,
                        fcmToken = local?.fcmToken,
                        createdAt = (remote.getLong("createdAt") ?: local?.createdAt)
                            ?: System.currentTimeMillis(),
                        updatedAt = remote.getLong("updatedAt") ?: System.currentTimeMillis(),
                        dirty = false,
                    )
                )
            )

            val session = Session(
                uid = firebaseUser.uid,
                displayName = name,
                email = email.trim().lowercase(),
                role = role,
                offline = false,
            )
            sessionStore.signIn(session)
            session
        }
    }

    suspend fun signInLocally(): Session {
        val admin = userRepository.ensureLocalAdmin()
        val session = Session(
            uid = admin.uid,
            displayName = admin.displayName,
            email = admin.email,
            role = UserRole.ADMIN,
            offline = true,
        )
        sessionStore.signIn(session)
        return session
    }

    /**
     * Creates a staff login without knocking the admin out of their own session.
     *
     * `createUserWithEmailAndPassword` signs in as the account it just made, so the call runs on a
     * second FirebaseApp instance that is signed out immediately afterwards.
     */
    suspend fun createStaffAccount(
        email: String,
        password: String,
        displayName: String,
        role: UserRole,
    ): Result<UserEntity> {
        if (!isCloudEnabled) {
            return Result.failure(
                IllegalStateException("Staff accounts need Firebase. Finish the setup in FIREBASE_SETUP.md first.")
            )
        }

        return runCatching {
            val primary = FirebaseApp.getInstance()
            val secondary = runCatching { FirebaseApp.getInstance(SECONDARY) }
                .getOrElse { FirebaseApp.initializeApp(context, primary.options, SECONDARY) }

            val auth = FirebaseAuth.getInstance(secondary)
            try {
                val created = auth.createUserWithEmailAndPassword(email.trim(), password).await()
                val firebaseUser = created.user ?: error("Account creation returned no user")
                val uid = firebaseUser.uid
                val now = System.currentTimeMillis()

                val user = UserEntity(
                    uid = uid,
                    email = email.trim().lowercase(),
                    displayName = displayName.trim().ifBlank { email.substringBefore("@") },
                    role = role,
                    active = true,
                    createdAt = now,
                    updatedAt = now,
                    dirty = false,
                )

                // Create the role document immediately while the primary Firebase app is still
                // authenticated as the admin. A new admin gets admin access on their first login.
                runCatching {
                    FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(uid)
                        .set(
                            mapOf(
                                "uid" to user.uid,
                                "email" to user.email,
                                "displayName" to user.displayName,
                                "role" to user.role.name,
                                "active" to user.active,
                                "createdAt" to user.createdAt,
                                "updatedAt" to user.updatedAt,
                            )
                        )
                        .await()
                }.getOrElse { writeError ->
                    // Avoid an Authentication-only account that cannot be retried from the app.
                    runCatching { firebaseUser.delete().await() }
                    throw writeError
                }

                userRepository.saveAll(listOf(user))
                user
            } finally {
                auth.signOut()
            }
        }
    }

    suspend fun signOut() {
        if (isCloudEnabled) runCatching { FirebaseAuth.getInstance().signOut() }
        sessionStore.signOut()
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = runCatching {
        if (!isCloudEnabled) error("Password reset needs Firebase.")
        FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).await()
    }

    /** Deactivates the role record, then removes the signed-in Firebase Authentication account. */
    suspend fun deleteCurrentAccount(): Result<Unit> {
        if (!isCloudEnabled) return Result.failure(
            IllegalStateException("A device-only owner account cannot be deleted from Firebase.")
        )
        return runCatching {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser ?: error("No Firebase account is signed in")
            val document = FirebaseFirestore.getInstance().collection("users").document(user.uid)
            val now = System.currentTimeMillis()
            document.update(mapOf("active" to false, "updatedAt" to now)).await()
            try {
                user.delete().await()
            } catch (error: Throwable) {
                // Do not lock a legitimate user out when Firebase requires a recent login.
                runCatching {
                    document.update(mapOf("active" to true, "updatedAt" to System.currentTimeMillis())).await()
                }
                throw error
            }
            sessionStore.signOut()
        }
    }

    private companion object {
        const val SECONDARY = "staffCreator"
    }
}
