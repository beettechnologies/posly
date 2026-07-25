package com.beettechnologies.posly.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

object DeviceListScreenTags {
    const val STORE_BUTTON = "device_list_store_button"
    const val ERROR_TEXT = "device_list_error_text"
    const val ITEM_PREFIX = "device_list_item_"
    const val DEPROVISION_BUTTON_PREFIX = "device_list_deprovision_button_"
    const val CONFIRM_DEPROVISION_BUTTON = "device_list_confirm_deprovision_button"
}

@Composable
fun DeviceListScreen(
    onBack: () -> Unit,
    viewModel: DeviceListViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var storeMenuExpanded by remember { mutableStateOf(false) }
    var pendingDeprovision by remember { mutableStateOf<DeviceResponse?>(null) }
    val selectedStore = uiState.stores.find { it.id == uiState.selectedStoreId }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Devices", style = MaterialTheme.typography.headlineSmall)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Store", style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(
                    onClick = { storeMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth().testTag(DeviceListScreenTags.STORE_BUTTON)
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

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(DeviceListScreenTags.ERROR_TEXT)
            )
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            items(uiState.devices) { device ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(DeviceListScreenTags.ITEM_PREFIX + device.id)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(device.terminalType ?: "Unknown terminal type")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = device.healthStatus.replace('_', ' '),
                                color = when (device.healthStatus) {
                                    "ONLINE" -> Color(0xFF2E7D32)
                                    "OFFLINE" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (device.lastSeenAt != null) {
                                Text(" • last seen ${device.lastSeenAt}")
                            } else {
                                Text(" • never seen")
                            }
                        }
                        if (device.status == "DEPROVISIONED") {
                            Text("Deprovisioned", color = MaterialTheme.colorScheme.error)
                        } else {
                            Button(
                                onClick = { pendingDeprovision = device },
                                enabled = uiState.deprovisioningDeviceId != device.id,
                                modifier = Modifier.padding(top = 8.dp)
                                    .testTag(DeviceListScreenTags.DEPROVISION_BUTTON_PREFIX + device.id)
                            ) {
                                Text("Deprovision")
                            }
                        }
                    }
                }
            }
        }
    }

    val deviceToDeprovision = pendingDeprovision
    if (deviceToDeprovision != null) {
        AlertDialog(
            onDismissRequest = { pendingDeprovision = null },
            title = { Text("Deprovision device?") },
            text = { Text("\"${deviceToDeprovision.name}\" will lose access immediately. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deprovision(deviceToDeprovision.id)
                        pendingDeprovision = null
                    },
                    modifier = Modifier.testTag(DeviceListScreenTags.CONFIRM_DEPROVISION_BUTTON)
                ) {
                    Text("Deprovision")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeprovision = null }) { Text("Cancel") }
            }
        )
    }
}
