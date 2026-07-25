package com.beettechnologies.posly.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.cart.AddCartItemOutcome
import com.beettechnologies.posly.cart.CartApi
import com.beettechnologies.posly.cart.CartResponse
import com.beettechnologies.posly.cart.CartSessionStore
import com.beettechnologies.posly.cart.CreateCartOutcome
import com.beettechnologies.posly.cart.DiscountDto
import com.beettechnologies.posly.cart.GetCartOutcome
import com.beettechnologies.posly.cart.RemoveCartItemOutcome
import com.beettechnologies.posly.cart.SelectedModifierRequest
import com.beettechnologies.posly.cart.SetCartDiscountOutcome
import com.beettechnologies.posly.cart.UpdateCartItemQuantityOutcome
import com.beettechnologies.posly.devices.DeviceCredentialsStore
import com.beettechnologies.posly.products.ProductSearchApi
import com.beettechnologies.posly.products.SearchOutcome
import com.beettechnologies.posly.products.SearchResultItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Enough of a voided line item to re-add it via [SaleViewModel.undoVoid] - the re-added item gets a new id. */
data class VoidedCartItem(
    val productId: String,
    val productName: String,
    val quantity: Int,
    val selectedModifiers: List<SelectedModifierRequest>,
    val discount: DiscountDto?
)

data class SaleUiState(
    val cart: CartResponse? = null,
    val searchQuery: String = "",
    val suggestions: List<SearchResultItem> = emptyList(),
    val isSearching: Boolean = false,
    val showNoResults: Boolean = false,
    val selectedProductId: String? = null,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val lastVoidedItem: VoidedCartItem? = null
)

