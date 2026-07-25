package com.beettechnologies.posly.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DevicePairingAdminUiState(
    val stores: List<StoreResponse> = emptyList(),
    val selectedStoreId: String? = null,
    val terminalType: String = "",
    val isGenerating: Boolean = false,
    val errorMessage: String? = null,
    val pairCode: PairCodeResponse? = null
)

class DevicePairingAdminViewModel(
    private val storeApi: StoreApi,
    private val deviceApi: DeviceApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicePairingAdminUiState())
    val uiState: StateFlow<DevicePairingAdminUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = storeApi.listStores()) {
                is StoreListResult.Success -> _uiState.value = _uiState.value.copy(stores = result.stores)
                StoreListResult.Forbidden -> _uiState.value =
                    _uiState.value.copy(errorMessage = "You don't have permission to view stores")
                is StoreListResult.NetworkError -> _uiState.value =
                    _uiState.value.copy(errorMessage = result.message)
            }
        }
    }

    fun onStoreSelected(storeId: String) {
        _uiState.value = _uiState.value.copy(selectedStoreId = storeId, pairCode = null, errorMessage = null)
    }

    fun onTerminalTypeChange(value: String) {
        _uiState.value = _uiState.value.copy(terminalType = value)
    }

    fun generateCode() {
        val state = _uiState.value
        val storeId = state.selectedStoreId ?: return
        viewModelScope.launch {
            _uiState.value = state.copy(isGenerating = true, errorMessage = null, pairCode = null)
            val request = CreatePairCodeRequest(
                storeId = storeId,
                terminalType = state.terminalType.trim().takeIf { it.isNotBlank() }
            )
            when (val outcome = deviceApi.createPairCode(request)) {
                is CreatePairCodeOutcome.Success -> _uiState.value =
                    _uiState.value.copy(isGenerating = false, pairCode = outcome.response)
                is CreatePairCodeOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(isGenerating = false, errorMessage = outcome.message)
                CreatePairCodeOutcome.Forbidden -> _uiState.value =
                    _uiState.value.copy(isGenerating = false, errorMessage = "You don't have permission to pair devices")
                is CreatePairCodeOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isGenerating = false, errorMessage = outcome.message)
            }
        }
    }
}
