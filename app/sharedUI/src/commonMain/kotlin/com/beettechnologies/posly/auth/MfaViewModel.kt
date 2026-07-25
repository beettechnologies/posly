package com.beettechnologies.posly.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MfaUiState(
    val code: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class MfaViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(MfaUiState())
    val uiState: StateFlow<MfaUiState> = _uiState.asStateFlow()

    fun onCodeChange(value: String) {
        _uiState.value = _uiState.value.copy(code = value, errorMessage = null)
    }

    fun submit() {
        val state = _uiState.value
        if (state.code.isBlank() || state.isLoading) return

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            val success = authRepository.verifyMfa(state.code)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = if (success) null else authRepository.lastError.value
            )
        }
    }
}
