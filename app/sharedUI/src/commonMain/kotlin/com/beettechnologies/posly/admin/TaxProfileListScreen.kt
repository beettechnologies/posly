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

object TaxProfileListScreenTags {
    const val ADD_BUTTON = "tax_profile_list_add_button"
    const val ERROR_TEXT = "tax_profile_list_error_text"
    const val ITEM_PREFIX = "tax_profile_list_item_"
}

@Composable
fun TaxProfileListScreen(
    onAddProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TaxProfileListViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Tax Profiles", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onAddProfile, modifier = Modifier.testTag(TaxProfileListScreenTags.ADD_BUTTON)) {
                Text("+ Add")
            }
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp).testTag(TaxProfileListScreenTags.ERROR_TEXT)
            )
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            items(uiState.profiles) { profile ->
                val totalRate = profile.rates.sumOf { it.ratePercent }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable(onClickLabel = "Edit ${profile.name}", role = Role.Button) { onEditProfile(profile.id) }
                        .testTag(TaxProfileListScreenTags.ITEM_PREFIX + profile.id)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text("${profile.rates.size} rate(s) • ${totalRate}% total")
                    }
                }
            }
        }
    }
}
