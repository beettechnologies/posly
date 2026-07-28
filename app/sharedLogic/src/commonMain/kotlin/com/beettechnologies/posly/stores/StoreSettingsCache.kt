package com.beettechnologies.posly.stores

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves a [StoreResponse] (currency/timezone/locale/logo) by [storeId], caching the result so
 * screens that only ever have a bare storeId (not a full [StoreResponse]) don't each make their
 * own network round-trip. [invalidate] should be called after an admin edits a store's settings so
 * this process picks up the change on its next fetch.
 *
 * Disclosed limitation: this cache is per-process - a different terminal/process won't see the
 * change until its own cache entry is invalidated or the app restarts. A cross-device broadcast or
 * TTL-based refresh would close that gap but is out of scope here.
 */
class StoreSettingsCache(private val storeApi: StoreApi) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, StoreResponse>()

    suspend fun getStore(storeId: String): StoreResponse? {
        mutex.withLock { cache[storeId] }?.let { return it }
        val result = storeApi.getStore(storeId)
        val store = (result as? StoreResult.Success)?.store ?: return null
        mutex.withLock { cache[storeId] = store }
        return store
    }

    fun invalidate(storeId: String) {
        cache.remove(storeId)
    }
}
