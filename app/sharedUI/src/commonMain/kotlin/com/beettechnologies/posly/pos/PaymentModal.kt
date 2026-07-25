package com.beettechnologies.posly.pos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.orders.PaymentRecordResponse
import org.koin.compose.viewmodel.koinViewModel

object PaymentModalTags {
    const val CONTAINER = "payment_container"
    const val LOADING_INDICATOR = "payment_loading_indicator"
    const val LOAD_ERROR_TEXT = "payment_load_error_text"
    const val TOTAL_TEXT = "payment_total_text"
    const val REMAINING_BALANCE_TEXT = "payment_remaining_balance_text"
    const val APPLIED_TENDER_PREFIX = "payment_applied_tender_"
    const val TENDER_CARD_BUTTON = "payment_tender_card_button"
    const val TENDER_CASH_BUTTON = "payment_tender_cash_button"
    const val TENDER_GIFT_BUTTON = "payment_tender_gift_button"
    const val AMOUNT_TO_APPLY_FIELD = "payment_amount_to_apply_field"
    const val CASH_AMOUNT_FIELD = "payment_cash_amount_field"
    const val CHANGE_DUE_TEXT = "payment_change_due_text"
    const val TERMINAL_STATE_TEXT = "payment_terminal_state_text"
    const val ERROR_TEXT = "payment_error_text"
    const val CONFIRM_BUTTON = "payment_confirm_button"
    const val START_TERMINAL_BUTTON = "payment_start_terminal_button"
    const val CANCEL_BUTTON = "payment_cancel_button"
}

@Composable
fun PaymentModal(
    orderId: String,
    onDismiss: () -> Unit,
    onCompleted: (OrderResponse) -> Unit,
    viewModel: PaymentViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(orderId) { viewModel.load(orderId) }
    LaunchedEffect(uiState.completedOrder) {
        uiState.completedOrder?.let(onCompleted)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payment") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(PaymentModalTags.CONTAINER)
            ) {
                if (uiState.isLoadingOrder) {
                    CircularProgressIndicator(modifier = Modifier.testTag(PaymentModalTags.LOADING_INDICATOR))
                }

                if (uiState.loadError != null) {
                    Text(
                        text = uiState.loadError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(PaymentModalTags.LOAD_ERROR_TEXT)
                    )
                }

                val order = uiState.order
                if (order != null) {
                    Text(
                        text = "Total due: $${uiState.total}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(PaymentModalTags.TOTAL_TEXT)
                    )
                    Text(
                        text = "Remaining: $${uiState.remainingBalance}",
                        modifier = Modifier.testTag(PaymentModalTags.REMAINING_BALANCE_TEXT)
                    )

                    if (order.payments.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            order.payments.forEachIndexed { index, payment ->
                                Text(
                                    text = tenderSummary(payment),
                                    modifier = Modifier.testTag(PaymentModalTags.APPLIED_TENDER_PREFIX + index)
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        TenderButton(
                            label = "Card",
                            selected = uiState.selectedTender == Tender.CARD,
                            enabled = !uiState.isBusy,
                            testTag = PaymentModalTags.TENDER_CARD_BUTTON,
                            onClick = { viewModel.selectTender(Tender.CARD) }
                        )
                        TenderButton(
                            label = "Cash",
                            selected = uiState.selectedTender == Tender.CASH,
                            enabled = !uiState.isBusy,
                            testTag = PaymentModalTags.TENDER_CASH_BUTTON,
                            onClick = { viewModel.selectTender(Tender.CASH) }
                        )
                        TenderButton(
                            label = "Gift card",
                            selected = uiState.selectedTender == Tender.GIFT_CARD,
                            enabled = !uiState.isBusy,
                            testTag = PaymentModalTags.TENDER_GIFT_BUTTON,
                            onClick = { viewModel.selectTender(Tender.GIFT_CARD) }
                        )
                    }

                    OutlinedTextField(
                        value = uiState.amountToApply,
                        onValueChange = viewModel::updateAmountToApply,
                        label = { Text("Amount to apply") },
                        enabled = !uiState.isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .testTag(PaymentModalTags.AMOUNT_TO_APPLY_FIELD)
                    )

                    when (uiState.selectedTender) {
                        Tender.CASH -> {
                            OutlinedTextField(
                                value = uiState.cashTendered,
                                onValueChange = viewModel::updateCashTendered,
                                label = { Text("Cash tendered") },
                                enabled = !uiState.isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .testTag(PaymentModalTags.CASH_AMOUNT_FIELD)
                            )
                            val change = uiState.changeDue
                            Text(
                                text = if (change != null) "Change due: $${change}" else "Cash tendered must cover the amount to apply",
                                modifier = Modifier.padding(top = 8.dp).testTag(PaymentModalTags.CHANGE_DUE_TEXT)
                            )
                        }

                        Tender.GIFT_CARD -> {
                            Text(
                                text = "Charges the amount above to the gift card.",
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }

                        Tender.CARD -> {
                            Text(
                                text = when (uiState.terminalState) {
                                    TerminalState.IDLE -> "Ready to start"
                                    TerminalState.POLLING -> "Waiting for the terminal..."
                                    TerminalState.APPROVED -> "Approved"
                                    TerminalState.DECLINED -> "Declined - retry or choose another tender below"
                                    TerminalState.TIMED_OUT -> "Timed out - retry or choose another tender below"
                                    TerminalState.ERROR -> "Error - retry or choose another tender below"
                                },
                                modifier = Modifier.padding(top = 12.dp).testTag(PaymentModalTags.TERMINAL_STATE_TEXT)
                            )
                        }
                    }

                    if (uiState.errorMessage != null) {
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp).testTag(PaymentModalTags.ERROR_TEXT)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (uiState.selectedTender == Tender.CARD) {
                val isRetry = uiState.terminalState == TerminalState.ERROR ||
                    uiState.terminalState == TerminalState.DECLINED ||
                    uiState.terminalState == TerminalState.TIMED_OUT
                Button(
                    onClick = viewModel::startTerminal,
                    enabled = uiState.amountToApplyValue != null &&
                        uiState.terminalState != TerminalState.POLLING &&
                        uiState.terminalState != TerminalState.APPROVED,
                    modifier = Modifier.testTag(PaymentModalTags.START_TERMINAL_BUTTON)
                ) {
                    Text(if (isRetry) "Retry" else "Start terminal")
                }
            } else {
                Button(
                    onClick = viewModel::confirmNonCardPayment,
                    enabled = !uiState.isBusy &&
                        (if (uiState.selectedTender == Tender.CASH) uiState.changeDue != null else uiState.amountToApplyValue != null),
                    modifier = Modifier.testTag(PaymentModalTags.CONFIRM_BUTTON)
                ) {
                    Text("Confirm")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(PaymentModalTags.CANCEL_BUTTON)) {
                Text("Cancel")
            }
        }
    )
}

private fun tenderSummary(payment: PaymentRecordResponse): String {
    val cardDetail = payment.maskedCardNumber?.let { masked -> " (${masked}, auth ${payment.reference})" }.orEmpty()
    return "${payment.method}: $${payment.amount}$cardDetail"
}

@Composable
private fun TenderButton(label: String, selected: Boolean, enabled: Boolean, testTag: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.padding(end = 8.dp).testTag(testTag)) {
            Text(label)
        }
    } else {
        TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(end = 8.dp).testTag(testTag)) {
            Text(label)
        }
    }
}
