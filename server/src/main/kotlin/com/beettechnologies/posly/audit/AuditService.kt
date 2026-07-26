package com.beettechnologies.posly.audit

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Collections

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
    FINANCE_REPORT_DELIVERY_FAILED
}

data class AuditRecord(
    val timestamp: Instant,
    val event: AuditEvent,
    val username: String?,
    val userId: String?,
    val remoteIp: String?,
    val detail: String? = null
)

object AuditService {
    private val log = LoggerFactory.getLogger("AUDIT")
    private val records = Collections.synchronizedList(mutableListOf<AuditRecord>())

    fun record(
        event: AuditEvent,
        username: String? = null,
        userId: String? = null,
        remoteIp: String? = null,
        detail: String? = null
    ) {
        val record = AuditRecord(
            timestamp = Instant.now(),
            event = event,
            username = username,
            userId = userId,
            remoteIp = remoteIp,
            detail = detail
        )
        records.add(record)
        log.info("[AUDIT] event={} username={} userId={} ip={} detail={}",
            record.event, record.username, record.userId, record.remoteIp, record.detail)
    }

    /** Recent audit records, newest first - optionally filtered by user and/or event type. */
    fun list(username: String? = null, event: AuditEvent? = null): List<AuditRecord> =
        synchronized(records) {
            records.filter { (username == null || it.username == username) && (event == null || it.event == event) }
        }.sortedByDescending { it.timestamp }

    /** Test-only: clears recorded history without affecting the SLF4J log output. */
    internal fun clearForTests() {
        synchronized(records) { records.clear() }
    }
}
