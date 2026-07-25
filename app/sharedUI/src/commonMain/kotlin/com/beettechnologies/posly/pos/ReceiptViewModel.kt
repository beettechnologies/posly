package com.beettechnologies.posly.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.receipts.EmailReceiptOutcome
import com.beettechnologies.posly.receipts.ListPrintersOutcome
import com.beettechnologies.posly.receipts.PrintReceiptOutcome
import com.beettechnologies.posly.receipts.ReceiptApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PrintState { IDLE, PRINTING, PRINTED, QUEUED, ERROR }

enum class EmailState { IDLE, SENDING, SENT, ERROR }

data class ReceiptUiState(
    val order: OrderResponse? = null,
    val isLoadingPrinter: Boolean = true,
    val printerId: String? = null,
    val printState: PrintState = PrintState.IDLE,
    val printMessage: String? = null,
    /** Shown after any print outcome that didn't put paper in the customer's hand - offline, queued, or errored. */
    val showEmailFallback: Boolean = false,
    val emailAddress: String = "",
    val emailState: EmailState = EmailState.IDLE,
    val emailMessage: String? = null
) {
    val isPrinting: Boolean get() = printState == PrintState.PRINTING
    val isSendingEmail: Boolean get() = emailState == EmailState.SENDING
}

/**
 * Backs the Print/Email actions on [ReceiptModal]. Print targets the store's first registered
 * printer (this project models one receipt printer per store - see PrinterRegistryService); a
 * queued job (printer offline, or transient retries exhausted server-side) surfaces an inline
 * prompt offering to email the receipt instead, satisfying the print-fallback requirement without
 * the cashier needing to diagnose why printing didn't complete.
 */
class ReceiptViewModel(private val receiptApi: ReceiptApi) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiptUiState())
    val uiState: StateFlow<ReceiptUiState> = _uiState.asStateFlow()

    fun initialize(order: OrderResponse) {
        if (_uiState.value.order?.id == order.id) return
        _uiState.value = ReceiptUiState(order = order)
        viewModelScope.launch {
            when (val result = receiptApi.listPrinters(order.storeId)) {
                is ListPrintersOutcome.Success -> _uiState.value = _uiState.value.copy(
                    isLoadingPrinter = false,
                    printerId = result.printers.firstOrNull()?.id
                )
                is ListPrintersOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoadingPrinter = false, printerId = null)
            }
        }
    }

    fun print() {
        val order = _uiState.value.order ?: return
        val printerId = _uiState.value.printerId
        if (printerId == null) {
            _uiState.value = _uiState.value.copy(
                printState = PrintState.ERROR,
                printMessage = "No printer configured for this store.",
                showEmailFallback = true
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(printState = PrintState.PRINTING, printMessage = null, showEmailFallback = false)
            when (val result = receiptApi.printReceipt(order.id, printerId)) {
                is PrintReceiptOutcome.Printed -> _uiState.value = _uiState.value.copy(
                    printState = PrintState.PRINTED,
                    printMessage = "Receipt sent to the printer."
                )
                is PrintReceiptOutcome.Queued -> _uiState.value = _uiState.value.copy(
                    printState = PrintState.QUEUED,
                    printMessage = result.job.message ?: "Printer unavailable - job queued.",
                    showEmailFallback = true
                )
                PrintReceiptOutcome.PrinterNotFound -> _uiState.value = _uiState.value.copy(
                    printState = PrintState.ERROR,
                    printMessage = "No printer configured for this store.",
                    showEmailFallback = true
                )
                PrintReceiptOutcome.OrderNotFound -> _uiState.value = _uiState.value.copy(
                    printState = PrintState.ERROR,
                    printMessage = "Order not found."
                )
                is PrintReceiptOutcome.NetworkError -> _uiState.value = _uiState.value.copy(
                    printState = PrintState.ERROR,
                    printMessage = result.message,
                    showEmailFallback = true
                )
            }
        }
    }

    fun updateEmailAddress(value: String) {
        _uiState.value = _uiState.value.copy(emailAddress = value, emailMessage = null)
    }

    fun sendEmail() {
        val order = _uiState.value.order ?: return
        val address = _uiState.value.emailAddress
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(emailState = EmailState.SENDING, emailMessage = null)
            when (val result = receiptApi.emailReceipt(order.id, address)) {
                is EmailReceiptOutcome.Sent -> _uiState.value = _uiState.value.copy(
                    emailState = EmailState.SENT,
                    emailMessage = "Receipt emailed to ${result.email.recipient}.",
                    showEmailFallback = false
                )
                is EmailReceiptOutcome.Failed -> _uiState.value = _uiState.value.copy(
                    emailState = EmailState.ERROR,
                    emailMessage = result.email.message ?: "Failed to send email."
                )
                is EmailReceiptOutcome.InvalidEmail -> _uiState.value = _uiState.value.copy(
                    emailState = EmailState.ERROR,
                    emailMessage = result.message
                )
                EmailReceiptOutcome.OrderNotFound -> _uiState.value = _uiState.value.copy(
                    emailState = EmailState.ERROR,
                    emailMessage = "Order not found."
                )
                is EmailReceiptOutcome.NetworkError -> _uiState.value = _uiState.value.copy(
                    emailState = EmailState.ERROR,
                    emailMessage = result.message
                )
            }
        }
    }

    fun dismissEmailFallback() {
        _uiState.value = _uiState.value.copy(showEmailFallback = false)
    }
}
