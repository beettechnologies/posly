package com.beettechnologies.posly.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.JWTVerifier
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.secrets.SecretName
import com.beettechnologies.posly.secrets.SecretsManager
import java.util.Date

class JwtService(
    private val secretsManager: SecretsManager,
    val issuer: String,
    val audience: String,
    val accessTokenExpirationMs: Long,
    val refreshTokenExpirationMs: Long,
    val mfaTokenExpirationMs: Long
) {
    /**
     * The version to sign new tokens with, plus its id as the JWT `kid` header - this is what lets
     * [algorithmForVerification] resolve the right historical key after [secretsManager] rotates,
     * so a token signed under the previous secret keeps verifying for the rest of its grace period.
     */
    private fun signingVersion() = secretsManager.current(SecretName.JWT_SIGNING_KEY)

    fun generateAccessToken(userId: String, roles: Set<Role>, roleVersion: Int): String {
        val version = signingVersion()
        return JWT.create()
            .withKeyId(version.id)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("roles", roles.map { it.name })
            .withClaim("roleVersion", roleVersion)
            .withClaim("type", "access")
            .withExpiresAt(Date(System.currentTimeMillis() + accessTokenExpirationMs))
            .sign(Algorithm.HMAC256(version.value))
    }

    fun generateRefreshToken(userId: String): String {
        val version = signingVersion()
        return JWT.create()
            .withKeyId(version.id)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("type", "refresh")
            .withExpiresAt(Date(System.currentTimeMillis() + refreshTokenExpirationMs))
            .sign(Algorithm.HMAC256(version.value))
    }

    fun generateMfaToken(userId: String): String {
        val version = signingVersion()
        return JWT.create()
            .withKeyId(version.id)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("type", "mfa")
            .withExpiresAt(Date(System.currentTimeMillis() + mfaTokenExpirationMs))
            .sign(Algorithm.HMAC256(version.value))
    }

    /** A week-long-by-default, single-purpose token an invited user redeems (once) to set their own password. */
    fun generateInviteToken(userId: String, expirationMs: Long = INVITE_TOKEN_EXPIRATION_MS): String {
        val version = signingVersion()
        return JWT.create()
            .withKeyId(version.id)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("type", "invite")
            .withExpiresAt(Date(System.currentTimeMillis() + expirationMs))
            .sign(Algorithm.HMAC256(version.value))
    }

    /**
     * Resolves the [Algorithm] to verify [token] with: decodes it (unverified) to read its `kid`
     * header, then looks that id up among [SecretsManager.validVersions] - the current signing
     * secret plus any previous one still within its post-rotation grace period. Tokens issued
     * without a `kid` (none should be, once every sign method above always sets one) fall back to
     * the current version. Returns null if the token can't be decoded or its `kid` matches no
     * still-valid version (rotated out past its grace period, or forged).
     */
    private fun algorithmForVerification(token: String): Algorithm? {
        val decoded = runCatching { JWT.decode(token) }.getOrNull() ?: return null
        val versions = secretsManager.validVersions(SecretName.JWT_SIGNING_KEY)
        val version = decoded.keyId?.let { kid -> versions.find { it.id == kid } }
            ?: versions.find { it.validUntil == null }
            ?: return null
        return Algorithm.HMAC256(version.value)
    }

    private fun verifierFor(algorithm: Algorithm) = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    /**
     * Builds the [JWTVerifier] Ktor's `jwt("jwt-auth")` plugin should use for an incoming bearer
     * token: resolves the right historical key by the token's `kid` (same as [verifyAccessToken]),
     * requiring `type=access` at the verifier level so a refresh/mfa/invite token is rejected
     * before the plugin's `validate` block even runs. Returns null if no valid key can be resolved
     * (malformed token, or its `kid` has aged out of every secret's grace period) - the plugin
     * treats a null verifier as an authentication failure.
     */
    fun accessTokenVerifierFor(token: String): JWTVerifier? {
        val algorithm = algorithmForVerification(token) ?: return null
        return JWT.require(algorithm)
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("type", "access")
            .build()
    }

    /**
     * [roleVersion] is a snapshot of [com.beettechnologies.posly.model.User.roleVersion] at issue
     * time - the JWT `validate` block in Application.kt re-checks it against the live value on
     * every request, so bumping it (any role or status change) invalidates every token already
     * issued for that user, without needing a revocation list.
     */
    fun verifyAccessToken(token: String): TokenClaims? = runCatching {
        val algorithm = algorithmForVerification(token) ?: return null
        val decoded = verifierFor(algorithm).verify(token)
        if (decoded.getClaim("type").asString() != "access") return null
        val rolesRaw = decoded.getClaim("roles").asList(String::class.java) ?: emptyList()
        val roles = rolesRaw.mapNotNull { runCatching { Role.valueOf(it) }.getOrNull() }.toSet()
        val roleVersion = decoded.getClaim("roleVersion").asInt() ?: 0
        TokenClaims(userId = decoded.subject, roles = roles, roleVersion = roleVersion)
    }.getOrElse { if (it is JWTVerificationException) null else throw it }

    fun verifyRefreshToken(token: String): String? = runCatching {
        val algorithm = algorithmForVerification(token) ?: return null
        val decoded = verifierFor(algorithm).verify(token)
        if (decoded.getClaim("type").asString() != "refresh") return null
        decoded.subject
    }.getOrElse { if (it is JWTVerificationException) null else throw it }

    fun verifyMfaToken(token: String): String? = runCatching {
        val algorithm = algorithmForVerification(token) ?: return null
        val decoded = verifierFor(algorithm).verify(token)
        if (decoded.getClaim("type").asString() != "mfa") return null
        decoded.subject
    }.getOrElse { if (it is JWTVerificationException) null else throw it }

    fun verifyInviteToken(token: String): String? = runCatching {
        val algorithm = algorithmForVerification(token) ?: return null
        val decoded = verifierFor(algorithm).verify(token)
        if (decoded.getClaim("type").asString() != "invite") return null
        decoded.subject
    }.getOrElse { if (it is JWTVerificationException) null else throw it }

    companion object {
        private const val INVITE_TOKEN_EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

data class TokenClaims(val userId: String, val roles: Set<Role>, val roleVersion: Int = 0)
