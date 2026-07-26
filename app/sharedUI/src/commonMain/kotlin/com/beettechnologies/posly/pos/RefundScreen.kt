package com.beettechnologies.posly.pos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

object RefundScreenTags {
    const val ORDER_ID_FIELD = "refund_order_id_field"
    const val LOAD_BUTTON = "refund_load_button"
    const val LOADING_INDICATOR = "refund_loading_indicator"
    const val LOAD_ERROR_TEXT = "refund_load_error_text"
    const val ORDER_STATUS_TEXT = "refund_order_status_text"
    const val MAX_REFUNDABLE_TEXT = "refund_max_refundable_text"
    const val LINE_PREFIX = "refund_line_"
    const val LINE_QUANTITY_FIELD_PREFIX = "refund_line_quantity_field_"
    const val LINE_RESTOCK_CHECKBOX_PREFIX = "refund_line_restock_checkbox_"
    const val METHOD_CARD_BUTTON = "refund_method_card_button"
    const val METHOD_MANUAL_BUTTON = "refund_method_manual_button"
    const val REASON_FIELD = "refund_reason_field"
    const val CARD_FAILED_TEXT = "refund_card_failed_text"
    const val USE_MANUAL_FALLBACK_BUTTON = "refund_use_manual_fallback_button"
    const val ERROR_TEXT = "refund_error_text"
    const val SUBMIT_BUTTON = "refund_submit_button"
    const val OUTCOME_TEXT = "refund_outcome_text"
}

@Composable
fun RefundScreen(
    onBack: () -> Unit,
    viewModel: RefundViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Refunds & returns", style = MaterialTheme.typography.headlineSmall)
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.orderIdInput,
                onValueChange = viewModel::updateOrderIdInput,
                label = { Text("Order ID") },
                enabled = !uiState.isLoadingOrder,
                modifier = Modifier.weight(1f).testTag(RefundScreenTags.ORDER_ID_FIELD)
            )
            Button(
                onClick = viewModel::loadOrder,
                enabled = !uiState.isLoadingOrder && uiState.orderIdInput.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp).testTag(RefundScreenTags.LOAD_BUTTON)
            ) {
                Text("Load")
            }
        }

        if (uiState.isLoadingOrder) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp).testTag(RefundScreenTags.LOADING_INDICATOR))
        }

        if (uiState.loadError != null) {
            Text(
                text = uiState.loadError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp).testTag(RefundScreenTags.LOAD_ERROR_TEXT)
            )
        }

        val order = uiState.order
        if (order != null) {
            Text(
                text = "Order status: ${order.status}",
                modifier = Modifier.padding(top = 16.dp).testTag(RefundScreenTags.ORDER_STATUS_TEXT)
            )
            Text(
                text = "Maximum refundable: $${uiState.maxRefundableAmount}",
                modifier = Modifier.testTag(RefundScreenTags.MAX_REFUNDABLE_TEXT)
            )

            if (uiState.completedOrder != null) {
                Text(
                    text = if (order.status == "REFUNDED") "Refund complete" else "Refund recorded - order is now partially refunded",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).testTag(RefundScreenTags.OUTCOME_TEXT)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

            LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                items(uiState.lines, key = { it.cartItemId }) { line ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(RefundScreenTags.LINE_PREFIX + line.cartItemId)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(line.productName, style = MaterialTheme.typography.titleSmall)
                            Text("Available to refund: ${line.availableQuantity}")
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedTextField(
                                    value = line.selectedQuantity.toString(),
                                    onValueChange = { value ->
                                        viewModel.updateLineQuantity(line.cartItemId, value.toIntOrNull() ?: 0)
                                    },
                                    label = { Text("Qty") },
                                    modifier = Modifier.weight(1f)
                                        .testTag(RefundScreenTags.LINE_QUANTITY_FIELD_PREFIX + line.cartItemId)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Checkbox(
                                        checked = line.restock,
                                        onCheckedChange = { viewModel.toggleRestock(line.cartItemId) },
                                        modifier = Modifier.testTag(RefundScreenTags.LINE_RESTOCK_CHECKBOX_PREFIX + line.cartItemId)
                                    )
                                    Text("Restock")
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            Row(modifier = Modifier.padding(top = 12.dp)) {
                MethodButton(
                    label = "Card",
                    selected = uiState.method == "CARD",
                    enabled = !uiState.isSubmitting,
                    testTag = RefundScreenTags.METHOD_CARD_BUTTON,
                    onClick = { viewModel.selectMethod("CARD") }
                )
                MethodButton(
                    label = "Manual",
                    selected = uiState.method == "MANUAL",
                    enabled = !uiState.isSubmitting,
                    testTag = RefundScreenTags.METHOD_MANUAL_BUTTON,
                    onClick = { viewModel.selectMethod("MANUAL") }
                )
            }

            if (uiState.method == "MANUAL") {
                OutlinedTextField(
                    value = uiState.reason,
                    onValueChange = viewModel::updateReason,
                    label = { Text("Reason (required)") },
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(RefundScreenTags.REASON_FIELD)
                )
            }

            if (uiState.cardFailed) {
                Text(
                    text = "The card refund could not be completed. Enter a reason and process it manually instead.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp).testTag(RefundScreenTags.CARD_FAILED_TEXT)
                )
                TextButton(
                    onClick = viewModel::useManualFallback,
                    modifier = Modifier.testTag(RefundScreenTags.USE_MANUAL_FALLBACK_BUTTON)
                ) {
                    Text("Process manually")
                }
            }

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp).testTag(RefundScreenTags.ERROR_TEXT)
                )
            }

            Button(
                onClick = viewModel::submit,
                enabled = uiState.canSubmit,
                modifier = Modifier.padding(top = 12.dp).testTag(RefundScreenTags.SUBMIT_BUTTON)
            ) {
                Text("Submit refund")
            }
        }
    }
}

@Composable
private fun MethodButton(label: String, selected: Boolean, enabled: Boolean, testTag: String, onClick: () -> Unit) {
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
