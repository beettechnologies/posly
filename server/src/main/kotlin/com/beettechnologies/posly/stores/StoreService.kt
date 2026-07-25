package com.beettechnologies.posly.stores

import java.util.Currency
import java.util.concurrent.ConcurrentHashMap

sealed class CreateStoreResult {
    data class Created(val store: Store) : CreateStoreResult()
    data class InvalidTimezone(val timezone: String) : CreateStoreResult()
    data class InvalidCurrency(val currency: String) : CreateStoreResult()
    data object TaxProfileNotFound : CreateStoreResult()
}

sealed class UpdateStoreResult {
    data class Updated(val store: Store) : UpdateStoreResult()
    data object NotFound : UpdateStoreResult()
    data class InvalidTimezone(val timezone: String) : UpdateStoreResult()
    data class InvalidCurrency(val currency: String) : UpdateStoreResult()
    data object TaxProfileNotFound : UpdateStoreResult()
}

class StoreService(private val taxProfileService: TaxProfileService) {

    private val stores = ConcurrentHashMap<String, Store>()

    fun createStore(
        name: String,
        address: Address,
        timezone: String,
        currency: String,
        taxProfileId: String?
    ): CreateStoreResult {
        if (!StoreTimeZone.isValid(timezone)) return CreateStoreResult.InvalidTimezone(timezone)
        if (!isValidCurrency(currency)) return CreateStoreResult.InvalidCurrency(currency)
        if (taxProfileId != null && taxProfileService.getProfile(taxProfileId) == null) {
            return CreateStoreResult.TaxProfileNotFound
        }

        val store = Store(
            name = name,
            address = address,
            timezone = timezone,
            currency = currency.uppercase(),
            taxProfileId = taxProfileId
        )
        stores[store.id] = store
        return CreateStoreResult.Created(store)
    }

    fun getStore(id: String): Store? = stores[id]

    fun listStores(): List<Store> = stores.values.toList()

    fun updateStore(
        id: String,
        name: String?,
        address: Address?,
        timezone: String?,
        currency: String?,
        taxProfileId: String?
    ): UpdateStoreResult {
        val existing = stores[id] ?: return UpdateStoreResult.NotFound

        if (timezone != null && !StoreTimeZone.isValid(timezone)) {
            return UpdateStoreResult.InvalidTimezone(timezone)
        }
        if (currency != null && !isValidCurrency(currency)) {
            return UpdateStoreResult.InvalidCurrency(currency)
        }
        if (taxProfileId != null && taxProfileService.getProfile(taxProfileId) == null) {
            return UpdateStoreResult.TaxProfileNotFound
        }

        val updated = existing.copy(
            name = name ?: existing.name,
            address = address ?: existing.address,
            timezone = timezone ?: existing.timezone,
            currency = currency?.uppercase() ?: existing.currency,
            taxProfileId = taxProfileId ?: existing.taxProfileId,
            updatedAt = System.currentTimeMillis()
        )
        stores[id] = updated
        return UpdateStoreResult.Updated(updated)
    }

    fun deleteStore(id: String): Boolean = stores.remove(id) != null

    private fun isValidCurrency(currency: String): Boolean =
        runCatching { Currency.getInstance(currency.uppercase()) }.isSuccess
}
