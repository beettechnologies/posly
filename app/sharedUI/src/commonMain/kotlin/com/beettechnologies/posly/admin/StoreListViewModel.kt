package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StoreListUiState(
    val stores: List<StoreResponse> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class StoreListViewModel(private val storeApi: StoreApi) : ViewModel() {

    private val _uiState = MutableStateFlow(StoreListUiState())
    val uiState: StateFlow<StoreListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = storeApi.listStores()) {
                is StoreListResult.Success -> _uiState.value = StoreListUiState(stores = result.stores)
                StoreListResult.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to view stores"
                )
                is StoreListResult.NetworkError -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.message
                )
            }
        }
    }
}
