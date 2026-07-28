package com.beettechnologies.posly.stores

import com.beettechnologies.posly.db.StoreLogosTable
import com.beettechnologies.posly.db.StoresTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Currency
import java.util.Locale
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

/** A logo upload is capped at 2 MB - generous for a receipt/UI logo, small enough to keep embedding it in every generated PDF cheap. */
private const val MAX_LOGO_BYTES = 2 * 1024 * 1024

sealed class CreateStoreResult {
    data class Created(val store: Store) : CreateStoreResult()
    data class InvalidTimezone(val timezone: String) : CreateStoreResult()
    data class InvalidCurrency(val currency: String) : CreateStoreResult()
    data class InvalidLocale(val locale: String) : CreateStoreResult()
    data object TaxProfileNotFound : CreateStoreResult()
}

sealed class UpdateStoreResult {
    data class Updated(val store: Store) : UpdateStoreResult()
    data object NotFound : UpdateStoreResult()
    data class InvalidTimezone(val timezone: String) : UpdateStoreResult()
    data class InvalidCurrency(val currency: String) : UpdateStoreResult()
    data class InvalidLocale(val locale: String) : UpdateStoreResult()
    data object TaxProfileNotFound : UpdateStoreResult()
}

sealed class UploadLogoResult {
    data class Success(val logoUrl: String) : UploadLogoResult()
    data object StoreNotFound : UploadLogoResult()
    data class InvalidImage(val message: String) : UploadLogoResult()
}

private fun rowToStore(row: ResultRow) = Store(
    id = row[StoresTable.id],
    name = row[StoresTable.name],
    address = row[StoresTable.address],
    timezone = row[StoresTable.timezone],
    currency = row[StoresTable.currency],
    locale = row[StoresTable.locale],
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
        taxProfileId: String?,
        locale: String = "en-US"
    ): CreateStoreResult {
        if (!StoreTimeZone.isValid(timezone)) return CreateStoreResult.InvalidTimezone(timezone)
        if (!isValidCurrency(currency)) return CreateStoreResult.InvalidCurrency(currency)
        if (!isValidLocale(locale)) return CreateStoreResult.InvalidLocale(locale)
        if (taxProfileId != null && taxProfileService.getProfile(taxProfileId) == null) {
            return CreateStoreResult.TaxProfileNotFound
        }

        val store = Store(
            name = name,
            address = address,
            timezone = timezone,
            currency = currency.uppercase(),
            locale = locale,
            taxProfileId = taxProfileId
        )
        transaction {
            StoresTable.insert {
                it[id] = store.id
                it[StoresTable.name] = store.name
                it[StoresTable.address] = store.address
                it[StoresTable.timezone] = store.timezone
                it[StoresTable.currency] = store.currency
                it[StoresTable.locale] = store.locale
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
        taxProfileId: String?,
        locale: String? = null
    ): UpdateStoreResult {
        if (timezone != null && !StoreTimeZone.isValid(timezone)) {
            return UpdateStoreResult.InvalidTimezone(timezone)
        }
        if (currency != null && !isValidCurrency(currency)) {
            return UpdateStoreResult.InvalidCurrency(currency)
        }
        if (locale != null && !isValidLocale(locale)) {
            return UpdateStoreResult.InvalidLocale(locale)
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
                locale = locale ?: existing.locale,
                taxProfileId = taxProfileId ?: existing.taxProfileId,
                updatedAt = System.currentTimeMillis()
            )
            StoresTable.update({ StoresTable.id eq id }) {
                it[StoresTable.name] = updated.name
                it[StoresTable.address] = updated.address
                it[StoresTable.timezone] = updated.timezone
                it[StoresTable.currency] = updated.currency
                it[StoresTable.locale] = updated.locale
                it[StoresTable.taxProfileId] = updated.taxProfileId
                it[updatedAt] = updated.updatedAt
            }
            UpdateStoreResult.Updated(updated)
        }
    }

    fun deleteStore(id: String): Boolean = transaction {
        StoresTable.deleteWhere { StoresTable.id eq id } > 0
    }

    fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoResult {
        if (getStore(storeId) == null) return UploadLogoResult.StoreNotFound
        if (bytes.size > MAX_LOGO_BYTES) {
            return UploadLogoResult.InvalidImage("Logo must be smaller than ${MAX_LOGO_BYTES / (1024 * 1024)}MB")
        }
        val decodable = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() != null
        if (!decodable) return UploadLogoResult.InvalidImage("File is not a readable image")

        transaction {
            StoreLogosTable.deleteWhere { StoreLogosTable.storeId eq storeId }
            StoreLogosTable.insert {
                it[StoreLogosTable.storeId] = storeId
                it[StoreLogosTable.fileName] = fileName
                it[StoreLogosTable.bytes] = ExposedBlob(bytes)
                it[uploadedAt] = System.currentTimeMillis()
            }
        }
        return UploadLogoResult.Success("/stores/$storeId/logo")
    }

    fun getLogo(storeId: String): StoreLogo? = transaction {
        StoreLogosTable.selectAll().where { StoreLogosTable.storeId eq storeId }.singleOrNull()?.let { row ->
            StoreLogo(fileName = row[StoreLogosTable.fileName], bytes = row[StoreLogosTable.bytes].bytes)
        }
    }

    private fun isValidCurrency(currency: String): Boolean =
        runCatching { Currency.getInstance(currency.uppercase()) }.isSuccess

    private fun isValidLocale(locale: String): Boolean =
        runCatching { Locale.forLanguageTag(locale).language.isNotBlank() }.getOrDefault(false)
}
