package com.beettechnologies.posly.admin

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

object FeatureFlagListScreenTags {
    const val CREATE_BUTTON = "feature_flag_list_create_button"
    const val ERROR_TEXT = "feature_flag_list_error_text"
    const val ITEM_PREFIX = "feature_flag_list_item_"
    const val SWITCH_PREFIX = "feature_flag_list_switch_"
    const val ROLLOUT_FIELD_PREFIX = "feature_flag_list_rollout_field_"
    const val SAVE_BUTTON_PREFIX = "feature_flag_list_save_button_"
}

@Composable
fun FeatureFlagListScreen(
    onCreateFlag: () -> Unit,
    onBack: () -> Unit,
    viewModel: FeatureFlagListViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Feature Flags", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onCreateFlag, modifier = Modifier.testTag(FeatureFlagListScreenTags.CREATE_BUTTON)) {
                Text("+ New Flag")
            }
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp).testTag(FeatureFlagListScreenTags.ERROR_TEXT)
            )
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            items(uiState.flags) { flag ->
                val isSaving = flag.key in uiState.savingKeys
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag(FeatureFlagListScreenTags.ITEM_PREFIX + flag.key)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(flag.key, style = MaterialTheme.typography.titleMedium)
                        Text(flag.description)

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Enabled")
                            Switch(
                                checked = flag.enabled,
                                onCheckedChange = { viewModel.toggleEnabled(flag.key) },
                                enabled = !isSaving,
                                modifier = Modifier.testTag(FeatureFlagListScreenTags.SWITCH_PREFIX + flag.key)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = uiState.rolloutInputs[flag.key] ?: flag.rolloutPercentage.toString(),
                                onValueChange = { viewModel.onRolloutInputChange(flag.key, it) },
                                label = { Text("Rollout %") },
                                enabled = !isSaving,
                                modifier = Modifier.testTag(FeatureFlagListScreenTags.ROLLOUT_FIELD_PREFIX + flag.key)
                            )
                            Button(
                                onClick = { viewModel.saveRolloutPercentage(flag.key) },
                                enabled = !isSaving,
                                modifier = Modifier.padding(start = 8.dp).testTag(FeatureFlagListScreenTags.SAVE_BUTTON_PREFIX + flag.key)
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }
}