class SaleViewModel(
    private val deviceCredentialsStore: DeviceCredentialsStore,
    private val cartSessionStore: CartSessionStore,
    private val cartApi: CartApi,
    private val productSearchApi: ProductSearchApi,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS
) : ViewModel() {

    private val _uiState = MutableStateFlow(SaleUiState())
    val uiState: StateFlow<SaleUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch { initializeCart() }
    }

    private suspend fun initializeCart() {
        val storeId = deviceCredentialsStore.getCredentials()?.storeId
        if (storeId == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "This device is not paired to a store")
            return
        }

        val savedCartId = cartSessionStore.getCurrentCartId()
        if (savedCartId != null) {
            val resumed = cartApi.getCart(savedCartId)
            if (resumed is GetCartOutcome.Success && resumed.cart.status == "OPEN") {
                _uiState.value = _uiState.value.copy(cart = resumed.cart)
                return
            }
        }

        when (val result = cartApi.createCart(storeId)) {
            is CreateCartOutcome.Success -> {
                cartSessionStore.setCurrentCartId(result.cart.id)
                _uiState.value = _uiState.value.copy(cart = result.cart)
            }
            is CreateCartOutcome.Rejected -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            is CreateCartOutcome.NetworkError -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
        }
    }

    /** Called on every keystroke in the search field; debounces before firing a typeahead search. */
    fun onQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(searchQuery = value, showNoResults = false, infoMessage = null)
        searchJob?.cancel()

        if (value.isBlank()) {
            _uiState.value = _uiState.value.copy(suggestions = emptyList(), isSearching = false)
            return
        }

        searchJob = viewModelScope.launch {
            delay(debounceMillis)
            runTypeahead(value)
        }
    }

    private suspend fun runTypeahead(query: String) {
        _uiState.value = _uiState.value.copy(isSearching = true)
        when (val result = productSearchApi.search(query = query)) {
            is SearchOutcome.Success -> _uiState.value = _uiState.value.copy(
                isSearching = false,
                suggestions = result.response.results,
                showNoResults = result.response.results.isEmpty()
            )
            is SearchOutcome.NetworkError -> _uiState.value =
                _uiState.value.copy(isSearching = false, errorMessage = result.message)
        }
    }

    /**
     * Fired when Enter is received on the search field - what a keyboard-wedge barcode scanner
     * sends right after typing a code. Tries an exact barcode match; a scanned/pasted code that
     * matches exactly one product is added immediately with no further input needed.
     */
    fun onEnterPressed() {
        val code = _uiState.value.searchQuery.trim()
        if (code.isBlank()) return
        searchJob?.cancel()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, infoMessage = null)
            when (val result = productSearchApi.search(barcode = code)) {
                is SearchOutcome.Success -> {
                    val match = result.response.results.singleOrNull()
                    if (match != null) {
                        addToCart(match)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isSearching = false,
                            suggestions = emptyList(),
                            showNoResults = true
                        )
                    }
                }
                is SearchOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isSearching = false, errorMessage = result.message)
            }
        }
    }

    /** Opens the product detail/modifiers modal rather than adding directly, so the cashier can review and pick modifiers first. */
    fun onSuggestionSelected(item: SearchResultItem) {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(selectedProductId = item.id)
    }

    fun dismissProductDetail() {
        _uiState.value = _uiState.value.copy(selectedProductId = null)
    }

    /** Called once the product detail modal has added its item to the cart. */
    fun onProductAdded(cart: CartResponse) {
        _uiState.value = _uiState.value.copy(
            cart = cart,
            selectedProductId = null,
            searchQuery = "",
            suggestions = emptyList(),
            showNoResults = false
        )
    }

    private suspend fun addToCart(item: SearchResultItem) {
        val cartId = _uiState.value.cart?.id
        if (cartId == null) {
            _uiState.value = _uiState.value.copy(isSearching = false, errorMessage = "No active cart")
            return
        }
        when (val result = cartApi.addItem(cartId, item.id, quantity = 1)) {
            is AddCartItemOutcome.Success -> _uiState.value = _uiState.value.copy(
                cart = result.cart,
                searchQuery = "",
                suggestions = emptyList(),
                showNoResults = false,
                isSearching = false
            )
            AddCartItemOutcome.CartNotFound -> _uiState.value = _uiState.value.copy(
                isSearching = false,
                errorMessage = "Cart not found - please restart the sale"
            )
            is AddCartItemOutcome.Rejected -> _uiState.value =
                _uiState.value.copy(isSearching = false, errorMessage = result.message)
            is AddCartItemOutcome.NetworkError -> _uiState.value =
                _uiState.value.copy(isSearching = false, errorMessage = result.message)
        }
    }

    /** Ignores non-positive quantities rather than sending them - the stepper's decrement stops at 1 for the same reason. */
    fun changeQuantity(itemId: String, newQuantity: Int) {
        if (newQuantity <= 0) return
        val cartId = _uiState.value.cart?.id ?: return

        viewModelScope.launch {
            when (val result = cartApi.updateItemQuantity(cartId, itemId, newQuantity)) {
                is UpdateCartItemQuantityOutcome.Success ->
                    _uiState.value = _uiState.value.copy(cart = result.cart, errorMessage = null)
                UpdateCartItemQuantityOutcome.CartNotFound -> _uiState.value =
                    _uiState.value.copy(errorMessage = "Cart not found - please restart the sale")
                UpdateCartItemQuantityOutcome.ItemNotFound -> _uiState.value =
                    _uiState.value.copy(errorMessage = "Item not found")
                is UpdateCartItemQuantityOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(errorMessage = result.message)
                is UpdateCartItemQuantityOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(errorMessage = result.message)
            }
        }
    }

    /** Removes a line item, keeping enough of it in [SaleUiState.lastVoidedItem] to offer Undo. */
    fun voidItem(itemId: String, reason: String? = null) {
        val cart = _uiState.value.cart ?: return
        val item = cart.items.find { it.id == itemId } ?: return

        viewModelScope.launch {
            when (val result = cartApi.removeItem(cart.id, itemId, reason)) {
                is RemoveCartItemOutcome.Success -> _uiState.value = _uiState.value.copy(
                    cart = result.cart,
                    errorMessage = null,
                    lastVoidedItem = VoidedCartItem(
                        productId = item.productId,
                        productName = item.productName,
                        quantity = item.quantity,
                        selectedModifiers = item.selectedModifiers.map { SelectedModifierRequest(it.modifierId, it.option) },
                        discount = item.discount
                    )
                )
                RemoveCartItemOutcome.CartNotFound -> _uiState.value =
                    _uiState.value.copy(errorMessage = "Cart not found - please restart the sale")
                RemoveCartItemOutcome.ItemNotFound -> _uiState.value =
                    _uiState.value.copy(errorMessage = "Item not found")
                is RemoveCartItemOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(errorMessage = result.message)
                is RemoveCartItemOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(errorMessage = result.message)
            }
        }
    }

    /** Re-adds the last voided item as a new line - there is no server-side undo, so this replays the add. */
    fun undoVoid() {
        val voided = _uiState.value.lastVoidedItem ?: return
        val cartId = _uiState.value.cart?.id ?: return
        _uiState.value = _uiState.value.copy(lastVoidedItem = null)

        viewModelScope.launch {
            when (
                val result = cartApi.addItem(
                    cartId,
                    voided.productId,
                    voided.quantity,
                    voided.selectedModifiers,
                    voided.discount
                )
            ) {
                is AddCartItemOutcome.Success -> _uiState.value = _uiState.value.copy(cart = result.cart, errorMessage = null)
                AddCartItemOutcome.CartNotFound -> _uiState.value =
                    _uiState.value.copy(errorMessage = "Cart not found - please restart the sale")
                is AddCartItemOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(errorMessage = "Could not restore \"${voided.productName}\": ${result.message}")
                is AddCartItemOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(errorMessage = result.message)
            }
        }
    }

    fun dismissUndo() {
        _uiState.value = _uiState.value.copy(lastVoidedItem = null)
    }

    /** Applies a preset percentage-off discount to the whole cart. */
    fun applyQuickDiscount(percent: Double) {
        setCartDiscount(DiscountDto(type = "PERCENTAGE", value = percent))
    }

    fun clearCartDiscount() {
        setCartDiscount(null)
    }

    private fun setCartDiscount(discount: DiscountDto?) {
        val cartId = _uiState.value.cart?.id ?: return
        viewModelScope.launch {
            when (val result = cartApi.setCartDiscount(cartId, discount)) {
                is SetCartDiscountOutcome.Success -> _uiState.value = _uiState.value.copy(cart = result.cart, errorMessage = null)
                SetCartDiscountOutcome.CartNotFound -> _uiState.value =
                    _uiState.value.copy(errorMessage = "Cart not found - please restart the sale")
                is SetCartDiscountOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(errorMessage = result.message)
                is SetCartDiscountOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(errorMessage = result.message)
            }
        }
    }

    /** Placeholder: there's no staff-paging backend yet - this just confirms the action to the cashier. */
    fun requestAssistance() {
        _uiState.value = _uiState.value.copy(infoMessage = "A store manager has been notified.")
    }

    /** Placeholder: there's no product-creation UI yet - points the cashier at the admin console. */
    fun requestCreateItem() {
        _uiState.value =
            _uiState.value.copy(infoMessage = "Ask a store manager to add this product from the admin console.")
    }

    fun dismissInfoMessage() {
        _uiState.value = _uiState.value.copy(infoMessage = null)
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 300L
    }
}
