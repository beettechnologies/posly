package com.beettechnologies.posly.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.orders.ListOrdersOutcome
import com.beettechnologies.posly.orders.OrderApi
import com.beettechnologies.posly.orders.OrderResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TransactionListUiState(
    val orders: List<OrderResponse> = emptyList(),
    val filterProductName: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * The drill-down destination for any dashboard metric: every order in the requested window,
 * optionally narrowed to just the ones containing [productId] (a top-SKU row's "which orders sold
 * this?" drill-down) - filtered client-side since the window is already a single day's orders, a
 * small enough set that a second server round-trip per SKU isn't worth it.
 */
class TransactionListViewModel(private val orderApi: OrderApi) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionListUiState())
    val uiState: StateFlow<TransactionListUiState> = _uiState.asStateFlow()

    private var initialized = false

    fun initialize(storeId: String, from: String, to: String, productId: String? = null, productName: String? = null) {
        if (initialized) return
        initialized = true
        _uiState.value = _uiState.value.copy(filterProductName = productName)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = orderApi.listOrders(storeId, from, to)) {
                is ListOrdersOutcome.Success -> {
                    val orders = if (productId != null) {
                        result.orders.filter { order -> order.items.any { it.productId == productId } }
                    } else {
                        result.orders
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, orders = orders)
                }
                ListOrdersOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to view transactions"
                )
                is ListOrdersOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is ListOrdersOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }
}
