package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.apikeys.ApiKeyApi
import com.beettechnologies.posly.apikeys.CreateApiKeyOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The scopes selectable in the create form - see server-side `ApiKeyScope` for the source of truth. */
val AVAILABLE_API_KEY_SCOPES = listOf("ORDERS_READ", "PRODUCTS_READ", "REPORTS_READ")

data class ApiKeyFormUiState(
    val name: String = "",
    val selectedScopes: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    /** Set exactly once, on a successful create - the raw secret, shown once and never re-fetchable. */
    val createdRawKey: String? = null
)

class ApiKeyFormViewModel(private val apiKeyApi: ApiKeyApi) : ViewModel() {

    private val _uiState = MutableStateFlow(ApiKeyFormUiState())
    val uiState: StateFlow<ApiKeyFormUiState> = _uiState.asStateFlow()

    fun onNameChange(value: String) {
        _uiState.value = _uiState.value.copy(name = value, errorMessage = null)
    }

    fun toggleScope(scope: String) {
        val current = _uiState.value.selectedScopes
        _uiState.value = _uiState.value.copy(
            selectedScopes = if (scope in current) current - scope else current + scope,
            errorMessage = null
        )
    }

    fun submit() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Name is required")
            return
        }
        if (state.selectedScopes.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "At least one scope is required")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            when (val result = apiKeyApi.createKey(state.name, state.selectedScopes.toList())) {
                is CreateApiKeyOutcome.Success -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    createdRawKey = result.created.rawKey
                )
                CreateApiKeyOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "You don't have permission to create API keys"
                )
                is CreateApiKeyOutcome.Rejected -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
                is CreateApiKeyOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
            }
        }
    }
}
