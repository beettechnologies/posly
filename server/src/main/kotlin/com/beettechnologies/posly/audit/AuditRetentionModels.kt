package com.beettechnologies.posly.audit

import java.time.Instant
import java.util.UUID

/**
 * One retention run's outcome. [archiveFilePath] is null when there was nothing to purge - no
 * empty archive file is written in that case.
 */
data class AuditRetentionResult(
    val id: String = UUID.randomUUID().toString(),
    val ranAt: Instant,
    val cutoff: Instant,
    val purgedCount: Int,
    val archiveFilePath: String?
)
