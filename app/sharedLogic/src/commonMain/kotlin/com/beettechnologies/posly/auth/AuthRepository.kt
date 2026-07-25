package com.beettechnologies.posly.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthState {
    data object LoggedOut : AuthState()
    data class MfaRequired(val mfaToken: String) : AuthState()
    data object LoggedIn : AuthState()
}

/**
 * Orchestrates login/MFA/logout against [AuthApi] and persists resulting
 * tokens via [TokenStore]. Screens observe [authState] to know which step
 * of the flow to render, and [lastError] for inline error messages.
 */
class AuthRepository(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Restores a persisted session on app start, if one exists. */
    suspend fun bootstrap() {
        if (tokenStore.getAccessToken() != null) {
            _authState.value = AuthState.LoggedIn
        }
    }

    suspend fun login(username: String, password: String): Boolean {
        _lastError.value = null
        return when (val outcome = authApi.login(username, password)) {
            is LoginOutcome.Success -> {
                tokenStore.saveTokens(outcome.accessToken, outcome.refreshToken)
                _authState.value = AuthState.LoggedIn
                true
            }
            is LoginOutcome.MfaRequired -> {
                _authState.value = AuthState.MfaRequired(outcome.mfaToken)
                true
            }
            is LoginOutcome.InvalidCredentials -> {
                _lastError.value = outcome.message
                false
            }
            is LoginOutcome.NetworkError -> {
                _lastError.value = outcome.message
                false
            }
        }
    }

    suspend fun verifyMfa(code: String): Boolean {
        val state = _authState.value
        if (state !is AuthState.MfaRequired) return false
        _lastError.value = null
        return when (val outcome = authApi.verifyMfa(state.mfaToken, code)) {
            is MfaOutcome.Success -> {
                tokenStore.saveTokens(outcome.accessToken, outcome.refreshToken)
                _authState.value = AuthState.LoggedIn
                true
            }
            is MfaOutcome.InvalidCode -> {
                _lastError.value = outcome.message
                false
            }
            is MfaOutcome.NetworkError -> {
                _lastError.value = outcome.message
                false
            }
        }
    }

    /** Explicit refresh cycle, e.g. on app resume. Logs the session out if the refresh token is no longer valid. */
    suspend fun refreshAccessToken(): Boolean {
        val refreshToken = tokenStore.getRefreshToken() ?: return false
        return when (val outcome = authApi.refresh(refreshToken)) {
            is RefreshOutcome.Success -> {
                tokenStore.saveTokens(outcome.accessToken, refreshToken)
                true
            }
            RefreshOutcome.Unauthorized -> {
                logout()
                false
            }
            is RefreshOutcome.NetworkError -> false
        }
    }

    suspend fun logout() {
        val refreshToken = tokenStore.getRefreshToken()
        if (refreshToken != null) {
            authApi.logout(refreshToken)
        }
        tokenStore.clear()
        _authState.value = AuthState.LoggedOut
    }
}
