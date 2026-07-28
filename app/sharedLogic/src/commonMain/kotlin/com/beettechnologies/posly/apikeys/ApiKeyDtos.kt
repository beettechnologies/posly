package com.beettechnologies.posly.apikeys

import kotlinx.serialization.Serializable

@Serializable
data class CreateApiKeyRequest(val name: String, val scopes: List<String>)

@Serializable
data class ApiKeyResponse(
    val id: String,
    val name: String,
    val keyPrefix: String,
    val scopes: List<String>,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val createdBy: String?,
    val lastUsedAt: String?,
    val revokedAt: String?,
    val revokedBy: String?
)

/** Only ever returned once, from the create/rotate response body - see API_KEYS.md. */
@Serializable
data class ApiKeyCreatedResponse(val apiKey: ApiKeyResponse, val rawKey: String)

@Serializable
data class ApiKeyUsageResponse(
    val id: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val timestamp: String
)
