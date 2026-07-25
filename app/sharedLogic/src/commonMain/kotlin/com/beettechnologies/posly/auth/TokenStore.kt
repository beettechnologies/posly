package com.beettechnologies.posly.auth

import com.russhwolf.settings.Settings

/**
 * Persists auth tokens across app restarts. Backed by a platform [Settings]
 * instance: EncryptedSharedPreferences-backed on Android, Preferences-backed
 * (unencrypted) on Desktop.
 */
interface TokenStore {
    suspend fun saveTokens(accessToken: String, refreshToken: String)
    suspend fun getAccessToken(): String?
    suspend fun getRefreshToken(): String?
    suspend fun clear()
}

class SettingsTokenStore(private val settings: Settings) : TokenStore {

    override suspend fun saveTokens(accessToken: String, refreshToken: String) {
        settings.putString(KEY_ACCESS_TOKEN, accessToken)
        settings.putString(KEY_REFRESH_TOKEN, refreshToken)
    }

    override suspend fun getAccessToken(): String? = settings.getStringOrNull(KEY_ACCESS_TOKEN)

    override suspend fun getRefreshToken(): String? = settings.getStringOrNull(KEY_REFRESH_TOKEN)

    override suspend fun clear() {
        settings.remove(KEY_ACCESS_TOKEN)
        settings.remove(KEY_REFRESH_TOKEN)
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
