package com.beettechnologies.posly.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.koin.compose.koinInject

private const val ROUTE_LOGIN = "login"
private const val ROUTE_MFA = "mfa"
private const val ROUTE_DASHBOARD = "dashboard"

/**
 * Single navigation surface for the auth flow, driven reactively by
 * AuthRepository.authState rather than screens calling navigate() themselves.
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
        composable(ROUTE_DASHBOARD) { DashboardScreen() }
    }
}
