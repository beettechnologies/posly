package com.beettechnologies.posly.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PairingUiState(
    val code: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val scanAttempt: Int = 0,
    val credentials: EnrollDeviceResponse? = null
)

class PairingViewModel(
    private val deviceApi: DeviceApi,
    private val credentialsStore: DeviceCredentialsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    fun onCodeChange(value: String) {
        _uiState.value = _uiState.value.copy(code = value, errorMessage = null)
    }

    fun onCodeScanned(value: String) {
        onCodeChange(value)
        submit()
    }

    fun submit() {
        val state = _uiState.value
        val trimmed = state.code.trim()
        if (trimmed.isBlank() || state.isLoading) return

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            when (val outcome = deviceApi.enrollDevice(EnrollDeviceRequest(code = trimmed))) {
                is EnrollDeviceOutcome.Success -> {
                    credentialsStore.saveCredentials(
                        DeviceCredentials(
                            deviceId = outcome.response.deviceId,
                            storeId = outcome.response.storeId,
                            clientId = outcome.response.clientId,
                            clientSecret = outcome.response.clientSecret
                        )
                    )
                    _uiState.value = _uiState.value.copy(isLoading = false, credentials = outcome.response)
                }
                is EnrollDeviceOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(isLoading = false, errorMessage = outcome.message)
                is EnrollDeviceOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoading = false, errorMessage = outcome.message)
            }
        }
    }

    /** Clears the error so the user can edit the code or re-scan; keeps whatever code they'd typed. */
    fun retry() {
        _uiState.value = _uiState.value.copy(errorMessage = null, scanAttempt = _uiState.value.scanAttempt + 1)
    }
}
