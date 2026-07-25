package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.stores.AddressDto
import com.beettechnologies.posly.stores.CreateStoreRequest
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreResult
import com.beettechnologies.posly.stores.TaxProfileApi
import com.beettechnologies.posly.stores.TaxProfileListResult
import com.beettechnologies.posly.stores.TaxProfileResponse
import com.beettechnologies.posly.stores.UpdateStoreRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StoreFormUiState(
    val id: String? = null,
    val name: String = "",
    val line1: String = "",
    val city: String = "",
    val postalCode: String = "",
    val country: String = "",
    val timezone: String = "",
    val currency: String = "",
    val taxProfileId: String? = null,
    val taxProfiles: List<TaxProfileResponse> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false
)

class StoreFormViewModel(
    private val storeApi: StoreApi,
    private val taxProfileApi: TaxProfileApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(StoreFormUiState())
    val uiState: StateFlow<StoreFormUiState> = _uiState.asStateFlow()

    private var initialized = false

    /** Called once by the screen (via LaunchedEffect) with the navigation argument. */
    fun initialize(storeId: String?) {
        if (initialized) return
        initialized = true
        _uiState.value = _uiState.value.copy(id = storeId)
        loadTaxProfiles()
        if (storeId != null) loadStore(storeId)
    }

    private fun loadTaxProfiles() {
        viewModelScope.launch {
            val result = taxProfileApi.listProfiles()
            if (result is TaxProfileListResult.Success) {
                _uiState.value = _uiState.value.copy(taxProfiles = result.profiles)
            }
        }
    }

    private fun loadStore(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = storeApi.getStore(id)) {
                is StoreResult.Success -> {
                    val store = result.store
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        name = store.name,
                        line1 = store.address.line1,
                        city = store.address.city,
                        postalCode = store.address.postalCode,
                        country = store.address.country,
                        timezone = store.timezone,
                        currency = store.currency,
                        taxProfileId = store.taxProfileId
                    )
                }
                else -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load store"
                )
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.value = _uiState.value.copy(name = value, errorMessage = null)
    }

    fun onLine1Change(value: String) {
        _uiState.value = _uiState.value.copy(line1 = value, errorMessage = null)
    }

    fun onCityChange(value: String) {
        _uiState.value = _uiState.value.copy(city = value, errorMessage = null)
    }

    fun onPostalCodeChange(value: String) {
        _uiState.value = _uiState.value.copy(postalCode = value, errorMessage = null)
    }

    fun onCountryChange(value: String) {
        _uiState.value = _uiState.value.copy(country = value, errorMessage = null)
    }

    fun onTimezoneChange(value: String) {
        _uiState.value = _uiState.value.copy(timezone = value, errorMessage = null)
    }

    fun onCurrencyChange(value: String) {
        _uiState.value = _uiState.value.copy(currency = value, errorMessage = null)
    }

    fun onTaxProfileSelected(taxProfileId: String?) {
        _uiState.value = _uiState.value.copy(taxProfileId = taxProfileId)
    }

    fun submit() {
        val state = _uiState.value
        if (state.name.isBlank() || state.line1.isBlank() || state.city.isBlank() ||
            state.postalCode.isBlank() || state.country.isBlank() ||
            state.timezone.isBlank() || state.currency.isBlank()
        ) {
            _uiState.value = state.copy(errorMessage = "All fields except tax profile are required")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            val address = AddressDto(
                line1 = state.line1,
                city = state.city,
                postalCode = state.postalCode,
                country = state.country
            )
            val result = if (state.id == null) {
                storeApi.createStore(
                    CreateStoreRequest(state.name, address, state.timezone, state.currency, state.taxProfileId)
                )
            } else {
                storeApi.updateStore(
                    state.id,
                    UpdateStoreRequest(state.name, address, state.timezone, state.currency, state.taxProfileId)
                )
            }
            when (result) {
                is StoreResult.Success -> _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
                is StoreResult.ValidationError -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = result.message
                )
                StoreResult.NotFound -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "Store not found"
                )
                StoreResult.Forbidden -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "You don't have permission to do this"
                )
                is StoreResult.NetworkError -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = result.message
                )
            }
        }
    }
}
