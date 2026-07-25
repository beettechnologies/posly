package com.beettechnologies.posly.cart

import com.russhwolf.settings.Settings

/**
 * Persists the id of this device's currently active cart so a sale in progress can be
 * restored after the app restarts or the device reconnects, rather than starting a fresh
 * cart every time the Sale screen is opened.
 */
interface CartSessionStore {
    suspend fun getCurrentCartId(): String?
    suspend fun setCurrentCartId(id: String?)
}

class SettingsCartSessionStore(private val settings: Settings) : CartSessionStore {

    override suspend fun getCurrentCartId(): String? = settings.getStringOrNull(KEY_CURRENT_CART_ID)

    override suspend fun setCurrentCartId(id: String?) {
        if (id != null) settings.putString(KEY_CURRENT_CART_ID, id) else settings.remove(KEY_CURRENT_CART_ID)
    }

    private companion object {
        const val KEY_CURRENT_CART_ID = "current_cart_id"
    }
}
