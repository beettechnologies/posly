package com.beettechnologies.posly.secrets

import java.time.Instant

enum class SecretName { JWT_SIGNING_KEY, PAYMENT_WEBHOOK_SECRET }

enum class SecretVersionStatus { CURRENT, IN_GRACE_PERIOD, EXPIRED }

/** The raw secret material for one version of a [SecretName] - only ever handed to signers/verifiers, never serialized in an API response. */
data class SecretVersion(
    val id: String,
    val name: SecretName,
    val value: String,
    val issuedAt: Instant,
    val validUntil: Instant?
)

/** Metadata-only view of a [SecretVersion], safe to expose over an admin API - deliberately has no [SecretVersion.value]. */
data class SecretVersionSummary(
    val id: String,
    val issuedAt: Instant,
    val validUntil: Instant?,
    val status: SecretVersionStatus
)

/**
 * Local secrets-management abstraction, following this codebase's existing gateway-interface
 * convention (see [com.beettechnologies.posly.payments.PaymentGateway], [com.beettechnologies.posly.email.EmailGateway]):
 * a real, working implementation usable today ([InMemorySecretsManager]), with this interface as
 * the seam a real HashiCorp Vault or cloud KMS client could implement later without changing any
 * caller - [com.beettechnologies.posly.auth.JwtService] and
 * [com.beettechnologies.posly.payments.PaymentGatewayService] depend only on this interface.
 */
interface SecretsManager {
    /** The version callers should sign/produce new tokens or signatures with. */
    fun current(name: SecretName): SecretVersion

    /** Every version verifiers should still accept - the current version plus any previous version still within its grace period. */
    fun validVersions(name: SecretName): List<SecretVersion>

    /**
     * Rotates [name] to a newly generated value, immediately making it [current] while the
     * superseded version remains in [validVersions] until its grace period elapses - this is what
     * lets dependent services keep verifying in-flight tokens/signatures with zero downtime.
     * Returns the new version, including its raw value - the only place that value is ever handed
     * back to a caller.
     */
    fun rotate(name: SecretName, actorUserId: String?): SecretVersion

    /** Every version ever issued for [name], metadata only - for an admin rotation-history listing. */
    fun history(name: SecretName): List<SecretVersionSummary>
}
