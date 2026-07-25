package com.beettechnologies.posly.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import com.beettechnologies.posly.admin.StoreFormScreen
import com.beettechnologies.posly.admin.StoreListScreen
import com.beettechnologies.posly.admin.TaxProfileFormScreen
import com.beettechnologies.posly.admin.TaxProfileListScreen
import com.beettechnologies.posly.devices.DeviceCredentialsStore
import com.beettechnologies.posly.devices.DeviceListScreen
import com.beettechnologies.posly.devices.DevicePairingAdminScreen
import com.beettechnologies.posly.devices.PairingScreen
import org.koin.compose.koinInject

private const val ROUTE_PAIRING = "pairing"
private const val ROUTE_LOGIN = "login"
private const val ROUTE_MFA = "mfa"
private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_STORES = "stores"
private const val ROUTE_STORE_FORM = "storeForm"
private const val ROUTE_STORE_FORM_EDIT = "storeForm/{id}"
private const val ROUTE_TAX_PROFILES = "taxProfiles"
private const val ROUTE_TAX_PROFILE_FORM = "taxProfileForm"
private const val ROUTE_TAX_PROFILE_FORM_EDIT = "taxProfileForm/{id}"
private const val ROUTE_DEVICE_PAIRING_ADMIN = "devicePairingAdmin"
private const val ROUTE_DEVICE_LIST = "deviceList"

/**
 * Single navigation surface for the auth flow, driven reactively by
 * AuthRepository.authState rather than screens calling navigate() themselves.
 * Admin (stores/tax-profiles) routes are regular user-navigated destinations
 * reached from the dashboard.
 *
 * App startup is additionally gated on device pairing: an unpaired device
 * always lands on the pairing screen first, regardless of authState. Once
 * paired, "Pair this device" remains reachable from the login screen (e.g.
 * to re-pair after credentials are revoked) without needing to clear app data.
 *
 * Note: this does not attempt to clear the back stack precisely on
 * login/logout transitions (kept simple pending a real navigation design
 * pass) - functionally correct for the login/MFA/dashboard flow, but a user
 * could technically navigate back to a screen for a state they've left.
 */
@Composable
fun AuthNavHost(
    authRepository: AuthRepository = koinInject(),
    credentialsStore: DeviceCredentialsStore = koinInject()
) {
    val navController = rememberNavController()
    val authState by authRepository.authState.collectAsStateWithLifecycle()
    var pairingChecked by remember { mutableStateOf(false) }
    var isPaired by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isPaired = credentialsStore.isPaired()
        pairingChecked = true
    }

    if (!pairingChecked) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LaunchedEffect(authState, isPaired) {
        if (!isPaired) return@LaunchedEffect
        val target = when (authState) {
            AuthState.LoggedOut -> ROUTE_LOGIN
            is AuthState.MfaRequired -> ROUTE_MFA
            AuthState.LoggedIn -> ROUTE_DASHBOARD
        }
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = if (isPaired) ROUTE_LOGIN else ROUTE_PAIRING) {
        composable(ROUTE_PAIRING) {
            PairingScreen(
                onPaired = {
                    isPaired = true
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(ROUTE_LOGIN) {
                            popUpTo(ROUTE_PAIRING) { inclusive = true }
                        }
                    }
                }
            )
        }
        composable(ROUTE_LOGIN) { LoginScreen(onPairDevice = { navController.navigate(ROUTE_PAIRING) }) }
        composable(ROUTE_MFA) { MfaScreen() }
        composable(ROUTE_DASHBOARD) {
            DashboardScreen(
                onManageStores = { navController.navigate(ROUTE_STORES) },
                onManageTaxProfiles = { navController.navigate(ROUTE_TAX_PROFILES) },
                onPairDevice = { navController.navigate(ROUTE_DEVICE_PAIRING_ADMIN) },
                onManageDevices = { navController.navigate(ROUTE_DEVICE_LIST) }
            )
        }
        composable(ROUTE_DEVICE_PAIRING_ADMIN) {
            DevicePairingAdminScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_DEVICE_LIST) {
            DeviceListScreen(onBack = { navController.popBackStack() })
        }

        composable(ROUTE_STORES) {
            StoreListScreen(
                onAddStore = { navController.navigate(ROUTE_STORE_FORM) },
                onEditStore = { id -> navController.navigate("storeForm/$id") },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_STORE_FORM) {
            StoreFormScreen(
                storeId = null,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_STORE_FORM_EDIT) { backStackEntry ->
            StoreFormScreen(
                storeId = backStackEntry.arguments?.read { getStringOrNull("id") },
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(ROUTE_TAX_PROFILES) {
            TaxProfileListScreen(
                onAddProfile = { navController.navigate(ROUTE_TAX_PROFILE_FORM) },
                onEditProfile = { id -> navController.navigate("taxProfileForm/$id") },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_TAX_PROFILE_FORM) {
            TaxProfileFormScreen(
                profileId = null,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_TAX_PROFILE_FORM_EDIT) { backStackEntry ->
            TaxProfileFormScreen(
                profileId = backStackEntry.arguments?.read { getStringOrNull("id") },
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
