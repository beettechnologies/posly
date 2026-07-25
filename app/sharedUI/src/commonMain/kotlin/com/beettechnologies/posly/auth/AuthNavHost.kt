package com.beettechnologies.posly.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import com.beettechnologies.posly.admin.StoreFormScreen
import com.beettechnologies.posly.admin.StoreListScreen
import com.beettechnologies.posly.admin.TaxProfileFormScreen
import com.beettechnologies.posly.admin.TaxProfileListScreen
import org.koin.compose.koinInject

private const val ROUTE_LOGIN = "login"
private const val ROUTE_MFA = "mfa"
private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_STORES = "stores"
private const val ROUTE_STORE_FORM = "storeForm"
private const val ROUTE_STORE_FORM_EDIT = "storeForm/{id}"
private const val ROUTE_TAX_PROFILES = "taxProfiles"
private const val ROUTE_TAX_PROFILE_FORM = "taxProfileForm"
private const val ROUTE_TAX_PROFILE_FORM_EDIT = "taxProfileForm/{id}"

/**
 * Single navigation surface for the auth flow, driven reactively by
 * AuthRepository.authState rather than screens calling navigate() themselves.
 * Admin (stores/tax-profiles) routes are regular user-navigated destinations
 * reached from the dashboard.
 *
 * Note: this does not attempt to clear the back stack precisely on
 * login/logout transitions (kept simple pending a real navigation design
 * pass) - functionally correct for the login/MFA/dashboard flow, but a user
 * could technically navigate back to a screen for a state they've left.
 */
@Composable
fun AuthNavHost(authRepository: AuthRepository = koinInject()) {
    val navController = rememberNavController()
    val authState by authRepository.authState.collectAsStateWithLifecycle()

    LaunchedEffect(authState) {
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

    NavHost(navController = navController, startDestination = ROUTE_LOGIN) {
        composable(ROUTE_LOGIN) { LoginScreen() }
        composable(ROUTE_MFA) { MfaScreen() }
        composable(ROUTE_DASHBOARD) {
            DashboardScreen(
                onManageStores = { navController.navigate(ROUTE_STORES) },
                onManageTaxProfiles = { navController.navigate(ROUTE_TAX_PROFILES) }
            )
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
