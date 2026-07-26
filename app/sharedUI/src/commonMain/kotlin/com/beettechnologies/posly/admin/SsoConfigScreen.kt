package com.beettechnologies.posly.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

object SsoConfigScreenTags {
    const val PROVIDER_NAME_FIELD = "sso_config_provider_name_field"
    const val ENABLED_SWITCH = "sso_config_enabled_switch"
    const val ADD_MAPPING_BUTTON = "sso_config_add_mapping_button"
    const val MAPPING_GROUP_FIELD_PREFIX = "sso_config_mapping_group_field_"
    const val MAPPING_ROLE_BUTTON_PREFIX = "sso_config_mapping_role_button_"
    const val MAPPING_REMOVE_BUTTON_PREFIX = "sso_config_mapping_remove_button_"
    const val DEFAULT_ROLE_CHECKBOX_PREFIX = "sso_config_default_role_checkbox_"
    const val SAVE_BUTTON = "sso_config_save_button"
    const val ERROR_TEXT = "sso_config_error_text"
    const val INFO_TEXT = "sso_config_info_text"
}

@Composable
fun SsoConfigScreen(
    onBack: () -> Unit,
    viewModel: SsoConfigViewModel = koinViewModel()
) {
    LaunchedEffect(Unit) { viewModel.initialize() }
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
            Text("Configure SSO", style = MaterialTheme.typography.headlineSmall)
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        OutlinedTextField(
            value = uiState.providerName,
            onValueChange = viewModel::onProviderNameChange,
            label = { Text("Provider name (e.g. Okta)") },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(SsoConfigScreenTags.PROVIDER_NAME_FIELD)
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Text("Enabled")
            Switch(
                checked = uiState.enabled,
                onCheckedChange = { viewModel.toggleEnabled() },
                enabled = !uiState.isSaving,
                modifier = Modifier.padding(start = 8.dp).testTag(SsoConfigScreenTags.ENABLED_SWITCH)
            )
        }

        Text("Default roles (used when no group mapping matches)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
        ASSIGNABLE_ROLES.forEach { role ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = role in uiState.defaultRoles,
                    onCheckedChange = { viewModel.toggleDefaultRole(role) },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.testTag(SsoConfigScreenTags.DEFAULT_ROLE_CHECKBOX_PREFIX + role)
                )
                Text(role)
            }
        }

        Text("Group → role mappings", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
        uiState.roleMappings.forEachIndexed { index, mapping ->
            var roleMenuExpanded by remember(index) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = mapping.externalGroup,
                    onValueChange = { viewModel.updateMappingGroup(index, it) },
                    label = { Text("External group") },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.weight(1f).testTag(SsoConfigScreenTags.MAPPING_GROUP_FIELD_PREFIX + index)
                )
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    OutlinedButton(
                        onClick = { roleMenuExpanded = true },
                        enabled = !uiState.isSaving,
                        modifier = Modifier.testTag(SsoConfigScreenTags.MAPPING_ROLE_BUTTON_PREFIX + index)
                    ) {
                        Text(mapping.role)
                    }
                    DropdownMenu(expanded = roleMenuExpanded, onDismissRequest = { roleMenuExpanded = false }) {
                        ASSIGNABLE_ROLES.forEach { role ->
                            DropdownMenuItem(
                                text = { Text(role) },
                                onClick = {
                                    viewModel.updateMappingRole(index, role)
                                    roleMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { viewModel.removeMapping(index) },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.testTag(SsoConfigScreenTags.MAPPING_REMOVE_BUTTON_PREFIX + index)
                ) {
                    Text("Remove")
                }
            }
        }
        TextButton(
            onClick = viewModel::addMapping,
            enabled = !uiState.isSaving,
            modifier = Modifier.padding(top = 8.dp).testTag(SsoConfigScreenTags.ADD_MAPPING_BUTTON)
        ) {
            Text("+ Add mapping")
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(SsoConfigScreenTags.ERROR_TEXT)
            )
        }
        if (uiState.infoMessage != null) {
            Text(
                text = uiState.infoMessage.orEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(SsoConfigScreenTags.INFO_TEXT)
            )
        }

        Button(
            onClick = viewModel::save,
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag(SsoConfigScreenTags.SAVE_BUTTON)
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Save")
            }
        }
    }
}
