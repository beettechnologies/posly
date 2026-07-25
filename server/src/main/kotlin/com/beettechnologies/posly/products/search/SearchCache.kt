package com.beettechnologies.posly.products.search

import java.util.concurrent.ConcurrentHashMap

/**
 * Small read-through TTL cache for repeated identical search queries.
 * Not an LRU: entries are just dropped once expired, which is sufficient
 * for a hot-query cache of this size.
 */
class SearchCache(
    private val ttlMillis: Long = 5_000,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val result: ProductSearchResult, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun getOrCompute(key: String, compute: () -> ProductSearchResult): ProductSearchResult {
        val now = nowProvider()
        val cached = entries[key]
        if (cached != null && cached.expiresAt > now) {
            return cached.result
        }
        val result = compute()
        entries[key] = Entry(result, now + ttlMillis)
        return result
    }

    fun invalidateAll() {
        entries.clear()
    }
}
