package com.beettechnologies.posly.pos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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

object ReceiptModalTags {
    const val CONTAINER = "receipt_container"
    const val ITEM_PREFIX = "receipt_item_"
    const val SUBTOTAL_TEXT = "receipt_subtotal_text"
    const val DISCOUNT_TEXT = "receipt_discount_text"
    const val TAX_LINE_PREFIX = "receipt_tax_line_"
    const val TAX_TEXT = "receipt_tax_text"
    const val TOTAL_TEXT = "receipt_total_text"
    const val PAYMENT_PREFIX = "receipt_payment_"
    const val PRINT_BUTTON = "receipt_print_button"
    const val PRINT_STATUS_TEXT = "receipt_print_status_text"
    const val EMAIL_FALLBACK_PROMPT = "receipt_email_fallback_prompt"
    const val EMAIL_FIELD = "receipt_email_field"
    const val EMAIL_SEND_BUTTON = "receipt_email_send_button"
    const val EMAIL_STATUS_TEXT = "receipt_email_status_text"
    const val NEW_SALE_BUTTON = "receipt_new_sale_button"
}

/**
 * Shown once an order is fully PAID - the itemized/totals/payment sections are a pure client-side
 * rendering of the order the API already returned, while Print/Email are live actions backed by
 * [ReceiptViewModel] (print job submission, PDF email delivery, and the offline-printer/failed-send
 * fallback prompt).
 */
@Composable
fun ReceiptModal(order: OrderResponse, onDismiss: () -> Unit, viewModel: ReceiptViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(order.id) { viewModel.initialize(order) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receipt") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(ReceiptModalTags.CONTAINER)
            ) {
                order.items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag(ReceiptModalTags.ITEM_PREFIX + index),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${item.quantity}x ${item.productName}")
                        Text("$${item.lineTotal}")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text("Subtotal: $${order.totals.subtotal}", modifier = Modifier.testTag(ReceiptModalTags.SUBTOTAL_TEXT))
                    val discountTotal = order.totals.cartDiscountAmount + order.totals.itemDiscountTotal
                    if (discountTotal > 0.0) {
                        Text("Discount: -$${discountTotal}", modifier = Modifier.testTag(ReceiptModalTags.DISCOUNT_TEXT))
                    }
                    order.totals.taxBreakdown.forEachIndexed { index, line ->
                        Text(
                            "${line.name} (${line.ratePercent}%): $${line.amount}",
                            modifier = Modifier.testTag(ReceiptModalTags.TAX_LINE_PREFIX + index)
                        )
                    }
                    Text("Tax: $${order.totals.totalTax}", modifier = Modifier.testTag(ReceiptModalTags.TAX_TEXT))
                    Text(
                        "Total: $${order.totals.total}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(ReceiptModalTags.TOTAL_TEXT)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text("Payment", style = MaterialTheme.typography.titleSmall)
                    order.payments.forEachIndexed { index, payment ->
                        Text(
                            text = tenderReceiptLine(payment),
                            modifier = Modifier.padding(top = 4.dp).testTag(ReceiptModalTags.PAYMENT_PREFIX + index)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        onClick = viewModel::print,
                        enabled = !uiState.isPrinting,
                        modifier = Modifier.fillMaxWidth().testTag(ReceiptModalTags.PRINT_BUTTON)
                    ) {
                        Text(if (uiState.isPrinting) "Printing..." else "Print")
                    }
                    uiState.printMessage?.let { message ->
                        Text(message, modifier = Modifier.padding(top = 4.dp).testTag(ReceiptModalTags.PRINT_STATUS_TEXT))
                    }
                    if (uiState.showEmailFallback) {
                        Text(
                            "Want to email the receipt instead?",
                            modifier = Modifier.padding(top = 8.dp).testTag(ReceiptModalTags.EMAIL_FALLBACK_PROMPT)
                        )
                    }

                    OutlinedTextField(
                        value = uiState.emailAddress,
                        onValueChange = viewModel::updateEmailAddress,
                        label = { Text("Email address") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(ReceiptModalTags.EMAIL_FIELD)
                    )
                    TextButton(
                        onClick = viewModel::sendEmail,
                        enabled = uiState.emailAddress.isNotBlank() && !uiState.isSendingEmail,
                        modifier = Modifier.testTag(ReceiptModalTags.EMAIL_SEND_BUTTON)
                    ) {
                        Text(if (uiState.isSendingEmail) "Sending..." else "Email Receipt")
                    }
                    uiState.emailMessage?.let { message ->
                        Text(message, modifier = Modifier.testTag(ReceiptModalTags.EMAIL_STATUS_TEXT))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag(ReceiptModalTags.NEW_SALE_BUTTON)) {
                Text("New Sale")
            }
        }
    )
}

private fun tenderReceiptLine(payment: PaymentRecordResponse): String {
    val cardDetail = payment.maskedCardNumber?.let { masked -> " - $masked, auth ${payment.reference}" }.orEmpty()
    return "${payment.method}: $${payment.amount}$cardDetail"
}
