package com.beettechnologies.posly.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.orders.GetOrderOutcome
import com.beettechnologies.posly.orders.OrderApi
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.orders.RefundLineItemRequest
import com.beettechnologies.posly.orders.RefundOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** One refundable line on the loaded order, with the cashier's in-progress selection for it. */
data class RefundLineState(
    val cartItemId: String,
    val productName: String,
    val availableQuantity: Int,
    val selectedQuantity: Int = 0,
    val restock: Boolean = false
)

data class RefundUiState(
    val orderIdInput: String = "",
    val isLoadingOrder: Boolean = false,
    val loadError: String? = null,
    val order: OrderResponse? = null,
    val lines: List<RefundLineState> = emptyList(),
    val method: String = "CARD",
    val reason: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    /** Set once a CARD attempt has failed at the gateway, prompting the cashier to fall back to MANUAL. */
    val cardFailed: Boolean = false,
    val completedOrder: OrderResponse? = null
) {
    val maxRefundableAmount: Double get() = order?.remainingRefundable ?: 0.0
    val hasSelection: Boolean get() = lines.any { it.selectedQuantity > 0 }
    val canSubmit: Boolean
        get() = !isSubmitting && hasSelection && (method != "MANUAL" || reason.isNotBlank())
}

/**
 * Drives the standalone refund/returns workflow: an order is looked up by id (rather than carried
 * over from an in-progress sale), its still-refundable line items are shown with the quantity
 * already refunded subtracted out, and a CARD or MANUAL refund is submitted for the selected
 * lines/quantities/restock flags. A CARD attempt that fails at the gateway (see
 * [RefundOutcome.GatewayError]) sets [RefundUiState.cardFailed] rather than an outright error, so
 * the UI can offer a manual fallback that completes the very same refund attempt (same
 * [refundId]) with a required reason instead of forcing the cashier to start over.
 */
class RefundViewModel(
    private val orderApi: OrderApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(RefundUiState())
    val uiState: StateFlow<RefundUiState> = _uiState.asStateFlow()

    private var refundId: String = newRefundId()

    fun updateOrderIdInput(value: String) {
        _uiState.value = _uiState.value.copy(orderIdInput = value)
    }

    fun loadOrder() {
        val orderId = _uiState.value.orderIdInput.trim()
        if (orderId.isEmpty() || _uiState.value.isLoadingOrder) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingOrder = true, loadError = null, completedOrder = null)
            when (val result = orderApi.getOrder(orderId)) {
                is GetOrderOutcome.Success -> applyLoadedOrder(result.order)
                GetOrderOutcome.NotFound -> _uiState.value =
                    _uiState.value.copy(isLoadingOrder = false, loadError = "Order not found", order = null, lines = emptyList())
                is GetOrderOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoadingOrder = false, loadError = result.message)
            }
        }
    }

    private fun applyLoadedOrder(order: OrderResponse) {
        refundId = newRefundId()
        _uiState.value = _uiState.value.copy(
            isLoadingOrder = false,
            loadError = null,
            order = order,
            lines = linesFor(order),
            method = "CARD",
            reason = "",
            errorMessage = null,
            cardFailed = false
        )
    }

    private fun linesFor(order: OrderResponse): List<RefundLineState> {
        val refundedQuantity = order.refunds
            .flatMap { it.lineItems }
            .groupBy { it.cartItemId }
            .mapValues { (_, items) -> items.sumOf { it.quantity } }
        return order.items.mapNotNull { item ->
            val available = item.quantity - (refundedQuantity[item.id] ?: 0)
            if (available <= 0) null else RefundLineState(item.id, item.productName, available)
        }
    }

    fun updateLineQuantity(cartItemId: String, quantity: Int) {
        _uiState.value = _uiState.value.copy(
            lines = _uiState.value.lines.map { line ->
                if (line.cartItemId == cartItemId) {
                    line.copy(selectedQuantity = quantity.coerceIn(0, line.availableQuantity))
                } else {
                    line
                }
            }
        )
    }

    fun toggleRestock(cartItemId: String) {
        _uiState.value = _uiState.value.copy(
            lines = _uiState.value.lines.map { line ->
                if (line.cartItemId == cartItemId) line.copy(restock = !line.restock) else line
            }
        )
    }

    fun selectMethod(method: String) {
        if (_uiState.value.isSubmitting) return
        _uiState.value = _uiState.value.copy(method = method, errorMessage = null)
    }

    fun updateReason(value: String) {
        _uiState.value = _uiState.value.copy(reason = value)
    }

    /** Switches a failed CARD attempt over to MANUAL, keeping the same line selection and [refundId]. */
    fun useManualFallback() {
        _uiState.value = _uiState.value.copy(method = "MANUAL", cardFailed = false, errorMessage = null)
    }

    fun submit() {
        val state = _uiState.value
        val order = state.order ?: return
        if (!state.canSubmit) return

        val lineItems = state.lines.filter { it.selectedQuantity > 0 }.map {
            RefundLineItemRequest(cartItemId = it.cartItemId, quantity = it.selectedQuantity, restock = it.restock)
        }
        val reason = state.reason.trim().ifEmpty { null }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, errorMessage = null, cardFailed = false)
            when (val result = orderApi.refund(order.id, refundId, state.method, lineItems, reason)) {
                is RefundOutcome.Success -> {
                    refundId = newRefundId()
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        order = result.order,
                        lines = linesFor(result.order),
                        method = "CARD",
                        reason = "",
                        completedOrder = result.order
                    )
                }
                RefundOutcome.OrderNotFound -> _uiState.value =
                    _uiState.value.copy(isSubmitting = false, errorMessage = "Order not found")
                is RefundOutcome.GatewayError -> _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = result.message,
                    cardFailed = state.method == "CARD"
                )
                is RefundOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(isSubmitting = false, errorMessage = result.message)
                is RefundOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isSubmitting = false, errorMessage = result.message)
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun reset() {
        refundId = newRefundId()
        _uiState.value = RefundUiState()
    }

    private fun newRefundId(): String = "refund-${Random.nextLong()}-${Random.nextLong()}"
}
