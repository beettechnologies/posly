package com.beettechnologies.posly.flags

import kotlinx.serialization.Serializable

@Serializable
data class CreateFeatureFlagRequest(
    val key: String,
    val description: String,
    val enabled: Boolean = false,
    val rolloutPercentage: Int = 0
)

@Serializable
data class UpdateFeatureFlagRequest(
    val enabled: Boolean? = null,
    val rolloutPercentage: Int? = null,
    val enabledStoreIds: List<String>? = null
)

@Serializable
data class FeatureFlagResponse(
    val id: String,
    val key: String,
    val description: String,
    val enabled: Boolean,
    val rolloutPercentage: Int,
    val enabledStoreIds: List<String>,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class FlagEvaluationResponse(val key: String, val storeId: String, val enabled: Boolean, val reason: String)

@Serializable
data class FeatureFlagAuditLogEntryResponse(
    val timestamp: String,
    val event: String,
    val username: String?,
    val userId: String?,
    val remoteIp: String?,
    val detail: String?
)
