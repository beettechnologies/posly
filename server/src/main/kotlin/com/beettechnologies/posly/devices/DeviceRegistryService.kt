package com.beettechnologies.posly.devices

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_TTL_SECONDS = 300L
private const val PAIRING_CODE_LENGTH = 8
private const val CLIENT_SECRET_LENGTH = 32
private const val MAX_CREATE_ATTEMPTS = 10

private const val PAIRING_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val CREDENTIAL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

data class PairingCode(
    val code: String,
    val storeId: String,
    val createdBy: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val deviceId: String? = null
)

data class DeviceRecord(
    val id: String,
    val storeId: String,
    val name: String,
    val enrolledAt: Instant,
    val clientId: String,
    val clientSecret: String
)

data class DeviceAuditRecord(
    val timestamp: Instant,
    val event: DeviceAuditEvent,
    val code: String? = null,
    val deviceId: String? = null,
    val storeId: String? = null,
    val actorId: String? = null,
    val detail: String? = null
)

enum class DeviceAuditEvent {
    PAIR_CODE_CREATED,
    PAIR_CODE_VALIDATED,
    PAIR_CODE_REVOKED,
    DEVICE_ENROLLED,
    ENROLLMENT_REJECTED
}

sealed class PairingCodeValidationResult {
    data class Valid(val pairingCode: PairingCode) : PairingCodeValidationResult()
    data object NotFound : PairingCodeValidationResult()
    data object Expired : PairingCodeValidationResult()
    data object Used : PairingCodeValidationResult()
    data object Revoked : PairingCodeValidationResult()
}

sealed class PairCodeRevokeResult {
    data object Revoked : PairCodeRevokeResult()
    data object NotFound : PairCodeRevokeResult()
    data object AlreadyUsed : PairCodeRevokeResult()
    data object AlreadyRevoked : PairCodeRevokeResult()
}

sealed class EnrollDeviceResult {
    data class Success(val device: DeviceRecord) : EnrollDeviceResult()
    data object PairCodeNotFound : EnrollDeviceResult()
    data object PairCodeExpired : EnrollDeviceResult()
    data object PairCodeUsed : EnrollDeviceResult()
    data object PairCodeRevoked : EnrollDeviceResult()
    data object StoreMismatch : EnrollDeviceResult()
}

