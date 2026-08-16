package com.uhmk.pos.feature.auth

import android.content.Context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uhmk.pos.core.db.UserEntity
import com.uhmk.pos.core.model.UserRole
import com.uhmk.pos.core.repo.UserRepository
import com.uhmk.pos.core.notify.NoticeListenerService
import com.uhmk.pos.core.sync.SyncManager
import com.uhmk.pos.core.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val cloudEnabled: Boolean = false,
    val signedIn: Boolean = false,
)

class AuthViewModel(
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val syncManager: SyncManager,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(cloudEnabled = authService.isCloudEnabled))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    val staff: StateFlow<List<UserEntity>> = userRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEmail(value: String) = _state.update { it.copy(email = value, error = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun signIn() {
        val current = _state.value
        if (current.busy) return
        if (current.email.isBlank() || current.password.isBlank()) {
            _state.update { it.copy(error = "Enter your email and password") }
            return
        }

        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            authService.signIn(current.email, current.password).fold(
                onSuccess = {
                    SyncWorker.schedule(appContext)
                    NoticeListenerService.start(appContext)
                    syncManager.syncAll()
                    _state.update { s -> s.copy(busy = false, signedIn = true) }
                },
                onFailure = { e ->
                    _state.update { s -> s.copy(busy = false, error = friendly(e)) }
                },
            )
        }
    }

    fun continueOnDevice() {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            authService.signInLocally()
            _state.update { it.copy(busy = false, signedIn = true) }
        }
    }

    fun resetPassword() {
        val email = _state.value.email
        if (email.isBlank()) {
            _state.update { it.copy(error = "Type your email first, then tap reset") }
            return
        }
        viewModelScope.launch {
            authService.sendPasswordReset(email).fold(
                onSuccess = { _state.update { it.copy(info = "Reset link sent to $email") } },
                onFailure = { e -> _state.update { it.copy(error = friendly(e)) } },
            )
        }
    }

    fun signOut() = viewModelScope.launch {
        NoticeListenerService.stop(appContext)
        authService.signOut()
        // Never leave the previous account's password sitting in the in-memory login form.
        _state.value = LoginUiState(cloudEnabled = authService.isCloudEnabled)
    }

    // ---- Staff management (admin) ----

    private val _staffResult = MutableStateFlow<String?>(null)
    val staffResult: StateFlow<String?> = _staffResult.asStateFlow()

    fun createStaff(email: String, password: String, name: String, role: UserRole) {
        viewModelScope.launch {
            authService.createStaffAccount(email, password, name, role).fold(
                onSuccess = { _staffResult.value = "Created ${it.displayName}" },
                onFailure = { _staffResult.value = friendly(it) },
            )
        }
    }

    fun setStaffActive(uid: String, active: Boolean) =
        viewModelScope.launch { userRepository.setActive(uid, active) }

    fun setStaffRole(uid: String, role: UserRole) =
        viewModelScope.launch { userRepository.setRole(uid, role) }

    fun consumeStaffResult() {
        _staffResult.value = null
    }

    fun consumeMessages() = _state.update { it.copy(error = null, info = null) }

    /** Firebase's raw exception text is not something to show a cashier. */
    private fun friendly(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("password is invalid", true) ||
                raw.contains("INVALID_LOGIN_CREDENTIALS", true) ||
                raw.contains("credential is incorrect", true) -> "Wrong email or password"

            raw.contains("no user record", true) -> "No account with that email"
            raw.contains("email address is already in use", true) -> "That email already has an account"
            raw.contains("badly formatted", true) -> "That email address does not look right"
            raw.contains("at least 6 characters", true) -> "Password must be at least 6 characters"
            raw.contains("network", true) -> "No connection. Check your internet and try again."
            raw.isBlank() -> "Something went wrong. Try again."
            else -> raw
        }
    }
}
