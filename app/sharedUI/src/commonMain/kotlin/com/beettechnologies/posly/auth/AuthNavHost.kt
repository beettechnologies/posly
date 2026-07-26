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
import com.beettechnologies.posly.admin.FeatureFlagFormScreen
import com.beettechnologies.posly.admin.FeatureFlagListScreen
import com.beettechnologies.posly.admin.FinanceReportsScreen
import com.beettechnologies.posly.admin.ImportWizardScreen
import com.beettechnologies.posly.admin.SsoConfigScreen
import com.beettechnologies.posly.admin.StoreFormScreen
import com.beettechnologies.posly.admin.StoreListScreen
import com.beettechnologies.posly.admin.TaxProfileFormScreen
import com.beettechnologies.posly.admin.TaxProfileListScreen
import com.beettechnologies.posly.admin.UserFormScreen
import com.beettechnologies.posly.admin.UserListScreen
import com.beettechnologies.posly.devices.DeviceCredentialsStore
import com.beettechnologies.posly.devices.DeviceListScreen
import com.beettechnologies.posly.devices.DevicePairingAdminScreen
import com.beettechnologies.posly.devices.PairingScreen
import com.beettechnologies.posly.pos.ManagerDashboardScreen
import com.beettechnologies.posly.pos.RefundScreen
import com.beettechnologies.posly.pos.SaleScreen
import com.beettechnologies.posly.pos.ShiftScreen
import com.beettechnologies.posly.pos.TransactionListScreen
import org.koin.compose.koinInject

private const val ROUTE_PAIRING = "pairing"
private const val ROUTE_LOGIN = "login"
private const val ROUTE_ACCEPT_INVITE = "acceptInvite"
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
private const val ROUTE_SALE = "sale"
private const val ROUTE_REFUNDS = "refunds"
private const val ROUTE_SHIFT = "shift"
private const val ROUTE_USERS = "users"
private const val ROUTE_USER_FORM = "userForm"
private const val ROUTE_USER_FORM_EDIT = "userForm/{id}"
private const val ROUTE_SSO_CONFIG = "ssoConfig"
private const val ROUTE_IMPORT_PRODUCTS = "importProducts"
private const val ROUTE_MANAGER_DASHBOARD = "managerDashboard"
private const val ROUTE_FINANCE_REPORTS = "financeReports"
private const val ROUTE_TRANSACTION_LIST = "transactionList"
private const val ROUTE_FEATURE_FLAGS = "featureFlags"
private const val ROUTE_FEATURE_FLAG_FORM = "featureFlagForm"

private data class TransactionListArgs(
    val storeId: String,
    val from: String,
    val to: String,
    val productId: String?,
    val productName: String?
)

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
    var pendingTransactionListArgs by remember { mutableStateOf<TransactionListArgs?>(null) }

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
        composable(ROUTE_LOGIN) {
            LoginScreen(
                onPairDevice = { navController.navigate(ROUTE_PAIRING) },
                onAcceptInvite = { navController.navigate(ROUTE_ACCEPT_INVITE) }
            )
        }
        composable(ROUTE_ACCEPT_INVITE) {
            AcceptInviteScreen(
                onAccepted = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_MFA) { MfaScreen() }
        composable(ROUTE_DASHBOARD) {
            DashboardScreen(
                onNewSale = { navController.navigate(ROUTE_SALE) },
                onManageStores = { navController.navigate(ROUTE_STORES) },
                onManageTaxProfiles = { navController.navigate(ROUTE_TAX_PROFILES) },
                onPairDevice = { navController.navigate(ROUTE_DEVICE_PAIRING_ADMIN) },
                onManageDevices = { navController.navigate(ROUTE_DEVICE_LIST) },
                onManageRefunds = { navController.navigate(ROUTE_REFUNDS) },
                onManageShift = { navController.navigate(ROUTE_SHIFT) },
                onManageUsers = { navController.navigate(ROUTE_USERS) },
                onImportProducts = { navController.navigate(ROUTE_IMPORT_PRODUCTS) },
                onManageDashboard = { navController.navigate(ROUTE_MANAGER_DASHBOARD) },
                onFinanceReports = { navController.navigate(ROUTE_FINANCE_REPORTS) },
                onManageFeatureFlags = { navController.navigate(ROUTE_FEATURE_FLAGS) }
            )
        }
        composable(ROUTE_SALE) {
            SaleScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_DEVICE_PAIRING_ADMIN) {
            DevicePairingAdminScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_DEVICE_LIST) {
            DeviceListScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_REFUNDS) {
            RefundScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_SHIFT) {
            ShiftScreen(onBack = { navController.popBackStack() })
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

        composable(ROUTE_USERS) {
            UserListScreen(
                onInviteUser = { navController.navigate(ROUTE_USER_FORM) },
                onEditUser = { id -> navController.navigate("userForm/$id") },
                onConfigureSso = { navController.navigate(ROUTE_SSO_CONFIG) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_USER_FORM) {
            UserFormScreen(
                userId = null,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_USER_FORM_EDIT) { backStackEntry ->
            UserFormScreen(
                userId = backStackEntry.arguments?.read { getStringOrNull("id") },
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_SSO_CONFIG) {
            SsoConfigScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_IMPORT_PRODUCTS) {
            ImportWizardScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_MANAGER_DASHBOARD) {
            ManagerDashboardScreen(
                onBack = { navController.popBackStack() },
                onDrillDown = { storeId, from, to, productId, productName ->
                    pendingTransactionListArgs = TransactionListArgs(storeId, from, to, productId, productName)
                    navController.navigate(ROUTE_TRANSACTION_LIST)
                }
            )
        }
        composable(ROUTE_FINANCE_REPORTS) {
            FinanceReportsScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_FEATURE_FLAGS) {
            FeatureFlagListScreen(
                onCreateFlag = { navController.navigate(ROUTE_FEATURE_FLAG_FORM) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_FEATURE_FLAG_FORM) {
            FeatureFlagFormScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(ROUTE_TRANSACTION_LIST) {
            val args = pendingTransactionListArgs
            if (args != null) {
                TransactionListScreen(
                    storeId = args.storeId,
                    from = args.from,
                    to = args.to,
                    productId = args.productId,
                    productName = args.productName,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
