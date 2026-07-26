package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.flags.FeatureFlagApi
import com.beettechnologies.posly.flags.FeatureFlagListResult
import com.beettechnologies.posly.flags.FeatureFlagResponse
import com.beettechnologies.posly.flags.UpdateFeatureFlagOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FeatureFlagListUiState(
    val flags: List<FeatureFlagResponse> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val rolloutInputs: Map<String, String> = emptyMap(),
    val savingKeys: Set<String> = emptySet()
)

class FeatureFlagListViewModel(private val featureFlagApi: FeatureFlagApi) : ViewModel() {

    private val _uiState = MutableStateFlow(FeatureFlagListUiState())
    val uiState: StateFlow<FeatureFlagListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = featureFlagApi.listFlags()) {
                is FeatureFlagListResult.Success -> _uiState.value = FeatureFlagListUiState(
                    flags = result.flags,
                    rolloutInputs = result.flags.associate { it.key to it.rolloutPercentage.toString() }
                )
                FeatureFlagListResult.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to view feature flags"
                )
                is FeatureFlagListResult.NetworkError -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.message
                )
            }
        }
    }

    fun onRolloutInputChange(key: String, value: String) {
        _uiState.value = _uiState.value.copy(rolloutInputs = _uiState.value.rolloutInputs + (key to value))
    }

    /** Flips [key]'s enabled state and saves immediately - no separate "save" step, matching the ticket's "toggled ... immediately" requirement. */
    fun toggleEnabled(key: String) {
        val flag = _uiState.value.flags.find { it.key == key } ?: return
        applyUpdate(key) { featureFlagApi.updateFlag(key, enabled = !flag.enabled) }
    }

    fun saveRolloutPercentage(key: String) {
        val percentage = _uiState.value.rolloutInputs[key]?.toIntOrNull()
        if (percentage == null || percentage !in 0..100) {
            _uiState.value = _uiState.value.copy(errorMessage = "Rollout percentage must be a whole number between 0 and 100")
            return
        }
        applyUpdate(key) { featureFlagApi.updateFlag(key, rolloutPercentage = percentage) }
    }

    private fun applyUpdate(key: String, call: suspend () -> UpdateFeatureFlagOutcome) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(savingKeys = _uiState.value.savingKeys + key, errorMessage = null)
            when (val result = call()) {
                is UpdateFeatureFlagOutcome.Success -> {
                    val updated = result.flag
                    _uiState.value = _uiState.value.copy(
                        flags = _uiState.value.flags.map { if (it.key == updated.key) updated else it },
                        rolloutInputs = _uiState.value.rolloutInputs + (updated.key to updated.rolloutPercentage.toString())
                    )
                }
                UpdateFeatureFlagOutcome.NotFound -> _uiState.value = _uiState.value.copy(errorMessage = "Flag not found")
                UpdateFeatureFlagOutcome.Forbidden -> _uiState.value = _uiState.value.copy(errorMessage = "You don't have permission to update feature flags")
                is UpdateFeatureFlagOutcome.Rejected -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
                is UpdateFeatureFlagOutcome.NetworkError -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }
            _uiState.value = _uiState.value.copy(savingKeys = _uiState.value.savingKeys - key)
        }
    }
}
