package com.beettechnologies.posly.audit

import com.beettechnologies.posly.db.InstantSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable
private data class AuditArchiveEntry(
    val id: String,
    @Serializable(with = InstantSerializer::class) val timestamp: Instant,
    val event: String,
    val username: String?,
    val userId: String?,
    val deviceId: String?,
    val correlationId: String?,
    val remoteIp: String?,
    val detail: String?
)

private val archiveJson = Json { encodeDefaults = true }

/**
 * Enforces the audit-log retention policy: rows older than [retentionDays] are archived as JSON
 * Lines under [archiveDirectory] and then purged from [AuditService], following the same
 * nullable-[CoroutineScope] self-scheduling idiom as [com.beettechnologies.posly.backup.BackupService].
 * No object-store/S3 integration exists in this environment (see DR_RUNBOOK.md), so the archive
 * is a local directory, same disclosed scope boundary as backups.
 */
class AuditRetentionService(
    private val archiveDirectory: String,
    scope: CoroutineScope? = null,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val retentionDays: Long = 90,
    private val checkIntervalMillis: Long = 24 * 60 * 60 * 1000L
) {
    init {
        scope?.launch {
            while (isActive) {
                delay(checkIntervalMillis)
                runRetentionNow()
            }
        }
    }

    fun runRetentionNow(): AuditRetentionResult {
        val now = nowProvider()
        val cutoff = now.minus(retentionDays, ChronoUnit.DAYS)
        val purged = AuditService.purgeOlderThan(cutoff)

        val archiveFilePath = if (purged.isNotEmpty()) {
            val dir = File(archiveDirectory).apply { mkdirs() }
            val file = File(dir, "audit-archive-${now.toEpochMilli()}.jsonl")
            file.bufferedWriter().use { writer ->
                purged.forEach { record ->
                    val entry = AuditArchiveEntry(
                        id = record.id,
                        timestamp = record.timestamp,
                        event = record.event.name,
                        username = record.username,
                        userId = record.userId,
                        deviceId = record.deviceId,
                        correlationId = record.correlationId,
                        remoteIp = record.remoteIp,
                        detail = record.detail
                    )
                    writer.write(archiveJson.encodeToString(entry))
                    writer.newLine()
                }
            }
            file.absolutePath
        } else {
            null
        }

        val result = AuditRetentionResult(
            ranAt = now,
            cutoff = cutoff,
            purgedCount = purged.size,
            archiveFilePath = archiveFilePath
        )
        AuditService.record(
            AuditEvent.AUDIT_RETENTION_COMPLETED,
            detail = "purgedCount=${result.purgedCount} cutoff=${result.cutoff} archiveFile=${result.archiveFilePath}"
        )
        return result
    }
}
