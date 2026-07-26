package com.beettechnologies.posly.admin

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import org.koin.compose.viewmodel.koinViewModel

object UserFormScreenTags {
    const val USERNAME_FIELD = "user_form_username_field"
    const val EMAIL_FIELD = "user_form_email_field"
    const val ROLE_CHECKBOX_PREFIX = "user_form_role_checkbox_"
    const val STORE_CHECKBOX_PREFIX = "user_form_store_checkbox_"
    const val INVITE_BUTTON = "user_form_invite_button"
    const val SAVE_ROLES_BUTTON = "user_form_save_roles_button"
    const val SAVE_STORE_ACCESS_BUTTON = "user_form_save_store_access_button"
    const val TOGGLE_STATUS_BUTTON = "user_form_toggle_status_button"
    const val ERROR_TEXT = "user_form_error_text"
    const val INFO_TEXT = "user_form_info_text"
    const val INVITE_TOKEN_TEXT = "user_form_invite_token_text"
}

@Composable
fun UserFormScreen(
    userId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: UserFormViewModel = koinViewModel()
) {
    LaunchedEffect(userId) { viewModel.initialize(userId) }
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
            Text(
                if (uiState.isEditing) "Edit User" else "Invite User",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        if (uiState.isEditing) {
            Text(uiState.username, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text(uiState.email.ifBlank { "No email on file" }, style = MaterialTheme.typography.bodyMedium)
            Text("Status: ${uiState.status.orEmpty()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        } else {
            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username") },
                enabled = !uiState.isSaving && !uiState.invited,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(UserFormScreenTags.USERNAME_FIELD)
            )
            OutlinedTextField(
                value = uiState.email,
                onValueChange = viewModel::onEmailChange,
                label = { Text("Email") },
                enabled = !uiState.isSaving && !uiState.invited,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(UserFormScreenTags.EMAIL_FIELD)
            )
        }

        Text("Roles", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
        ASSIGNABLE_ROLES.forEach { role ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = role in uiState.selectedRoles,
                    onCheckedChange = { viewModel.toggleRole(role) },
                    enabled = !uiState.isSaving && !uiState.invited,
                    modifier = Modifier.testTag(UserFormScreenTags.ROLE_CHECKBOX_PREFIX + role)
                )
                Text(role)
            }
        }
        if (uiState.isEditing) {
            Button(
                onClick = viewModel::saveRoles,
                enabled = !uiState.isSaving,
                modifier = Modifier.padding(top = 8.dp).testTag(UserFormScreenTags.SAVE_ROLES_BUTTON)
            ) {
                Text("Save Roles")
            }
        }

        Text("Store access", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
        uiState.stores.forEach { store ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = store.id in uiState.selectedStoreIds,
                    onCheckedChange = { viewModel.toggleStore(store.id) },
                    enabled = !uiState.isSaving && !uiState.invited,
                    modifier = Modifier.testTag(UserFormScreenTags.STORE_CHECKBOX_PREFIX + store.id)
                )
                Text(store.name)
            }
        }
        if (uiState.isEditing) {
            Button(
                onClick = viewModel::saveStoreAccess,
                enabled = !uiState.isSaving,
                modifier = Modifier.padding(top = 8.dp).testTag(UserFormScreenTags.SAVE_STORE_ACCESS_BUTTON)
            ) {
                Text("Save Store Access")
            }

            OutlinedButton(
                onClick = viewModel::toggleStatus,
                enabled = !uiState.isSaving,
                modifier = Modifier.padding(top = 20.dp).testTag(UserFormScreenTags.TOGGLE_STATUS_BUTTON)
            ) {
                Text(if (uiState.status == "DISABLED") "Re-enable User" else "Disable User")
            }
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(UserFormScreenTags.ERROR_TEXT)
            )
        }
        if (uiState.infoMessage != null) {
            Text(
                text = uiState.infoMessage.orEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(UserFormScreenTags.INFO_TEXT)
            )
        }

        if (!uiState.isEditing) {
            if (uiState.invited) {
                Text(
                    text = "Invite token (no real mail server - use this to accept the invite): ${uiState.inviteToken}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(UserFormScreenTags.INVITE_TOKEN_TEXT)
                )
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text("Done")
                }
            } else {
                Button(
                    onClick = viewModel::submitInvite,
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag(UserFormScreenTags.INVITE_BUTTON)
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Text("Send Invite")
                    }
                }
            }
        }
    }
}
