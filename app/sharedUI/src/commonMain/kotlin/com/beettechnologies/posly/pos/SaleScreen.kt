package com.beettechnologies.posly.pos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beettechnologies.posly.products.SearchResultItem
import org.koin.compose.viewmodel.koinViewModel

object SaleScreenTags {
    const val SEARCH_FIELD = "sale_search_field"
    const val SUGGESTION_PREFIX = "sale_suggestion_"
    const val NO_RESULTS_TEXT = "sale_no_results_text"
    const val CREATE_ITEM_BUTTON = "sale_create_item_button"
    const val REQUEST_ASSISTANCE_BUTTON = "sale_request_assistance_button"
    const val INFO_MESSAGE_TEXT = "sale_info_message_text"
    const val ERROR_TEXT = "sale_error_text"
    const val CART_ITEM_PREFIX = "sale_cart_item_"
    const val TOTAL_TEXT = "sale_total_text"
}

@Composable
fun SaleScreen(
    onBack: () -> Unit,
    viewModel: SaleViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("New Sale", style = MaterialTheme.typography.headlineSmall)
        }

        Row(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onQueryChange,
                    label = { Text("Search or scan a barcode") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SaleScreenTags.SEARCH_FIELD)
                        .onKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                viewModel.onEnterPressed()
                                true
                            } else {
                                false
                            }
                        }
                )

                if (uiState.isSearching) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                }

                if (uiState.errorMessage != null) {
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp).testTag(SaleScreenTags.ERROR_TEXT)
                    )
                }

                if (uiState.infoMessage != null) {
                    Text(
                        text = uiState.infoMessage.orEmpty(),
                        modifier = Modifier.padding(top = 12.dp).testTag(SaleScreenTags.INFO_MESSAGE_TEXT)
                    )
                }

                if (uiState.showNoResults) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        Text(
                            text = "No products found for \"${uiState.searchQuery}\"",
                            modifier = Modifier.testTag(SaleScreenTags.NO_RESULTS_TEXT)
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            Button(
                                onClick = viewModel::requestCreateItem,
                                modifier = Modifier.padding(end = 8.dp).testTag(SaleScreenTags.CREATE_ITEM_BUTTON)
                            ) {
                                Text("Create item")
                            }
                            Button(
                                onClick = viewModel::requestAssistance,
                                modifier = Modifier.testTag(SaleScreenTags.REQUEST_ASSISTANCE_BUTTON)
                            ) {
                                Text("Request assistance")
                            }
                        }
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(uiState.suggestions) { item ->
                        SuggestionRow(item, onClick = { viewModel.onSuggestionSelected(item) })
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp)) {
                Text("Cart", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                    items(uiState.cart?.items.orEmpty()) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .testTag(SaleScreenTags.CART_ITEM_PREFIX + item.id)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${item.quantity}x ${item.productName}")
                                Text("$${item.lineTotal}")
                            }
                        }
                    }
                }
                HorizontalDivider()
                val totals = uiState.cart?.totals
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text("Subtotal: $${totals?.subtotal ?: 0.0}")
                    Text("Tax: $${totals?.totalTax ?: 0.0}")
                    Text(
                        "Total: $${totals?.total ?: 0.0}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(SaleScreenTags.TOTAL_TEXT)
                    )
                }
            }
        }
    }

    val selectedProductId = uiState.selectedProductId
    if (selectedProductId != null) {
        ProductDetailModal(
            productId = selectedProductId,
            cartId = uiState.cart?.id,
            onDismiss = viewModel::dismissProductDetail,
            onAddedToCart = viewModel::onProductAdded
        )
    }
}

@Composable
private fun SuggestionRow(item: SearchResultItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
            .testTag(SaleScreenTags.SUGGESTION_PREFIX + item.id)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(item.name)
            Text("$${item.price}")
        }
    }
}
