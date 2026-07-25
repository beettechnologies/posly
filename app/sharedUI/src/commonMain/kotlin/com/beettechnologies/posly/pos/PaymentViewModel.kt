package com.beettechnologies.posly.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.orders.ConfirmPaymentOutcome
import com.beettechnologies.posly.orders.GetOrderOutcome
import com.beettechnologies.posly.orders.OrderApi
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.payments.CreatePaymentOutcome
import com.beettechnologies.posly.payments.GetPaymentOutcome
import com.beettechnologies.posly.payments.PaymentApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Tender { CARD, CASH, GIFT_CARD }

enum class TerminalState { IDLE, POLLING, APPROVED, DECLINED, TIMED_OUT, ERROR }

data class PaymentUiState(
    val isLoadingOrder: Boolean = true,
    val order: OrderResponse? = null,
    val loadError: String? = null,
    val selectedTender: Tender = Tender.CARD,
    /** Editable for every tender type - defaults to the full remaining balance but can be reduced for a split. */
    val amountToApply: String = "",
    val cashTendered: String = "",
    val terminalState: TerminalState = TerminalState.IDLE,
    val errorMessage: String? = null,
    val isConfirming: Boolean = false,
    val completedOrder: OrderResponse? = null
) {
    val total: Double get() = order?.totals?.total ?: 0.0
    val remainingBalance: Double get() = order?.remainingBalance ?: 0.0

    /** Non-null only once a positive amount not exceeding what's still owed has been entered. */
    val amountToApplyValue: Double?
        get() = amountToApply.toDoubleOrNull()?.takeIf { it > 0.0 && it <= remainingBalance + 0.001 }

    /** Non-null only once a valid amount-to-apply AND cash covering it have been entered. */
    val changeDue: Double?
        get() {
            val applied = amountToApplyValue ?: return null
            return cashTendered.toDoubleOrNull()?.let { it - applied }?.takeIf { it >= -0.001 }
        }

    val isBusy: Boolean get() = isConfirming || terminalState == TerminalState.POLLING
}

/**
 * Cash/gift-card tenders confirm synchronously against the order via [OrderApi.confirmPayment].
 * Card is a two-step, asynchronous flow with no real terminal behind it: [startTerminal] creates a
 * gateway payment then polls [PaymentApi.getPayment] until the simulator's auto-resolve (see
 * PaymentGatewayService) settles it, or [pollTimeoutMillis] elapses.
 *
 * Every tender applies [PaymentUiState.amountToApplyValue] rather than always the full total, so a
 * split/partial payment works uniformly across tender types: a tender that doesn't fully cover the
 * order loops back to tender selection with the updated remaining balance instead of completing -
 * [PaymentUiState.completedOrder] is only set once the order's own status reports fully PAID.
 */
