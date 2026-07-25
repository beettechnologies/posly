package com.beettechnologies.posly.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.cart.AddCartItemOutcome
import com.beettechnologies.posly.cart.CartApi
import com.beettechnologies.posly.cart.CartResponse
import com.beettechnologies.posly.cart.CartSessionStore
import com.beettechnologies.posly.cart.CreateCartOutcome
import com.beettechnologies.posly.cart.GetCartOutcome
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

data class SaleUiState(
    val cart: CartResponse? = null,
    val searchQuery: String = "",
    val suggestions: List<SearchResultItem> = emptyList(),
    val isSearching: Boolean = false,
    val showNoResults: Boolean = false,
    val infoMessage: String? = null,
    val errorMessage: String? = null
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

    fun onSuggestionSelected(item: SearchResultItem) {
        searchJob?.cancel()
        viewModelScope.launch { addToCart(item) }
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
