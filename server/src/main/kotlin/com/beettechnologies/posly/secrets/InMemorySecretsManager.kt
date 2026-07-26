package com.beettechnologies.posly.secrets

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private const val GENERATED_SECRET_LENGTH = 48

/**
 * Working [SecretsManager] for an environment with no real Vault/KMS available. Rotation state
 * lives only for the lifetime of this process - restarting the app resets every secret back to its
 * `application.conf`-seeded value, since there is no real Vault/KMS backing it here. That
 * limitation is disclosed in `SECURITY_COMPLIANCE.md`; a real deployment would implement
 * [SecretsManager] against an actual secrets store instead of this class.
 */
class InMemorySecretsManager(
    initialSecrets: Map<SecretName, String>,
    private val gracePeriodMs: Long,
    private val nowProvider: () -> Instant = { Instant.now() }
) : SecretsManager {
    private val secureRandom = SecureRandom()
    private val versionsByName = ConcurrentHashMap<SecretName, MutableList<SecretVersion>>()

    init {
        val now = nowProvider()
        initialSecrets.forEach { (name, value) ->
            versionsByName[name] = mutableListOf(
                SecretVersion(id = UUID.randomUUID().toString(), name = name, value = value, issuedAt = now, validUntil = null)
            )
        }
    }

    override fun current(name: SecretName): SecretVersion =
        versions(name).single { it.validUntil == null }

    override fun validVersions(name: SecretName): List<SecretVersion> {
        val now = nowProvider()
        return versions(name).filter { it.validUntil == null || it.validUntil.isAfter(now) }
    }

    @Synchronized
    override fun rotate(name: SecretName, actorUserId: String?): SecretVersion {
        val now = nowProvider()
        val graceExpiresAt = now.plusMillis(gracePeriodMs)
        val list = versions(name)
        val previousCurrentIndex = list.indexOfLast { it.validUntil == null }
        if (previousCurrentIndex >= 0) {
            list[previousCurrentIndex] = list[previousCurrentIndex].copy(validUntil = graceExpiresAt)
        }
        val newVersion = SecretVersion(
            id = UUID.randomUUID().toString(),
            name = name,
            value = randomSecretValue(),
            issuedAt = now,
            validUntil = null
        )
        list.add(newVersion)
        AuditService.record(
            AuditEvent.SECRET_ROTATED,
            userId = actorUserId,
            detail = "secret=$name newVersionId=${newVersion.id} previousVersionGraceExpiresAt=$graceExpiresAt"
        )
        return newVersion
    }

    override fun history(name: SecretName): List<SecretVersionSummary> {
        val now = nowProvider()
        return versions(name).map {
            val status = when {
                it.validUntil == null -> SecretVersionStatus.CURRENT
                it.validUntil.isAfter(now) -> SecretVersionStatus.IN_GRACE_PERIOD
                else -> SecretVersionStatus.EXPIRED
            }
            SecretVersionSummary(id = it.id, issuedAt = it.issuedAt, validUntil = it.validUntil, status = status)
        }
    }

    private fun versions(name: SecretName): MutableList<SecretVersion> =
        versionsByName[name] ?: error("No secret seeded for $name")

    private fun randomSecretValue(): String =
        buildString(GENERATED_SECRET_LENGTH) {
            repeat(GENERATED_SECRET_LENGTH) {
                append(SECRET_ALPHABET[secureRandom.nextInt(SECRET_ALPHABET.length)])
            }
        }
}
