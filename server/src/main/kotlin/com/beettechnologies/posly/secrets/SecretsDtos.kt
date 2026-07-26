package com.beettechnologies.posly.secrets

import kotlinx.serialization.Serializable

@Serializable
data class SecretVersionSummaryResponse(
    val id: String,
    val issuedAt: String,
    val validUntil: String?,
    val status: String
)

fun SecretVersionSummary.toResponse() = SecretVersionSummaryResponse(
    id = id,
    issuedAt = issuedAt.toString(),
    validUntil = validUntil?.toString(),
    status = status.name
)

@Serializable
data class SecretSummaryResponse(
    val name: String,
    val current: SecretVersionSummaryResponse,
    val history: List<SecretVersionSummaryResponse>
)

/** Only ever returned once, immediately after [SecretsManager.rotate] - not retrievable from any GET endpoint. */
@Serializable
data class SecretRotationResponse(
    val secretName: String,
    val newVersionId: String,
    val newValue: String,
    val issuedAt: String,
    val previousVersionGraceExpiresAt: String?
)
