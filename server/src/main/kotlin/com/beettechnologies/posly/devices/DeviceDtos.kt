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
data class ValidatePairCodeRequest(val code: String)

@Serializable
data class ValidatePairCodeResponse(
    val valid: Boolean,
    val storeId: String? = null,
    val expiresAt: String? = null,
    val error: String? = null
)

@Serializable
data class RevokePairCodeRequest(val code: String)

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

@Serializable
data class HeartbeatRequest(
    val clientId: String,
    val clientSecret: String
)

@Serializable
data class HeartbeatResponse(
    val status: String,
    val lastSeenAt: String
)
