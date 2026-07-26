package com.beettechnologies.posly.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupMetadataResponse(
    val id: String,
    val createdAt: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val status: String,
    val validated: Boolean,
    val errorMessage: String?
)

fun BackupMetadata.toResponse() = BackupMetadataResponse(
    id = id,
    createdAt = createdAt.toString(),
    sizeBytes = sizeBytes,
    checksumSha256 = checksumSha256,
    status = status.name,
    validated = validated,
    errorMessage = errorMessage
)

@Serializable
data class RestoreRequest(val targetJdbcUrl: String)

@Serializable
data class RestoreDrillResultResponse(
    val id: String,
    val backupId: String,
    val targetJdbcUrl: String,
    val startedAt: String,
    val completedAt: String,
    val durationMillis: Long,
    val tableRowCounts: Map<String, Long>,
    val rowCountsMatched: Boolean,
    val status: String,
    val errorMessage: String?
)

fun RestoreDrillResult.toResponse() = RestoreDrillResultResponse(
    id = id,
    backupId = backupId,
    targetJdbcUrl = targetJdbcUrl,
    startedAt = startedAt.toString(),
    completedAt = completedAt.toString(),
    durationMillis = durationMillis,
    tableRowCounts = tableRowCounts,
    rowCountsMatched = rowCountsMatched,
    status = status.name,
    errorMessage = errorMessage
)
