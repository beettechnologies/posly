package com.beettechnologies.posly.stores

import kotlinx.serialization.Serializable
import java.math.RoundingMode
import java.util.UUID

@Serializable
data class Address(
    val line1: String,
    val line2: String? = null,
    val city: String,
    val state: String? = null,
    val postalCode: String,
    val country: String
)

data class Store(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: Address,
    val timezone: String,
    val currency: String,
    val locale: String = "en-US",
    val taxProfileId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A store's uploaded branding image, stored as a raw blob (mirrors [com.beettechnologies.posly.products.ProductService]'s image storage) - one row per store, replaced (not appended to) on re-upload. */
data class StoreLogo(
    val fileName: String,
    val bytes: ByteArray
)

enum class PricingMode { INCLUSIVE, EXCLUSIVE }

@Serializable
data class TaxRate(
    val name: String,
    val ratePercent: Double,
    /** Rates are applied (and displayed) in ascending order; ties keep their original list position. */
    val order: Int = 0,
    /** If true, this rate taxes the running total after every lower-order rate's already-rounded tax - true tax-on-tax stacking (e.g. GST then PST on the GST-inclusive amount) rather than each rate taxing the same original base independently. */
    val compoundsOnPrior: Boolean = false
)

data class TaxProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val rates: List<TaxRate>,
    val pricingMode: PricingMode = PricingMode.EXCLUSIVE,
    val roundingMode: RoundingMode = RoundingMode.HALF_UP,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
