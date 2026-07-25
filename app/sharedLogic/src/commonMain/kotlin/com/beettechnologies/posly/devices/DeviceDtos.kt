package com.beettechnologies.posly.devices

import kotlinx.serialization.Serializable

@Serializable
data class CreatePairCodeRequest(
    val storeId: String,
    val expiresInSeconds: Long? = null,
    val terminalType: String? = null
)

@Serializable
data class PairCodeResponse(
    val code: String,
    val storeId: String,
    val expiresAt: String,
    val terminalType: String? = null
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

@Serializable
data class DeviceResponse(
    val id: String,
    val storeId: String,
    val name: String,
    val terminalType: String?,
    val enrolledAt: String,
    val status: String,
    val healthStatus: String,
    val lastSeenAt: String? = null,
    val deprovisionedAt: String? = null
)
