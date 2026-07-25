package com.beettechnologies.posly

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.beettechnologies.posly.auth.authModule
import com.beettechnologies.posly.auth.platformAuthModule
import com.beettechnologies.posly.auth.sharedUiModule
import com.beettechnologies.posly.stores.storesModule
import org.koin.core.context.startKoin

fun main() {
    startKoin {
        modules(authModule, platformAuthModule(), sharedUiModule, storesModule)
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Posly",
        ) {
            App()
        }
    }
}