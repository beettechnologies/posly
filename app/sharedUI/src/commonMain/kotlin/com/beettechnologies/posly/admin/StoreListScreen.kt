package com.beettechnologies.posly.admin

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

object StoreListScreenTags {
    const val ADD_BUTTON = "store_list_add_button"
    const val ERROR_TEXT = "store_list_error_text"
    const val ITEM_PREFIX = "store_list_item_"
}

@Composable
fun StoreListScreen(
    onAddStore: () -> Unit,
    onEditStore: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: StoreListViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Stores", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onAddStore, modifier = Modifier.testTag(StoreListScreenTags.ADD_BUTTON)) {
                Text("+ Add")
            }
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp).testTag(StoreListScreenTags.ERROR_TEXT)
            )
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            items(uiState.stores) { store ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable(onClickLabel = "Edit ${store.name}", role = Role.Button) { onEditStore(store.id) }
                        .testTag(StoreListScreenTags.ITEM_PREFIX + store.id)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(store.name, style = MaterialTheme.typography.titleMedium)
                        Text("${store.address.city} • ${store.timezone} • ${store.currency}")
                    }
                }
            }
        }
    }
}
