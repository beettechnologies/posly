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

object UserListScreenTags {
    const val INVITE_BUTTON = "user_list_invite_button"
    const val SSO_CONFIG_BUTTON = "user_list_sso_config_button"
    const val ERROR_TEXT = "user_list_error_text"
    const val ITEM_PREFIX = "user_list_item_"
}

@Composable
fun UserListScreen(
    onInviteUser: () -> Unit,
    onEditUser: (String) -> Unit,
    onConfigureSso: () -> Unit,
    onBack: () -> Unit,
    viewModel: UserListViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Users", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onInviteUser, modifier = Modifier.testTag(UserListScreenTags.INVITE_BUTTON)) {
                Text("+ Invite")
            }
        }

        TextButton(
            onClick = onConfigureSso,
            modifier = Modifier.padding(top = 4.dp).testTag(UserListScreenTags.SSO_CONFIG_BUTTON)
        ) {
            Text("Configure SSO")
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp).testTag(UserListScreenTags.ERROR_TEXT)
            )
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            items(uiState.users) { user ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable(onClickLabel = "Edit ${user.username}", role = Role.Button) { onEditUser(user.id) }
                        .testTag(UserListScreenTags.ITEM_PREFIX + user.id)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(user.username, style = MaterialTheme.typography.titleMedium)
                        Text("${user.roles.joinToString()} • ${user.status}")
                    }
                }
            }
        }
    }
}
