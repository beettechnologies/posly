package com.beettechnologies.posly.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.users.AcceptInviteOutcome
import com.beettechnologies.posly.users.UserApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AcceptInviteUiState(
    val token: String = "",
    val newPassword: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val accepted: Boolean = false
)

class AcceptInviteViewModel(private val userApi: UserApi) : ViewModel() {

    private val _uiState = MutableStateFlow(AcceptInviteUiState())
    val uiState: StateFlow<AcceptInviteUiState> = _uiState.asStateFlow()

    fun onTokenChange(value: String) {
        _uiState.value = _uiState.value.copy(token = value, errorMessage = null)
    }

    fun onNewPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(newPassword = value, errorMessage = null)
    }

    fun submit() {
        val state = _uiState.value
        if (state.token.isBlank() || state.newPassword.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Both the invite token and a new password are required")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, errorMessage = null)
            when (val result = userApi.acceptInvite(state.token, state.newPassword)) {
                AcceptInviteOutcome.Success -> _uiState.value = _uiState.value.copy(isSubmitting = false, accepted = true)
                AcceptInviteOutcome.TokenInvalid -> _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = "This invite link is invalid or has expired"
                )
                AcceptInviteOutcome.NotInvited -> _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = "This account has already set a password"
                )
                is AcceptInviteOutcome.NetworkError -> _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = result.message
                )
            }
        }
    }
}
