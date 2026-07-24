package com.beettechnologies.posly.audit

import org.slf4j.LoggerFactory
import java.time.Instant

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
    DEVICE_ENROLLMENT_FAILURE
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
        log.info("[AUDIT] event={} username={} userId={} ip={} detail={}",
            record.event, record.username, record.userId, record.remoteIp, record.detail)
    }
}
