package com.beettechnologies.posly.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

object DevicePairingAdminScreenTags {
    const val STORE_BUTTON = "device_pairing_store_button"
    const val TERMINAL_TYPE_FIELD = "device_pairing_terminal_type_field"
    const val GENERATE_BUTTON = "device_pairing_generate_button"
    const val ERROR_TEXT = "device_pairing_error_text"
    const val CODE_TEXT = "device_pairing_code_text"
}

@Composable
fun DevicePairingAdminScreen(
    onBack: () -> Unit,
    viewModel: DevicePairingAdminViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var storeMenuExpanded by remember { mutableStateOf(false) }
    val selectedStore = uiState.stores.find { it.id == uiState.selectedStoreId }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Pair a Device", style = MaterialTheme.typography.headlineSmall)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Store", style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(
                    onClick = { storeMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth().testTag(DevicePairingAdminScreenTags.STORE_BUTTON)
                ) {
                    Text(selectedStore?.name ?: "Select a store")
                }
                DropdownMenu(
                    expanded = storeMenuExpanded,
                    onDismissRequest = { storeMenuExpanded = false }
                ) {
                    uiState.stores.forEach { store ->
                        DropdownMenuItem(
                            text = { Text(store.name) },
                            onClick = {
                                viewModel.onStoreSelected(store.id)
                                storeMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = uiState.terminalType,
            onValueChange = viewModel::onTerminalTypeChange,
            label = { Text("Terminal type (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(DevicePairingAdminScreenTags.TERMINAL_TYPE_FIELD)
        )

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(DevicePairingAdminScreenTags.ERROR_TEXT)
            )
        }

        Button(
            onClick = viewModel::generateCode,
            enabled = !uiState.isGenerating && uiState.selectedStoreId != null,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(DevicePairingAdminScreenTags.GENERATE_BUTTON)
        ) {
            if (uiState.isGenerating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Generate pairing code")
            }
        }

        val pairCode = uiState.pairCode
        if (pairCode != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = pairCode.code,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag(DevicePairingAdminScreenTags.CODE_TEXT)
                )
                Text("Expires at ${pairCode.expiresAt}", modifier = Modifier.padding(top = 4.dp))

                val qrBitmap = remember(pairCode.code) { generateQrCodeImageBitmap(pairCode.code) }
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "Pairing code QR",
                        modifier = Modifier.size(220.dp).padding(top = 16.dp)
                    )
                }
            }
        }
    }
}
