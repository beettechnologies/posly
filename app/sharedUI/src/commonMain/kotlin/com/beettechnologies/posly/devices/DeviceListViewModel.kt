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

data class DeviceListUiState(
    val stores: List<StoreResponse> = emptyList(),
    val selectedStoreId: String? = null,
    val devices: List<DeviceResponse> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val deprovisioningDeviceId: String? = null
)

class DeviceListViewModel(
    private val storeApi: StoreApi,
    private val deviceApi: DeviceApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceListUiState())
    val uiState: StateFlow<DeviceListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = storeApi.listStores()) {
                is StoreListResult.Success -> {
                    _uiState.value = _uiState.value.copy(stores = result.stores)
                    result.stores.firstOrNull()?.let { onStoreSelected(it.id) }
                }
                StoreListResult.Forbidden -> _uiState.value =
                    _uiState.value.copy(errorMessage = "You don't have permission to view stores")
                is StoreListResult.NetworkError -> _uiState.value =
                    _uiState.value.copy(errorMessage = result.message)
            }
        }
    }

    fun onStoreSelected(storeId: String) {
        _uiState.value = _uiState.value.copy(selectedStoreId = storeId, devices = emptyList())
        refresh()
    }

    fun refresh() {
        val storeId = _uiState.value.selectedStoreId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = deviceApi.listDevices(storeId)) {
                is ListDevicesOutcome.Success -> _uiState.value =
                    _uiState.value.copy(isLoading = false, devices = result.devices)
                ListDevicesOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to view devices"
                )
                is ListDevicesOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun deprovision(deviceId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(deprovisioningDeviceId = deviceId, errorMessage = null)
            when (val outcome = deviceApi.deprovisionDevice(deviceId)) {
                is DeprovisionDeviceOutcome.Success -> _uiState.value = _uiState.value.copy(
                    deprovisioningDeviceId = null,
                    devices = _uiState.value.devices.map { if (it.id == deviceId) outcome.device else it }
                )
                DeprovisionDeviceOutcome.NotFound -> _uiState.value = _uiState.value.copy(
                    deprovisioningDeviceId = null,
                    errorMessage = "Device not found"
                )
                is DeprovisionDeviceOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(deprovisioningDeviceId = null, errorMessage = outcome.message)
                DeprovisionDeviceOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    deprovisioningDeviceId = null,
                    errorMessage = "You don't have permission to deprovision devices"
                )
                is DeprovisionDeviceOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(deprovisioningDeviceId = null, errorMessage = outcome.message)
            }
        }
    }
}