class DeviceRegistryService(
    private val nowProvider: () -> Instant = { Instant.now() },
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private val lock = Any()
    private val pairingCodes = ConcurrentHashMap<String, PairingCode>()
    private val devices = ConcurrentHashMap<String, DeviceRecord>()
    private val auditTrail = mutableListOf<DeviceAuditRecord>()

    fun createPairCode(storeId: String, createdBy: String, expiresInSeconds: Long? = null): PairingCode {
        val now = nowProvider()
        val ttl = expiresInSeconds ?: DEFAULT_TTL_SECONDS
        val expiresAt = now.plusSeconds(ttl.coerceAtLeast(0))

        repeat(MAX_CREATE_ATTEMPTS) {
            val code = randomToken(PAIRING_CODE_LENGTH, PAIRING_CODE_ALPHABET)
            val pairingCode = PairingCode(
                code = code,
                storeId = storeId,
                createdBy = createdBy,
                createdAt = now,
                expiresAt = expiresAt
            )
            if (pairingCodes.putIfAbsent(code, pairingCode) == null) {
                recordAudit(
                    DeviceAuditEvent.PAIR_CODE_CREATED,
                    code = code,
                    storeId = storeId,
                    actorId = createdBy,
                    detail = "expiresAt=$expiresAt"
                )
                return pairingCode
            }
        }
        error("Failed to allocate unique pairing code")
    }

    fun validatePairCode(code: String): PairingCodeValidationResult {
        val pairingCode = pairingCodes[code] ?: return PairingCodeValidationResult.NotFound
        return validatePairingCodeState(pairingCode).also {
            val detail = when (it) {
                is PairingCodeValidationResult.Valid -> "valid"
                PairingCodeValidationResult.NotFound -> "not_found"
                PairingCodeValidationResult.Expired -> "expired"
                PairingCodeValidationResult.Used -> "used"
                PairingCodeValidationResult.Revoked -> "revoked"
            }
            recordAudit(
                DeviceAuditEvent.PAIR_CODE_VALIDATED,
                code = code,
                storeId = pairingCode.storeId,
                detail = detail
            )
        }
    }

    fun revokePairCode(code: String, revokedBy: String): PairCodeRevokeResult {
        synchronized(lock) {
            val pairingCode = pairingCodes[code] ?: return PairCodeRevokeResult.NotFound
            if (pairingCode.usedAt != null) return PairCodeRevokeResult.AlreadyUsed
            if (pairingCode.revokedAt != null) return PairCodeRevokeResult.AlreadyRevoked

            val revokedCode = pairingCode.copy(revokedAt = nowProvider())
            pairingCodes[code] = revokedCode
            recordAudit(
                DeviceAuditEvent.PAIR_CODE_REVOKED,
                code = code,
                storeId = pairingCode.storeId,
                actorId = revokedBy
            )
            return PairCodeRevokeResult.Revoked
        }
    }

    fun enrollDevice(code: String, requestedStoreId: String?, name: String?): EnrollDeviceResult {
        synchronized(lock) {
            val pairingCode = pairingCodes[code] ?: return EnrollDeviceResult.PairCodeNotFound
            when (validatePairingCodeState(pairingCode)) {
                PairingCodeValidationResult.NotFound -> return EnrollDeviceResult.PairCodeNotFound
                PairingCodeValidationResult.Expired -> {
                    rejectEnrollment(code, pairingCode.storeId, "Pairing code expired")
                    return EnrollDeviceResult.PairCodeExpired
                }
                PairingCodeValidationResult.Used -> {
                    rejectEnrollment(code, pairingCode.storeId, "Pairing code already used")
                    return EnrollDeviceResult.PairCodeUsed
                }
                PairingCodeValidationResult.Revoked -> {
                    rejectEnrollment(code, pairingCode.storeId, "Pairing code revoked")
                    return EnrollDeviceResult.PairCodeRevoked
                }
                is PairingCodeValidationResult.Valid -> Unit
            }

            if (requestedStoreId != null && requestedStoreId != pairingCode.storeId) {
                rejectEnrollment(code, pairingCode.storeId, "Store mismatch")
                return EnrollDeviceResult.StoreMismatch
            }

            val now = nowProvider()
            val deviceId = UUID.randomUUID().toString()
            val device = DeviceRecord(
                id = deviceId,
                storeId = pairingCode.storeId,
                name = name?.takeIf { it.isNotBlank() } ?: "Store device",
                enrolledAt = now,
                clientId = "dev_${randomToken(16, CREDENTIAL_ALPHABET)}",
                clientSecret = randomToken(CLIENT_SECRET_LENGTH, CREDENTIAL_ALPHABET)
            )
            devices[deviceId] = device
            pairingCodes[code] = pairingCode.copy(usedAt = now, deviceId = deviceId)

            recordAudit(
                DeviceAuditEvent.DEVICE_ENROLLED,
                code = code,
                deviceId = deviceId,
                storeId = pairingCode.storeId,
                detail = "name=${device.name}"
            )
            AuditService.record(
                AuditEvent.DEVICE_ENROLLMENT_SUCCESS,
                detail = "deviceId=$deviceId storeId=${pairingCode.storeId}"
            )
            return EnrollDeviceResult.Success(device)
        }
    }

    fun listAuditTrail(): List<DeviceAuditRecord> = synchronized(auditTrail) { auditTrail.toList() }

    private fun validatePairingCodeState(pairingCode: PairingCode): PairingCodeValidationResult {
        val now = nowProvider()
        return when {
            pairingCode.revokedAt != null -> PairingCodeValidationResult.Revoked
            pairingCode.usedAt != null -> PairingCodeValidationResult.Used
            !now.isBefore(pairingCode.expiresAt) -> PairingCodeValidationResult.Expired
            else -> PairingCodeValidationResult.Valid(pairingCode)
        }
    }

    private fun rejectEnrollment(code: String, storeId: String, reason: String) {
        recordAudit(
            DeviceAuditEvent.ENROLLMENT_REJECTED,
            code = code,
            storeId = storeId,
            detail = reason
        )
        AuditService.record(AuditEvent.DEVICE_ENROLLMENT_FAILURE, detail = "code=$code reason=$reason")
    }

    private fun recordAudit(
        event: DeviceAuditEvent,
        code: String? = null,
        deviceId: String? = null,
        storeId: String? = null,
        actorId: String? = null,
        detail: String? = null
    ) {
        synchronized(auditTrail) {
            auditTrail += DeviceAuditRecord(
                timestamp = nowProvider(),
                event = event,
                code = code,
                deviceId = deviceId,
                storeId = storeId,
                actorId = actorId,
                detail = detail
            )
        }
    }

    private fun randomToken(length: Int, alphabet: String): String =
        buildString(length) {
            repeat(length) {
                append(alphabet[secureRandom.nextInt(alphabet.length)])
            }
        }
}
