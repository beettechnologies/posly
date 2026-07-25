package com.beettechnologies.posly.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.cart.AddCartItemOutcome
import com.beettechnologies.posly.cart.CartApi
import com.beettechnologies.posly.cart.CartResponse
import com.beettechnologies.posly.cart.SelectedModifierRequest
import com.beettechnologies.posly.products.GetProductOutcome
import com.beettechnologies.posly.products.ProductApi
import com.beettechnologies.posly.products.ProductResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProductDetailUiState(
    val isLoading: Boolean = true,
    val product: ProductResponse? = null,
    val selectedOptions: Map<String, String> = emptyMap(),
    val quantity: Int = 1,
    val isAdding: Boolean = false,
    val added: CartResponse? = null,
    val errorMessage: String? = null
) {
    /** Sum of the additionalCost for every modifier group with a selection made so far. */
    val modifiersTotal: Double
        get() = selectedOptions.keys.sumOf { modifierId ->
            product?.modifiers?.find { it.id == modifierId }?.additionalCost ?: 0.0
        }

    val unitPrice: Double get() = (product?.price ?: 0.0) + modifiersTotal

    val lineTotal: Double get() = unitPrice * quantity
}

class ProductDetailViewModel(
    private val productApi: ProductApi,
    private val cartApi: CartApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductDetailUiState())
    val uiState: StateFlow<ProductDetailUiState> = _uiState.asStateFlow()

    private var loadedProductId: String? = null

    fun load(productId: String) {
        if (loadedProductId == productId) return
        loadedProductId = productId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = productApi.getProduct(productId)) {
                is GetProductOutcome.Success -> _uiState.value =
                    _uiState.value.copy(isLoading = false, product = result.product)
                GetProductOutcome.NotFound -> _uiState.value =
                    _uiState.value.copy(isLoading = false, errorMessage = "Product not found")
                is GetProductOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun selectOption(modifierId: String, option: String) {
        val modifier = _uiState.value.product?.modifiers?.find { it.id == modifierId } ?: return
        if (option in modifier.unavailableOptions) return
        _uiState.value = _uiState.value.copy(selectedOptions = _uiState.value.selectedOptions + (modifierId to option))
    }

    fun incrementQuantity() {
        _uiState.value = _uiState.value.copy(quantity = _uiState.value.quantity + 1)
    }

    fun decrementQuantity() {
        val state = _uiState.value
        if (state.quantity > 1) _uiState.value = state.copy(quantity = state.quantity - 1)
    }

    fun addToCart(cartId: String) {
        val state = _uiState.value
        val product = state.product ?: return
        if (state.isAdding) return

        viewModelScope.launch {
            _uiState.value = state.copy(isAdding = true, errorMessage = null)
            val modifiers = state.selectedOptions.map { (modifierId, option) -> SelectedModifierRequest(modifierId, option) }
            when (val result = cartApi.addItem(cartId, product.id, state.quantity, modifiers)) {
                is AddCartItemOutcome.Success -> _uiState.value = _uiState.value.copy(isAdding = false, added = result.cart)
                AddCartItemOutcome.CartNotFound -> _uiState.value =
                    _uiState.value.copy(isAdding = false, errorMessage = "Cart not found - please restart the sale")
                is AddCartItemOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(isAdding = false, errorMessage = result.message)
                is AddCartItemOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isAdding = false, errorMessage = result.message)
            }
        }
    }
}
