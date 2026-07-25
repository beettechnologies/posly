package com.beettechnologies.posly.auth

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.prefs.Preferences

actual val defaultBaseUrl: String = "http://localhost:8080"

actual fun platformAuthModule(): Module = module {
    single<Settings> {
        PreferencesSettings(Preferences.userRoot().node("com/beettechnologies/posly"))
    }
    single {
        val tokenStore: TokenStore = get()
        HttpClient(CIO) { configureAuthClient(tokenStore, defaultBaseUrl) }
    }
}
