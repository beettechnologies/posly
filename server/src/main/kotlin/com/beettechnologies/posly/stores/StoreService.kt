package com.beettechnologies.posly.stores

import com.beettechnologies.posly.db.StoresTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Currency

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

private fun rowToStore(row: ResultRow) = Store(
    id = row[StoresTable.id],
    name = row[StoresTable.name],
    address = row[StoresTable.address],
    timezone = row[StoresTable.timezone],
    currency = row[StoresTable.currency],
    taxProfileId = row[StoresTable.taxProfileId],
    createdAt = row[StoresTable.createdAt],
    updatedAt = row[StoresTable.updatedAt]
)

class StoreService(private val taxProfileService: TaxProfileService) {

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
        transaction {
            StoresTable.insert {
                it[id] = store.id
                it[StoresTable.name] = store.name
                it[StoresTable.address] = store.address
                it[StoresTable.timezone] = store.timezone
                it[StoresTable.currency] = store.currency
                it[StoresTable.taxProfileId] = store.taxProfileId
                it[createdAt] = store.createdAt
                it[updatedAt] = store.updatedAt
            }
        }
        return CreateStoreResult.Created(store)
    }

    fun getStore(id: String): Store? = transaction {
        StoresTable.selectAll().where { StoresTable.id eq id }.singleOrNull()?.let { rowToStore(it) }
    }

    fun listStores(): List<Store> = transaction {
        StoresTable.selectAll().map { rowToStore(it) }
    }

    fun updateStore(
        id: String,
        name: String?,
        address: Address?,
        timezone: String?,
        currency: String?,
        taxProfileId: String?
    ): UpdateStoreResult {
        if (timezone != null && !StoreTimeZone.isValid(timezone)) {
            return UpdateStoreResult.InvalidTimezone(timezone)
        }
        if (currency != null && !isValidCurrency(currency)) {
            return UpdateStoreResult.InvalidCurrency(currency)
        }
        if (taxProfileId != null && taxProfileService.getProfile(taxProfileId) == null) {
            return UpdateStoreResult.TaxProfileNotFound
        }

        return transaction {
            val existing = StoresTable.selectAll().where { StoresTable.id eq id }.singleOrNull()?.let { rowToStore(it) }
                ?: return@transaction UpdateStoreResult.NotFound

            val updated = existing.copy(
                name = name ?: existing.name,
                address = address ?: existing.address,
                timezone = timezone ?: existing.timezone,
                currency = currency?.uppercase() ?: existing.currency,
                taxProfileId = taxProfileId ?: existing.taxProfileId,
                updatedAt = System.currentTimeMillis()
            )
            StoresTable.update({ StoresTable.id eq id }) {
                it[StoresTable.name] = updated.name
                it[StoresTable.address] = updated.address
                it[StoresTable.timezone] = updated.timezone
                it[StoresTable.currency] = updated.currency
                it[StoresTable.taxProfileId] = updated.taxProfileId
                it[updatedAt] = updated.updatedAt
            }
            UpdateStoreResult.Updated(updated)
        }
    }

    fun deleteStore(id: String): Boolean = transaction {
        StoresTable.deleteWhere { StoresTable.id eq id } > 0
    }

    private fun isValidCurrency(currency: String): Boolean =
        runCatching { Currency.getInstance(currency.uppercase()) }.isSuccess
}
