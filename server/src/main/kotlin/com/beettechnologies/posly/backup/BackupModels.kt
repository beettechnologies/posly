package com.beettechnologies.posly.backup

import java.time.Instant
import java.util.UUID

enum class BackupStatus { SUCCESS, FAILED }

/**
 * One backup run's record. [validated] means the artifact was parsed/sanity-checked after being
 * written (non-empty, contains recognizable schema/data statements) - not a full test restore,
 * which is what a DR drill ([RestoreDrillResult]) is for.
 */
data class BackupMetadata(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Instant,
    val sizeBytes: Long,
    val checksumSha256: String,
    val filePath: String,
    val status: BackupStatus,
    val validated: Boolean,
    val errorMessage: String? = null
)

/**
 * One DR-drill (or real recovery) attempt: restoring [backupId] into [targetJdbcUrl] and verifying
 * the result. [tableRowCounts] is the restored target's per-table row count, compared against the
 * source at drill time - [rowCountsMatched] is the pass/fail signal for "data integrity."
 */
data class RestoreDrillResult(
    val id: String = UUID.randomUUID().toString(),
    val backupId: String,
    val targetJdbcUrl: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMillis: Long,
    val tableRowCounts: Map<String, Long>,
    val rowCountsMatched: Boolean,
    val status: BackupStatus,
    val errorMessage: String? = null
)