class PaymentViewModel(
    private val orderApi: OrderApi,
    private val paymentApi: PaymentApi,
    private val pollIntervalMillis: Long = 1000,
    private val pollTimeoutMillis: Long = 15000
) : ViewModel() {

    private val _uiState = MutableStateFlow(PaymentUiState())
    val uiState: StateFlow<PaymentUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    fun load(orderId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingOrder = true, loadError = null)
            when (val result = orderApi.getOrder(orderId)) {
                is GetOrderOutcome.Success -> _uiState.value = _uiState.value.copy(
                    isLoadingOrder = false,
                    order = result.order,
                    amountToApply = result.order.remainingBalance.toString()
                )
                GetOrderOutcome.NotFound -> _uiState.value =
                    _uiState.value.copy(isLoadingOrder = false, loadError = "Order not found")
                is GetOrderOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoadingOrder = false, loadError = result.message)
            }
        }
    }

    fun selectTender(tender: Tender) {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(
            selectedTender = tender,
            amountToApply = _uiState.value.remainingBalance.toString(),
            cashTendered = "",
            terminalState = TerminalState.IDLE,
            errorMessage = null
        )
    }

    fun updateAmountToApply(value: String) {
        _uiState.value = _uiState.value.copy(amountToApply = value)
    }

    fun updateCashTendered(value: String) {
        _uiState.value = _uiState.value.copy(cashTendered = value)
    }

    fun confirmNonCardPayment() {
        val state = _uiState.value
        val order = state.order ?: return
        if (state.isBusy) return
        val amount = state.amountToApplyValue ?: return
        if (state.selectedTender == Tender.CASH && state.changeDue == null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConfirming = true, errorMessage = null)
            val method = if (state.selectedTender == Tender.CASH) "CASH" else "GIFT_CARD"
            val reference = if (state.selectedTender == Tender.CASH) state.cashTendered else null
            when (val result = orderApi.confirmPayment(order.id, method, amount, reference)) {
                is ConfirmPaymentOutcome.Success -> applyTenderResult(result.order)
                ConfirmPaymentOutcome.OrderNotFound -> _uiState.value =
                    _uiState.value.copy(isConfirming = false, errorMessage = "Order not found")
                is ConfirmPaymentOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(isConfirming = false, errorMessage = result.message)
                is ConfirmPaymentOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isConfirming = false, errorMessage = result.message)
            }
        }
    }

    /** Also serves as the "Retry" action after a decline/timeout/network error - re-entering this while POLLING is a no-op. */
    fun startTerminal() {
        val state = _uiState.value
        val order = state.order ?: return
        val amount = state.amountToApplyValue ?: return
        if (state.terminalState == TerminalState.POLLING) return

        pollJob?.cancel()
        _uiState.value = _uiState.value.copy(terminalState = TerminalState.POLLING, errorMessage = null)

        pollJob = viewModelScope.launch {
            when (val result = paymentApi.createPayment(order.id, amount, "USD")) {
                is CreatePaymentOutcome.Success -> pollPayment(result.payment.id)
                CreatePaymentOutcome.OrderNotFound -> _uiState.value =
                    _uiState.value.copy(terminalState = TerminalState.ERROR, errorMessage = "Order not found")
                is CreatePaymentOutcome.GatewayError -> _uiState.value =
                    _uiState.value.copy(terminalState = TerminalState.ERROR, errorMessage = result.message)
                is CreatePaymentOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(terminalState = TerminalState.ERROR, errorMessage = result.message)
            }
        }
    }

    private suspend fun pollPayment(paymentId: String) {
        var elapsedMillis = 0L
        while (elapsedMillis < pollTimeoutMillis) {
            delay(pollIntervalMillis)
            elapsedMillis += pollIntervalMillis

            when (val result = paymentApi.getPayment(paymentId)) {
                is GetPaymentOutcome.Success -> when (result.payment.status) {
                    "APPROVED" -> {
                        finalizeApprovedPayment()
                        return
                    }
                    "DECLINED" -> {
                        _uiState.value = _uiState.value.copy(
                            terminalState = TerminalState.DECLINED,
                            errorMessage = (result.payment.declineReason ?: "Card declined") +
                                " - retry or choose another tender below."
                        )
                        return
                    }
                    else -> Unit // still INITIATED - keep polling
                }
                GetPaymentOutcome.NotFound -> {
                    _uiState.value = _uiState.value.copy(terminalState = TerminalState.ERROR, errorMessage = "Payment not found")
                    return
                }
                is GetPaymentOutcome.NetworkError -> {
                    _uiState.value = _uiState.value.copy(terminalState = TerminalState.ERROR, errorMessage = result.message)
                    return
                }
            }
        }
        _uiState.value = _uiState.value.copy(
            terminalState = TerminalState.TIMED_OUT,
            errorMessage = "The terminal did not respond in time - retry or choose another tender below."
        )
    }

    /** A resolved (approved or partial-completing) tender: either finishes the sale or loops back to tender selection. */
    private suspend fun finalizeApprovedPayment() {
        val orderId = _uiState.value.order?.id
        val refreshed = orderId?.let { orderApi.getOrder(it) }
        val order = (refreshed as? GetOrderOutcome.Success)?.order ?: _uiState.value.order
        if (order != null && order.status == "PAID") {
            _uiState.value = _uiState.value.copy(terminalState = TerminalState.APPROVED, order = order, completedOrder = order)
        } else if (order != null) {
            _uiState.value = _uiState.value.copy(
                terminalState = TerminalState.IDLE,
                order = order,
                amountToApply = order.remainingBalance.toString(),
                cashTendered = "",
                errorMessage = null
            )
        } else {
            _uiState.value = _uiState.value.copy(terminalState = TerminalState.APPROVED)
        }
    }

    private fun applyTenderResult(order: OrderResponse) {
        _uiState.value = if (order.status == "PAID") {
            _uiState.value.copy(isConfirming = false, order = order, completedOrder = order)
        } else {
            _uiState.value.copy(
                isConfirming = false,
                order = order,
                amountToApply = order.remainingBalance.toString(),
                cashTendered = "",
                terminalState = TerminalState.IDLE,
                errorMessage = null
            )
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        pollJob?.cancel()
    }
}
