package com.beettechnologies.posly.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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

object FeatureFlagFormScreenTags {
    const val KEY_FIELD = "feature_flag_form_key_field"
    const val DESCRIPTION_FIELD = "feature_flag_form_description_field"
    const val CREATE_BUTTON = "feature_flag_form_create_button"
    const val DONE_BUTTON = "feature_flag_form_done_button"
    const val ERROR_TEXT = "feature_flag_form_error_text"
}

@Composable
fun FeatureFlagFormScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: FeatureFlagFormViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
            Text("New Feature Flag", style = MaterialTheme.typography.headlineSmall)
        }

        OutlinedTextField(
            value = uiState.key,
            onValueChange = viewModel::onKeyChange,
            label = { Text("Key (e.g. new-checkout-flow)") },
            enabled = !uiState.isSaving && !uiState.created,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(FeatureFlagFormScreenTags.KEY_FIELD)
        )
        OutlinedTextField(
            value = uiState.description,
            onValueChange = viewModel::onDescriptionChange,
            label = { Text("Description") },
            enabled = !uiState.isSaving && !uiState.created,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(FeatureFlagFormScreenTags.DESCRIPTION_FIELD)
        )

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp).testTag(FeatureFlagFormScreenTags.ERROR_TEXT)
            )
        }

        if (uiState.isSaving) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        if (uiState.created) {
            Text("Flag created - it starts disabled with a 0% rollout.", modifier = Modifier.padding(top = 16.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(FeatureFlagFormScreenTags.DONE_BUTTON)) {
                Text("Done")
            }
        } else {
            Button(
                onClick = viewModel::submit,
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(FeatureFlagFormScreenTags.CREATE_BUTTON)
            ) {
                Text("Create Flag")
            }
        }
    }
}
