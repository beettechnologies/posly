package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.stores.TaxProfileApi
import com.beettechnologies.posly.stores.TaxProfileListResult
import com.beettechnologies.posly.stores.TaxProfileResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TaxProfileListUiState(
    val profiles: List<TaxProfileResponse> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class TaxProfileListViewModel(private val taxProfileApi: TaxProfileApi) : ViewModel() {

    private val _uiState = MutableStateFlow(TaxProfileListUiState())
    val uiState: StateFlow<TaxProfileListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = taxProfileApi.listProfiles()) {
                is TaxProfileListResult.Success -> _uiState.value = TaxProfileListUiState(profiles = result.profiles)
                TaxProfileListResult.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to view tax profiles"
                )
                is TaxProfileListResult.NetworkError -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.message
                )
            }
        }
    }
}
