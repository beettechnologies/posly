package com.beettechnologies.posly.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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

object ApiKeyListScreenTags {
    const val CREATE_BUTTON = "api_key_list_create_button"
    const val ERROR_TEXT = "api_key_list_error_text"
    const val ITEM_PREFIX = "api_key_list_item_"
    const val STATUS_TEXT_PREFIX = "api_key_list_status_text_"
    const val REVOKE_BUTTON_PREFIX = "api_key_list_revoke_button_"
    const val ROTATE_BUTTON_PREFIX = "api_key_list_rotate_button_"
    const val USAGE_TOGGLE_PREFIX = "api_key_list_usage_toggle_"
    const val USAGE_ROW_PREFIX = "api_key_list_usage_row_"
    const val ROTATED_KEY_DIALOG = "api_key_list_rotated_key_dialog"
    const val ROTATED_KEY_TEXT = "api_key_list_rotated_key_text"
    const val ROTATED_KEY_DISMISS_BUTTON = "api_key_list_rotated_key_dismiss_button"
}

@Composable
fun ApiKeyListScreen(
    onCreateKey: () -> Unit,
    onBack: () -> Unit,
    viewModel: ApiKeyListViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("API Keys", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onCreateKey, modifier = Modifier.testTag(ApiKeyListScreenTags.CREATE_BUTTON)) {
                Text("+ New Key")
            }
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp).testTag(ApiKeyListScreenTags.ERROR_TEXT).statusMessage()
            )
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            items(uiState.keys) { key ->
                val isBusy = key.id in uiState.busyKeyIds
                val isActive = key.status == "ACTIVE"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag(ApiKeyListScreenTags.ITEM_PREFIX + key.id)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(key.name, style = MaterialTheme.typography.titleMedium)
                        Text("posly_${key.keyPrefix}...", style = MaterialTheme.typography.bodySmall)
                        Text("Scopes: ${key.scopes.joinToString()}")
                        Text(
                            "Status: ${key.status}" + if (!isActive) " (revoked ${key.revokedAt.orEmpty()})" else "",
                            modifier = Modifier.testTag(ApiKeyListScreenTags.STATUS_TEXT_PREFIX + key.id)
                        )
                        Text("Last used: ${key.lastUsedAt ?: "never"}", style = MaterialTheme.typography.bodySmall)

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isActive) {
                                Button(
                                    onClick = { viewModel.rotate(key.id) },
                                    enabled = !isBusy,
                                    modifier = Modifier.testTag(ApiKeyListScreenTags.ROTATE_BUTTON_PREFIX + key.id)
                                ) { Text("Rotate") }
                                TextButton(
                                    onClick = { viewModel.revoke(key.id) },
                                    enabled = !isBusy,
                                    modifier = Modifier.testTag(ApiKeyListScreenTags.REVOKE_BUTTON_PREFIX + key.id)
                                ) { Text("Revoke") }
                            }
                            TextButton(
                                onClick = { viewModel.toggleUsage(key.id) },
                                modifier = Modifier.testTag(ApiKeyListScreenTags.USAGE_TOGGLE_PREFIX + key.id)
                            ) {
                                Text(if (key.id in uiState.expandedUsageKeyIds) "Hide usage" else "View usage")
                            }
                        }

                        if (key.id in uiState.expandedUsageKeyIds) {
                            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                            val usage = uiState.usageByKeyId[key.id]
                            if (usage == null) {
                                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                            } else if (usage.isEmpty()) {
                                Text("No usage recorded yet.", modifier = Modifier.padding(top = 8.dp))
                            } else {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    usage.forEach { entry ->
                                        Text(
                                            "${entry.method} ${entry.path} -> ${entry.statusCode} (${entry.timestamp})",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.testTag(ApiKeyListScreenTags.USAGE_ROW_PREFIX + entry.id)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val rotated = uiState.justRotated
    if (rotated != null) {
        val (_, rawKey) = rotated
        AlertDialog(
            modifier = Modifier.testTag(ApiKeyListScreenTags.ROTATED_KEY_DIALOG),
            onDismissRequest = viewModel::dismissRotatedKeyDialog,
            title = { Text("New secret generated") },
            text = {
                Column {
                    Text("Copy this key now - it won't be shown again. The previous key stopped working immediately.")
                    Text(
                        rawKey,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp).testTag(ApiKeyListScreenTags.ROTATED_KEY_TEXT)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::dismissRotatedKeyDialog,
                    modifier = Modifier.testTag(ApiKeyListScreenTags.ROTATED_KEY_DISMISS_BUTTON)
                ) { Text("Done") }
            }
        )
    }
}
