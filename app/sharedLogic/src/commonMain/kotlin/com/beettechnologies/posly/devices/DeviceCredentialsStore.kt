package com.beettechnologies.posly.devices

import com.russhwolf.settings.Settings

data class DeviceCredentials(
    val deviceId: String,
    val storeId: String,
    val clientId: String,
    val clientSecret: String
)

/**
 * Persists this terminal's pairing credentials across restarts, backed by the
 * same platform [Settings] instance used for auth tokens (encrypted on
 * Android, plain Preferences on Desktop). Presence of credentials is what
 * gates app startup between the pairing flow and the login flow.
 */
interface DeviceCredentialsStore {
    suspend fun isPaired(): Boolean
    suspend fun getCredentials(): DeviceCredentials?
    suspend fun saveCredentials(credentials: DeviceCredentials)
    suspend fun clear()
}

class SettingsDeviceCredentialsStore(private val settings: Settings) : DeviceCredentialsStore {

    override suspend fun isPaired(): Boolean = settings.getStringOrNull(KEY_CLIENT_ID) != null

    override suspend fun getCredentials(): DeviceCredentials? {
        val deviceId = settings.getStringOrNull(KEY_DEVICE_ID) ?: return null
        val storeId = settings.getStringOrNull(KEY_STORE_ID) ?: return null
        val clientId = settings.getStringOrNull(KEY_CLIENT_ID) ?: return null
        val clientSecret = settings.getStringOrNull(KEY_CLIENT_SECRET) ?: return null
        return DeviceCredentials(deviceId, storeId, clientId, clientSecret)
    }

    override suspend fun saveCredentials(credentials: DeviceCredentials) {
        settings.putString(KEY_DEVICE_ID, credentials.deviceId)
        settings.putString(KEY_STORE_ID, credentials.storeId)
        settings.putString(KEY_CLIENT_ID, credentials.clientId)
        settings.putString(KEY_CLIENT_SECRET, credentials.clientSecret)
    }

    override suspend fun clear() {
        settings.remove(KEY_DEVICE_ID)
        settings.remove(KEY_STORE_ID)
        settings.remove(KEY_CLIENT_ID)
        settings.remove(KEY_CLIENT_SECRET)
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_STORE_ID = "device_store_id"
        const val KEY_CLIENT_ID = "device_client_id"
        const val KEY_CLIENT_SECRET = "device_client_secret"
    }
}
