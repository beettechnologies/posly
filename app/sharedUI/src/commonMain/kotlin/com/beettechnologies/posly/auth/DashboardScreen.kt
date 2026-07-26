package com.beettechnologies.posly.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Landing screen after login. The admin actions below are shown regardless of
 * role; the server enforces ADMIN-only access and the destination screens
 * surface a "you don't have permission" message on 403 rather than the
 * dashboard trying to duplicate that check client-side.
 */
@Composable
fun DashboardScreen(
    onNewSale: () -> Unit,
    onManageStores: () -> Unit,
    onManageTaxProfiles: () -> Unit,
    onPairDevice: () -> Unit,
    onManageDevices: () -> Unit,
    onManageRefunds: () -> Unit,
    onManageShift: () -> Unit,
    onManageUsers: () -> Unit,
    onImportProducts: () -> Unit,
    onManageDashboard: () -> Unit,
    authRepository: AuthRepository = koinInject()
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome, you're logged in",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Button(onClick = onNewSale, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("New Sale")
        }
        Button(onClick = onManageStores, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Manage Stores")
        }
        Button(onClick = onManageTaxProfiles, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Manage Tax Profiles")
        }
        Button(onClick = onPairDevice, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Pair a Device")
        }
        Button(onClick = onManageDevices, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Manage Devices")
        }
        Button(onClick = onManageRefunds, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Refunds & Returns")
        }
        Button(onClick = onManageShift, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Shift")
        }
        Button(onClick = onManageUsers, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Manage Users")
        }
        Button(onClick = onImportProducts, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Import Products")
        }
        Button(onClick = onManageDashboard, modifier = Modifier.padding(bottom = 24.dp)) {
            Text("Manager Dashboard")
        }
        Button(onClick = { scope.launch { authRepository.logout() } }) {
            Text("Log out")
        }
    }
}
