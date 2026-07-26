package com.beettechnologies.posly.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

object AcceptInviteScreenTags {
    const val TOKEN_FIELD = "accept_invite_token_field"
    const val PASSWORD_FIELD = "accept_invite_password_field"
    const val SUBMIT_BUTTON = "accept_invite_submit_button"
    const val ERROR_TEXT = "accept_invite_error_text"
    const val SUCCESS_TEXT = "accept_invite_success_text"
}

@Composable
fun AcceptInviteScreen(
    onAccepted: () -> Unit,
    onBack: () -> Unit,
    viewModel: AcceptInviteViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.accepted) {
        if (uiState.accepted) onAccepted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Accept your invite",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = uiState.token,
            onValueChange = viewModel::onTokenChange,
            label = { Text("Invite token") },
            enabled = !uiState.isSubmitting,
            modifier = Modifier.fillMaxWidth().testTag(AcceptInviteScreenTags.TOKEN_FIELD)
        )

        OutlinedTextField(
            value = uiState.newPassword,
            onValueChange = viewModel::onNewPasswordChange,
            label = { Text("New password") },
            singleLine = true,
            enabled = !uiState.isSubmitting,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(AcceptInviteScreenTags.PASSWORD_FIELD)
        )

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(AcceptInviteScreenTags.ERROR_TEXT)
            )
        }

        Button(
            onClick = viewModel::submit,
            enabled = !uiState.isSubmitting,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).testTag(AcceptInviteScreenTags.SUBMIT_BUTTON)
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Set password & continue")
            }
        }

        TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
            Text("Back to login")
        }
    }
}
