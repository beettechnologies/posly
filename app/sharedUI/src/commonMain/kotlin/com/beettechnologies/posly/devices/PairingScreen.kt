package com.beettechnologies.posly.devices

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

object PairingScreenTags {
    const val CODE_FIELD = "pairing_code_field"
    const val SUBMIT_BUTTON = "pairing_submit_button"
    const val ERROR_TEXT = "pairing_error_text"
    const val RETRY_BUTTON = "pairing_retry_button"
    const val CONTINUE_BUTTON = "pairing_continue_button"
    const val SUCCESS_TEXT = "pairing_success_text"
}

@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    viewModel: PairingViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val credentials = uiState.credentials

    if (credentials != null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Device paired",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp).testTag(PairingScreenTags.SUCCESS_TEXT)
            )
            Text("Device ID: ${credentials.deviceId}")
            Text("Client ID: ${credentials.clientId}")
            Text("Client secret: ${credentials.clientSecret}")
            Button(
                onClick = onPaired,
                modifier = Modifier.padding(top = 24.dp).testTag(PairingScreenTags.CONTINUE_BUTTON)
            ) {
                Text("Continue")
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Pair this device",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Scan the QR code shown by your store manager, or enter the pairing code manually.",
            modifier = Modifier.padding(bottom = 16.dp)
        )

        key(uiState.scanAttempt) {
            QrScanner(
                onCodeScanned = viewModel::onCodeScanned,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
        }

        OutlinedTextField(
            value = uiState.code,
            onValueChange = viewModel::onCodeChange,
            label = { Text("Pairing code") },
            singleLine = true,
            enabled = !uiState.isLoading,
            modifier = Modifier.fillMaxWidth().testTag(PairingScreenTags.CODE_FIELD)
        )

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(PairingScreenTags.ERROR_TEXT)
            )
            TextButton(
                onClick = viewModel::retry,
                modifier = Modifier.testTag(PairingScreenTags.RETRY_BUTTON)
            ) {
                Text("Try again")
            }
        }

        Button(
            onClick = viewModel::submit,
            enabled = !uiState.isLoading,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(PairingScreenTags.SUBMIT_BUTTON)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Pair device")
            }
        }
    }
}
