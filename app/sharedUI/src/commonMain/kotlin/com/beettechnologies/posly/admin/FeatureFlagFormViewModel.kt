package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.flags.CreateFeatureFlagOutcome
import com.beettechnologies.posly.flags.FeatureFlagApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FeatureFlagFormUiState(
    val key: String = "",
    val description: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val created: Boolean = false
)

class FeatureFlagFormViewModel(private val featureFlagApi: FeatureFlagApi) : ViewModel() {

    private val _uiState = MutableStateFlow(FeatureFlagFormUiState())
    val uiState: StateFlow<FeatureFlagFormUiState> = _uiState.asStateFlow()

    fun onKeyChange(value: String) {
        _uiState.value = _uiState.value.copy(key = value, errorMessage = null)
    }

    fun onDescriptionChange(value: String) {
        _uiState.value = _uiState.value.copy(description = value, errorMessage = null)
    }

    fun submit() {
        val state = _uiState.value
        if (state.key.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Key is required")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            when (val result = featureFlagApi.createFlag(state.key, state.description)) {
                is CreateFeatureFlagOutcome.Success -> _uiState.value = _uiState.value.copy(isSaving = false, created = true)
                CreateFeatureFlagOutcome.DuplicateKey -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "A flag with this key already exists"
                )
                CreateFeatureFlagOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "You don't have permission to create feature flags"
                )
                is CreateFeatureFlagOutcome.Rejected -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
                is CreateFeatureFlagOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
            }
        }
    }
}
