package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.stores.CreateTaxProfileRequest
import com.beettechnologies.posly.stores.TaxProfileApi
import com.beettechnologies.posly.stores.TaxProfileResult
import com.beettechnologies.posly.stores.TaxRateRequest
import com.beettechnologies.posly.stores.UpdateTaxProfileRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TaxRateRow(val name: String = "", val ratePercent: String = "")

data class TaxProfileFormUiState(
    val id: String? = null,
    val name: String = "",
    val rates: List<TaxRateRow> = listOf(TaxRateRow()),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false
)

class TaxProfileFormViewModel(private val taxProfileApi: TaxProfileApi) : ViewModel() {

    private val _uiState = MutableStateFlow(TaxProfileFormUiState())
    val uiState: StateFlow<TaxProfileFormUiState> = _uiState.asStateFlow()

    private var initialized = false

    fun initialize(profileId: String?) {
        if (initialized) return
        initialized = true
        _uiState.value = _uiState.value.copy(id = profileId)
        if (profileId != null) loadProfile(profileId)
    }

    private fun loadProfile(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = taxProfileApi.getProfile(id)) {
                is TaxProfileResult.Success -> {
                    val profile = result.profile
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        name = profile.name,
                        rates = if (profile.rates.isEmpty()) {
                            listOf(TaxRateRow())
                        } else {
                            profile.rates.map { TaxRateRow(it.name, it.ratePercent.toString()) }
                        }
                    )
                }
                else -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load tax profile"
                )
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.value = _uiState.value.copy(name = value, errorMessage = null)
    }

    fun onRateNameChange(index: Int, value: String) {
        val updated = _uiState.value.rates.toMutableList()
        updated[index] = updated[index].copy(name = value)
        _uiState.value = _uiState.value.copy(rates = updated, errorMessage = null)
    }

    fun onRatePercentChange(index: Int, value: String) {
        val updated = _uiState.value.rates.toMutableList()
        updated[index] = updated[index].copy(ratePercent = value)
        _uiState.value = _uiState.value.copy(rates = updated, errorMessage = null)
    }

    fun addRateRow() {
        _uiState.value = _uiState.value.copy(rates = _uiState.value.rates + TaxRateRow())
    }

    fun removeRateRow(index: Int) {
        val current = _uiState.value.rates
        if (current.size <= 1) return
        _uiState.value = _uiState.value.copy(rates = current.toMutableList().apply { removeAt(index) })
    }

    fun submit() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Name is required")
            return
        }

        val parsedRates = mutableListOf<TaxRateRequest>()
        for (row in state.rates) {
            if (row.name.isBlank() && row.ratePercent.isBlank()) continue
            val percent = row.ratePercent.toDoubleOrNull()
            if (row.name.isBlank() || percent == null || percent < 0) {
                _uiState.value = state.copy(errorMessage = "Each rate needs a name and a non-negative percentage")
                return
            }
            parsedRates.add(TaxRateRequest(row.name, percent))
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            val result = if (state.id == null) {
                taxProfileApi.createProfile(CreateTaxProfileRequest(state.name, parsedRates))
            } else {
                taxProfileApi.updateProfile(state.id, UpdateTaxProfileRequest(state.name, parsedRates))
            }
            when (result) {
                is TaxProfileResult.Success -> _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
                is TaxProfileResult.ValidationError -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = result.message
                )
                TaxProfileResult.NotFound -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "Tax profile not found"
                )
                TaxProfileResult.Forbidden -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "You don't have permission to do this"
                )
                is TaxProfileResult.NetworkError -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = result.message
                )
            }
        }
    }
}
