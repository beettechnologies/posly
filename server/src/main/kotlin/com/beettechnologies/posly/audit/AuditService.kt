package com.beettechnologies.posly.audit

import com.beettechnologies.posly.db.AuditTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

enum class AuditEvent {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    REFRESH_SUCCESS,
    REFRESH_FAILURE,
    MFA_CHALLENGE,
    MFA_SUCCESS,
    MFA_FAILURE,
    ACCESS_DENIED,
    DEVICE_ENROLLMENT_SUCCESS,
    DEVICE_ENROLLMENT_FAILURE,
    DEVICE_DEPROVISIONED,
    OFFLINE_SALE_CONFLICT,
    USER_INVITED,
    USER_ROLES_CHANGED,
    USER_STATUS_CHANGED,
    SSO_CONFIGURED,
    SSO_LOGIN_SUCCESS,
    SSO_LOGIN_FAILURE,
    PRODUCT_IMPORT_STARTED,
    PRODUCT_IMPORT_COMPLETED,
    PRODUCT_IMPORT_ROLLED_BACK,
    REPORT_PIPELINE_STARTED,
    REPORT_PIPELINE_COMPLETED,
    REPORT_PIPELINE_FAILED,
    FINANCE_REPORT_GENERATED,
    FINANCE_REPORT_SCHEDULED,
    FINANCE_REPORT_DELIVERED,
    FINANCE_REPORT_DELIVERY_FAILED,
    BACKUP_COMPLETED,
    BACKUP_FAILED,
    RESTORE_DRILL_COMPLETED,
    RESTORE_DRILL_FAILED,
    SECRET_ROTATED,
    FEATURE_FLAG_CREATED,
    FEATURE_FLAG_UPDATED,
    ORDER_CREATED,
    ORDER_PAYMENT_CONFIRMED,
    ORDER_REFUNDED,
    AUDIT_RETENTION_COMPLETED,
    STORE_LOGO_UPDATED,
    API_KEY_CREATED,
    API_KEY_ROTATED,
    API_KEY_REVOKED
}

data class AuditRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Instant,
    val event: AuditEvent,
    val username: String?,
    val userId: String?,
    val deviceId: String? = null,
    val correlationId: String? = null,
    val remoteIp: String?,
    val detail: String? = null
)

private fun rowToAuditRecord(row: ResultRow) = AuditRecord(
    id = row[AuditTable.id],
    timestamp = row[AuditTable.timestamp],
    event = AuditEvent.valueOf(row[AuditTable.event]),
    username = row[AuditTable.username],
    userId = row[AuditTable.userId],
    deviceId = row[AuditTable.deviceId],
    correlationId = row[AuditTable.correlationId],
    remoteIp = row[AuditTable.remoteIp],
    detail = row[AuditTable.detail]
)

/**
 * Exposed-backed since Ticket 40 (previously an in-memory list) - every call site across the
 * codebase still just calls [record]/[list] statically, unchanged, following the same
 * migrate-internals-only-keep-the-public-API pattern as [com.beettechnologies.posly.stores.TaxProfileService]
 * and friends in the original database migration.
 */
object AuditService {
    private val log = LoggerFactory.getLogger("AUDIT")

    fun record(
        event: AuditEvent,
        username: String? = null,
        userId: String? = null,
        deviceId: String? = null,
        correlationId: String? = null,
        remoteIp: String? = null,
        detail: String? = null
    ) {
        val record = AuditRecord(
            timestamp = Instant.now(),
            event = event,
            username = username,
            userId = userId,
            deviceId = deviceId,
            correlationId = correlationId,
            remoteIp = remoteIp,
            detail = detail
        )
        transaction {
            AuditTable.insert {
                it[AuditTable.id] = record.id
                it[AuditTable.timestamp] = record.timestamp
                it[AuditTable.event] = record.event.name
                it[AuditTable.username] = record.username
                it[AuditTable.userId] = record.userId
                it[AuditTable.deviceId] = record.deviceId
                it[AuditTable.correlationId] = record.correlationId
                it[AuditTable.remoteIp] = record.remoteIp
                it[AuditTable.detail] = record.detail
            }
        }
        log.info(
            "[AUDIT] event={} username={} userId={} deviceId={} correlationId={} ip={} detail={}",
            record.event, record.username, record.userId, record.deviceId, record.correlationId, record.remoteIp, record.detail
        )
    }

    /** Recent audit records, newest first - optionally filtered by user, event type, and/or correlation id. */
    fun list(username: String? = null, event: AuditEvent? = null, correlationId: String? = null): List<AuditRecord> = transaction {
        var query = AuditTable.selectAll()
        if (username != null) query = query.andWhere { AuditTable.username eq username }
        if (event != null) query = query.andWhere { AuditTable.event eq event.name }
        if (correlationId != null) query = query.andWhere { AuditTable.correlationId eq correlationId }
        query.map { rowToAuditRecord(it) }.sortedByDescending { it.timestamp }
    }

    /** Deletes every record timestamped strictly before [cutoff] and returns what was deleted, for the caller to archive. */
    fun purgeOlderThan(cutoff: Instant): List<AuditRecord> = transaction {
        val toDelete = AuditTable.selectAll().andWhere { AuditTable.timestamp less cutoff }.map { rowToAuditRecord(it) }
        if (toDelete.isNotEmpty()) {
            AuditTable.deleteWhere { AuditTable.timestamp less cutoff }
        }
        toDelete
    }

    /** Test-only: clears recorded history without affecting the SLF4J log output. */
    internal fun clearForTests() {
        transaction { AuditTable.deleteAll() }
    }
}
