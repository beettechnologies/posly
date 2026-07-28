package com.beettechnologies.posly.pos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.beettechnologies.posly.accessibility.statusMessage
import org.koin.compose.viewmodel.koinViewModel

object TransactionListScreenTags {
    const val LOADING_INDICATOR = "transaction_list_loading_indicator"
    const val ERROR_TEXT = "transaction_list_error_text"
    const val FILTER_TEXT = "transaction_list_filter_text"
    const val ORDER_ROW_PREFIX = "transaction_list_order_row_"
    const val EMPTY_TEXT = "transaction_list_empty_text"
}

@Composable
fun TransactionListScreen(
    storeId: String,
    from: String,
    to: String,
    productId: String?,
    productName: String?,
    onBack: () -> Unit,
    viewModel: TransactionListViewModel = koinViewModel()
) {
    LaunchedEffect(storeId, from, to, productId) { viewModel.initialize(storeId, from, to, productId, productName) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Transactions", style = MaterialTheme.typography.headlineSmall)
        }

        if (uiState.filterProductName != null) {
            Text(
                "Filtered by: ${uiState.filterProductName}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp).testTag(TransactionListScreenTags.FILTER_TEXT)
            )
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp).testTag(TransactionListScreenTags.LOADING_INDICATOR))
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp).testTag(TransactionListScreenTags.ERROR_TEXT).statusMessage()
            )
        }

        if (!uiState.isLoading && uiState.errorMessage == null && uiState.orders.isEmpty()) {
            Text(
                "No transactions in this window.",
                modifier = Modifier.padding(top = 16.dp).testTag(TransactionListScreenTags.EMPTY_TEXT)
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            items(uiState.orders) { order ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag(TransactionListScreenTags.ORDER_ROW_PREFIX + order.id)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(order.checkedOutAt, style = MaterialTheme.typography.bodySmall)
                            Text(order.status, style = MaterialTheme.typography.labelMedium)
                        }
                        Text("$${order.totals.total}", style = MaterialTheme.typography.titleMedium)
                        Text(order.items.joinToString(", ") { "${it.quantity}x ${it.productName}" })
                        val methods = order.payments.map { it.method }.distinct()
                        if (methods.isNotEmpty()) {
                            Text("Paid via: ${methods.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
