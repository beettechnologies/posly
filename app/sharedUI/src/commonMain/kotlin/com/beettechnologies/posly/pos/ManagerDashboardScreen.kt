package com.beettechnologies.posly.pos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

object ManagerDashboardScreenTags {
    const val LOADING_INDICATOR = "manager_dashboard_loading_indicator"
    const val ERROR_TEXT = "manager_dashboard_error_text"
    const val STORE_BUTTON = "manager_dashboard_store_button"
    const val REFRESH_BUTTON = "manager_dashboard_refresh_button"
    const val SALES_CARD = "manager_dashboard_sales_card"
    const val TRANSACTIONS_CARD = "manager_dashboard_transactions_card"
    const val CASH_ON_HAND_CARD = "manager_dashboard_cash_on_hand_card"
    const val TOP_PRODUCT_ROW_PREFIX = "manager_dashboard_top_product_row_"
}

/**
 * Every metric here drills through to [TransactionListScreen] for the orders behind it - the
 * sales/transactions tiles show the whole day, a SKU row narrows to just that product's orders.
 */
@Composable
fun ManagerDashboardScreen(
    onBack: () -> Unit,
    onDrillDown: (storeId: String, from: String, to: String, productId: String?, productName: String?) -> Unit,
    viewModel: ManagerDashboardViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var storeMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadStores() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Dashboard", style = MaterialTheme.typography.headlineSmall)
            TextButton(
                onClick = { viewModel.refresh() },
                enabled = !uiState.isRefreshing && uiState.selectedStoreId != null,
                modifier = Modifier.testTag(ManagerDashboardScreenTags.REFRESH_BUTTON)
            ) {
                Text("Refresh")
            }
        }

        if (uiState.stores.size > 1) {
            Box(modifier = Modifier.padding(top = 12.dp)) {
                OutlinedButton(
                    onClick = { storeMenuExpanded = true },
                    modifier = Modifier.testTag(ManagerDashboardScreenTags.STORE_BUTTON)
                ) {
                    Text(uiState.selectedStoreName ?: "Select store")
                }
                DropdownMenu(expanded = storeMenuExpanded, onDismissRequest = { storeMenuExpanded = false }) {
                    uiState.stores.forEach { store ->
                        DropdownMenuItem(
                            text = { Text(store.name) },
                            onClick = {
                                viewModel.selectStore(store.id)
                                storeMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (uiState.isLoadingStores || uiState.isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp).testTag(ManagerDashboardScreenTags.LOADING_INDICATOR))
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(ManagerDashboardScreenTags.ERROR_TEXT)
            )
        }

        val sales = uiState.sales
        val storeId = uiState.selectedStoreId
        if (sales != null && storeId != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clickable { onDrillDown(storeId, sales.periodStart, sales.periodEnd, null, null) }
                    .testTag(ManagerDashboardScreenTags.SALES_CARD)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Today's Sales", style = MaterialTheme.typography.titleMedium)
                    Text("$${sales.netSales}", style = MaterialTheme.typography.headlineMedium)
                    Text("Gross: $${sales.grossSales}  •  Refunds: $${sales.refundsTotal}")
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clickable { onDrillDown(storeId, sales.periodStart, sales.periodEnd, null, null) }
                    .testTag(ManagerDashboardScreenTags.TRANSACTIONS_CARD)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Transactions", style = MaterialTheme.typography.titleMedium)
                    Text("${sales.orderCount}", style = MaterialTheme.typography.headlineMedium)
                    Text("${sales.itemsSold} items sold")
                }
            }

            Text("Top Sellers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            if (uiState.topProducts.isEmpty()) {
                Text("No sales yet today.")
            }
            uiState.topProducts.forEach { product ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable { onDrillDown(storeId, sales.periodStart, sales.periodEnd, product.productId, product.productName) }
                        .testTag(ManagerDashboardScreenTags.TOP_PRODUCT_ROW_PREFIX + product.productId)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(product.productName)
                        Text("${product.quantitySold} sold  •  $${product.revenue}")
                    }
                }
            }
        }

        val cashOnHand = uiState.cashOnHand
        if (cashOnHand != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag(ManagerDashboardScreenTags.CASH_ON_HAND_CARD)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Cash on Hand", style = MaterialTheme.typography.titleMedium)
                    Text("$${cashOnHand.totalExpectedCash}", style = MaterialTheme.typography.headlineMedium)
                    Text("${cashOnHand.openShiftCount} open shift(s)")
                }
            }
        }
    }
}
