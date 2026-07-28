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
import androidx.compose.material3.Checkbox
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
import com.beettechnologies.posly.accessibility.statusMessage
import org.koin.compose.viewmodel.koinViewModel

object ApiKeyFormScreenTags {
    const val NAME_FIELD = "api_key_form_name_field"
    const val SCOPE_CHECKBOX_PREFIX = "api_key_form_scope_checkbox_"
    const val CREATE_BUTTON = "api_key_form_create_button"
    const val DONE_BUTTON = "api_key_form_done_button"
    const val ERROR_TEXT = "api_key_form_error_text"
    const val CREATED_KEY_TEXT = "api_key_form_created_key_text"
}

@Composable
fun ApiKeyFormScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: ApiKeyFormViewModel = koinViewModel()
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
            Text("New API Key", style = MaterialTheme.typography.headlineSmall)
        }

        if (uiState.createdRawKey == null) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Name (e.g. Accounting integration)") },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(ApiKeyFormScreenTags.NAME_FIELD)
            )

            Text("Scopes", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
            AVAILABLE_API_KEY_SCOPES.forEach { scope ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Checkbox(
                        checked = scope in uiState.selectedScopes,
                        onCheckedChange = { viewModel.toggleScope(scope) },
                        enabled = !uiState.isSaving,
                        modifier = Modifier.testTag(ApiKeyFormScreenTags.SCOPE_CHECKBOX_PREFIX + scope)
                    )
                    Text(scope)
                }
            }

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp).testTag(ApiKeyFormScreenTags.ERROR_TEXT).statusMessage()
                )
            }

            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            }

            Button(
                onClick = viewModel::submit,
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(ApiKeyFormScreenTags.CREATE_BUTTON)
            ) {
                Text("Create Key")
            }
        } else {
            Text(
                "Key created. Copy the secret below now - it won't be shown again.",
                modifier = Modifier.padding(top = 16.dp).statusMessage()
            )
            Text(
                uiState.createdRawKey.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).testTag(ApiKeyFormScreenTags.CREATED_KEY_TEXT)
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(ApiKeyFormScreenTags.DONE_BUTTON)) {
                Text("Done")
            }
        }
    }
}
