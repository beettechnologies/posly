package com.beettechnologies.posly.stores

import kotlinx.serialization.Serializable

@Serializable
data class AddressDto(
    val line1: String,
    val line2: String? = null,
    val city: String,
    val state: String? = null,
    val postalCode: String,
    val country: String
)

@Serializable
data class CreateStoreRequest(
    val name: String,
    val address: AddressDto,
    val timezone: String,
    val currency: String,
    val taxProfileId: String? = null,
    val locale: String = "en-US"
)

@Serializable
data class UpdateStoreRequest(
    val name: String? = null,
    val address: AddressDto? = null,
    val timezone: String? = null,
    val currency: String? = null,
    val taxProfileId: String? = null,
    val locale: String? = null
)

@Serializable
data class StoreResponse(
    val id: String,
    val name: String,
    val address: AddressDto,
    val timezone: String,
    val currency: String,
    val locale: String,
    val taxProfileId: String? = null,
    /** Always present - the client should treat a 404 from this URL as "no logo uploaded yet." */
    val logoUrl: String,
    val createdAt: Long,
    val updatedAt: Long
)

fun Store.toResponse() = StoreResponse(
    id = id,
    name = name,
    address = AddressDto(
        line1 = address.line1,
        line2 = address.line2,
        city = address.city,
        state = address.state,
        postalCode = address.postalCode,
        country = address.country
    ),
    timezone = timezone,
    currency = currency,
    locale = locale,
    taxProfileId = taxProfileId,
    logoUrl = "/stores/$id/logo",
    createdAt = createdAt,
    updatedAt = updatedAt
)

@Serializable
data class LogoUploadResponse(val logoUrl: String)
