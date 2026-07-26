package com.beettechnologies.posly.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.reporting.CashOnHandOutcome
import com.beettechnologies.posly.reporting.CashOnHandResponse
import com.beettechnologies.posly.reporting.ProductSalesSummaryResponse
import com.beettechnologies.posly.reporting.RealtimeSalesOutcome
import com.beettechnologies.posly.reporting.ReportingApi
import com.beettechnologies.posly.reporting.SalesAggregateResponse
import com.beettechnologies.posly.reporting.TopProductsOutcome
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ManagerDashboardUiState(
    val isLoadingStores: Boolean = true,
    val stores: List<StoreResponse> = emptyList(),
    val selectedStoreId: String? = null,
    val sales: SalesAggregateResponse? = null,
    val topProducts: List<ProductSalesSummaryResponse> = emptyList(),
    val cashOnHand: CashOnHandResponse? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
) {
    val selectedStoreName: String? get() = stores.find { it.id == selectedStoreId }?.name
}

/**
 * A lightweight, at-a-glance dashboard for a store manager: today's sales, transaction count, top
 * 5 SKUs, and cash on hand across every currently open shift. Every tile drills through to
 * [TransactionListViewModel] for the underlying orders behind that number.
 */
class ManagerDashboardViewModel(
    private val reportingApi: ReportingApi,
    private val storeApi: StoreApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManagerDashboardUiState())
    val uiState: StateFlow<ManagerDashboardUiState> = _uiState.asStateFlow()

    fun loadStores() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingStores = true, errorMessage = null)
            when (val result = storeApi.listStores()) {
                is StoreListResult.Success -> {
                    val selected = _uiState.value.selectedStoreId ?: result.stores.firstOrNull()?.id
                    _uiState.value = _uiState.value.copy(isLoadingStores = false, stores = result.stores, selectedStoreId = selected)
                    if (selected != null) refresh()
                }
                StoreListResult.Forbidden -> _uiState.value =
                    _uiState.value.copy(isLoadingStores = false, errorMessage = "You don't have permission to view stores")
                is StoreListResult.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoadingStores = false, errorMessage = result.message)
            }
        }
    }

    fun selectStore(storeId: String) {
        _uiState.value = _uiState.value.copy(selectedStoreId = storeId)
        refresh()
    }

    fun refresh() {
        val storeId = _uiState.value.selectedStoreId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)

            when (val result = reportingApi.getRealtimeSales(storeId)) {
                is RealtimeSalesOutcome.Success -> _uiState.value = _uiState.value.copy(sales = result.sales)
                RealtimeSalesOutcome.Forbidden -> _uiState.value =
                    _uiState.value.copy(errorMessage = "You don't have permission to view this dashboard")
                is RealtimeSalesOutcome.NetworkError -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }

            when (val result = reportingApi.getTopProducts(storeId)) {
                is TopProductsOutcome.Success -> _uiState.value = _uiState.value.copy(topProducts = result.products)
                TopProductsOutcome.Forbidden -> Unit // already surfaced by the sales call above
                is TopProductsOutcome.NetworkError -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }

            when (val result = reportingApi.getCashOnHand(storeId)) {
                is CashOnHandOutcome.Success -> _uiState.value = _uiState.value.copy(cashOnHand = result.cashOnHand)
                CashOnHandOutcome.Forbidden -> Unit
                is CashOnHandOutcome.NetworkError -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }

            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }
}
