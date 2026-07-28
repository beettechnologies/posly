package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.apikeys.ApiKeyApi
import com.beettechnologies.posly.apikeys.ApiKeyListResult
import com.beettechnologies.posly.apikeys.ApiKeyResponse
import com.beettechnologies.posly.apikeys.ApiKeyUsageResponse
import com.beettechnologies.posly.apikeys.ApiKeyUsageResult
import com.beettechnologies.posly.apikeys.RevokeApiKeyOutcome
import com.beettechnologies.posly.apikeys.RotateApiKeyOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ApiKeyListUiState(
    val keys: List<ApiKeyResponse> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val busyKeyIds: Set<String> = emptySet(),
    /** Set exactly once, right after a rotate succeeds - the new secret, shown once, then dismissed. Mirrors the create flow's show-once treatment. */
    val justRotated: Pair<String, String>? = null,
    val expandedUsageKeyIds: Set<String> = emptySet(),
    val usageByKeyId: Map<String, List<ApiKeyUsageResponse>> = emptyMap()
)

class ApiKeyListViewModel(private val apiKeyApi: ApiKeyApi) : ViewModel() {

    private val _uiState = MutableStateFlow(ApiKeyListUiState())
    val uiState: StateFlow<ApiKeyListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = apiKeyApi.listKeys()) {
                is ApiKeyListResult.Success -> _uiState.value = _uiState.value.copy(isLoading = false, keys = result.keys)
                ApiKeyListResult.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to view API keys"
                )
                is ApiKeyListResult.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busyKeyIds = _uiState.value.busyKeyIds + id, errorMessage = null)
            when (val result = apiKeyApi.revokeKey(id)) {
                is RevokeApiKeyOutcome.Success -> _uiState.value = _uiState.value.copy(
                    keys = _uiState.value.keys.map { if (it.id == id) result.apiKey else it }
                )
                RevokeApiKeyOutcome.NotFound -> _uiState.value = _uiState.value.copy(errorMessage = "API key not found")
                RevokeApiKeyOutcome.AlreadyRevoked -> _uiState.value = _uiState.value.copy(errorMessage = "This key is already revoked")
                RevokeApiKeyOutcome.Forbidden -> _uiState.value = _uiState.value.copy(errorMessage = "You don't have permission to revoke API keys")
                is RevokeApiKeyOutcome.NetworkError -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }
            _uiState.value = _uiState.value.copy(busyKeyIds = _uiState.value.busyKeyIds - id)
        }
    }

    fun rotate(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busyKeyIds = _uiState.value.busyKeyIds + id, errorMessage = null)
            when (val result = apiKeyApi.rotateKey(id)) {
                is RotateApiKeyOutcome.Success -> _uiState.value = _uiState.value.copy(
                    keys = _uiState.value.keys.map { if (it.id == id) result.created.apiKey else it },
                    justRotated = id to result.created.rawKey
                )
                RotateApiKeyOutcome.NotFound -> _uiState.value = _uiState.value.copy(errorMessage = "API key not found")
                RotateApiKeyOutcome.Revoked -> _uiState.value = _uiState.value.copy(errorMessage = "Cannot rotate a revoked key")
                RotateApiKeyOutcome.Forbidden -> _uiState.value = _uiState.value.copy(errorMessage = "You don't have permission to rotate API keys")
                is RotateApiKeyOutcome.NetworkError -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }
            _uiState.value = _uiState.value.copy(busyKeyIds = _uiState.value.busyKeyIds - id)
        }
    }

    fun dismissRotatedKeyDialog() {
        _uiState.value = _uiState.value.copy(justRotated = null)
    }

    /** Fetches usage the first time a key's row is expanded; collapsing just hides it again (cached, not re-fetched). */
    fun toggleUsage(id: String) {
        val state = _uiState.value
        if (id in state.expandedUsageKeyIds) {
            _uiState.value = state.copy(expandedUsageKeyIds = state.expandedUsageKeyIds - id)
            return
        }
        _uiState.value = state.copy(expandedUsageKeyIds = state.expandedUsageKeyIds + id)
        if (id in state.usageByKeyId) return

        viewModelScope.launch {
            when (val result = apiKeyApi.getUsage(id)) {
                is ApiKeyUsageResult.Success -> _uiState.value = _uiState.value.copy(
                    usageByKeyId = _uiState.value.usageByKeyId + (id to result.usage)
                )
                ApiKeyUsageResult.NotFound -> _uiState.value = _uiState.value.copy(errorMessage = "API key not found")
                is ApiKeyUsageResult.NetworkError -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }
        }
    }
}
