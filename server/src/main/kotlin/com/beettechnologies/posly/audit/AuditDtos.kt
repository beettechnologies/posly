package com.beettechnologies.posly.audit

import kotlinx.serialization.Serializable

@Serializable
data class AuditLogEntryResponse(
    val id: String,
    val timestamp: String,
    val event: String,
    val username: String?,
    val userId: String?,
    val deviceId: String?,
    val correlationId: String?,
    val remoteIp: String?,
    val detail: String?
)

fun AuditRecord.toResponse() = AuditLogEntryResponse(
    id = id,
    timestamp = timestamp.toString(),
    event = event.name,
    username = username,
    userId = userId,
    deviceId = deviceId,
    correlationId = correlationId,
    remoteIp = remoteIp,
    detail = detail
)

@Serializable
data class AuditRetentionResultResponse(
    val id: String,
    val ranAt: String,
    val cutoff: String,
    val purgedCount: Int,
    val archiveFilePath: String?
)

fun AuditRetentionResult.toResponse() = AuditRetentionResultResponse(
    id = id,
    ranAt = ranAt.toString(),
    cutoff = cutoff.toString(),
    purgedCount = purgedCount,
    archiveFilePath = archiveFilePath
)
