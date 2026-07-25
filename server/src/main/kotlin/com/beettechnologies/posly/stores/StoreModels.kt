package com.beettechnologies.posly.stores

import java.util.UUID

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
    val taxProfileId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class TaxRate(
    val name: String,
    val ratePercent: Double
)

data class TaxProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val rates: List<TaxRate>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
