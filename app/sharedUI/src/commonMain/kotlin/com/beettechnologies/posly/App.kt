package com.beettechnologies.posly

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.beettechnologies.posly.auth.AuthNavHost

/**
 * Assumes Koin has already been started by the platform entry point
 * (MainActivity.onCreate / desktopApp's main()) before this is composed.
 */
@Composable
fun App() {
    MaterialTheme {
        AuthNavHost()
    }
}