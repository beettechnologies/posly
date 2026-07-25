package com.beettechnologies.posly.devices

import kotlinx.serialization.Serializable

@Serializable
data class CreatePairCodeRequest(
    val storeId: String,
    val expiresInSeconds: Long? = null
)

@Serializable
data class PairCodeResponse(
    val code: String,
    val storeId: String,
    val expiresAt: String
)

@Serializable
data class EnrollDeviceRequest(
    val code: String,
    val storeId: String? = null,
    val name: String? = null
)

@Serializable
data class EnrollDeviceResponse(
    val deviceId: String,
    val storeId: String,
    val clientId: String,
    val clientSecret: String
)
