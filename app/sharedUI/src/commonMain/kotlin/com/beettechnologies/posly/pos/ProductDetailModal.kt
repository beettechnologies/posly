package com.beettechnologies.posly.pos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beettechnologies.posly.cart.CartResponse
import org.koin.compose.viewmodel.koinViewModel

object ProductDetailModalTags {
    const val CONTAINER = "product_detail_container"
    const val OPTION_PREFIX = "product_detail_option_"
    const val OOS_REASON_PREFIX = "product_detail_oos_reason_"
    const val QUANTITY_TEXT = "product_detail_quantity_text"
    const val QUANTITY_INCREMENT = "product_detail_quantity_increment"
    const val QUANTITY_DECREMENT = "product_detail_quantity_decrement"
    const val LINE_TOTAL_TEXT = "product_detail_line_total_text"
    const val ERROR_TEXT = "product_detail_error_text"
    const val ADD_TO_CART_BUTTON = "product_detail_add_to_cart_button"
    const val CANCEL_BUTTON = "product_detail_cancel_button"
}

@Composable
fun ProductDetailModal(
    productId: String,
    cartId: String?,
    onDismiss: () -> Unit,
    onAddedToCart: (CartResponse) -> Unit,
    viewModel: ProductDetailViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(productId) { viewModel.load(productId) }
    LaunchedEffect(uiState.added) {
        uiState.added?.let(onAddedToCart)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiState.product?.name ?: "Product") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(ProductDetailModalTags.CONTAINER)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                }

                uiState.product?.let { product ->
                    val description = product.description
                    if (!description.isNullOrBlank()) {
                        Text(description)
                    }

                    product.modifiers.forEach { modifier ->
                        Text(
                            modifier.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        modifier.options.forEach { option ->
                            val isUnavailable = option in modifier.unavailableOptions
                            val isSelected = uiState.selectedOptions[modifier.id] == option
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isUnavailable) { viewModel.selectOption(modifier.id, option) }
                                    .testTag(ProductDetailModalTags.OPTION_PREFIX + modifier.id + "_" + option)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.selectOption(modifier.id, option) },
                                    enabled = !isUnavailable
                                )
                                Column {
                                    Text(
                                        text = option + if (modifier.additionalCost != 0.0) " (+$${modifier.additionalCost})" else "",
                                        color = if (isUnavailable) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    if (isUnavailable) {
                                        Text(
                                            text = "Out of stock",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.testTag(
                                                ProductDetailModalTags.OOS_REASON_PREFIX + modifier.id + "_" + option
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Quantity", modifier = Modifier.padding(end = 12.dp))
                        TextButton(
                            onClick = viewModel::decrementQuantity,
                            modifier = Modifier.testTag(ProductDetailModalTags.QUANTITY_DECREMENT)
                        ) { Text("-") }
                        Text(
                            text = "${uiState.quantity}",
                            modifier = Modifier.padding(horizontal = 8.dp).testTag(ProductDetailModalTags.QUANTITY_TEXT)
                        )
                        TextButton(
                            onClick = viewModel::incrementQuantity,
                            modifier = Modifier.testTag(ProductDetailModalTags.QUANTITY_INCREMENT)
                        ) { Text("+") }
                    }

                    Text(
                        text = "Total: $${uiState.lineTotal}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp).testTag(ProductDetailModalTags.LINE_TOTAL_TEXT)
                    )
                }

                if (uiState.errorMessage != null) {
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp).testTag(ProductDetailModalTags.ERROR_TEXT)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { cartId?.let { viewModel.addToCart(it) } },
                enabled = !uiState.isAdding && uiState.product != null && cartId != null,
                modifier = Modifier.testTag(ProductDetailModalTags.ADD_TO_CART_BUTTON)
            ) {
                Text("Add to cart")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ProductDetailModalTags.CANCEL_BUTTON)) {
                Text("Cancel")
            }
        }
    )
}
