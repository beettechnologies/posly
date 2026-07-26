package com.beettechnologies.posly.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.beettechnologies.posly.model.Role
import java.util.Date

class JwtService(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTokenExpirationMs: Long,
    val refreshTokenExpirationMs: Long,
    val mfaTokenExpirationMs: Long
) {
    private val algorithm = Algorithm.HMAC256(secret)

    /**
     * [roleVersion] is a snapshot of [com.beettechnologies.posly.model.User.roleVersion] at issue
     * time - the JWT `validate` block in Application.kt re-checks it against the live value on
     * every request, so bumping it (any role or status change) invalidates every token already
     * issued for that user, without needing a revocation list.
     */
    fun generateAccessToken(userId: String, roles: Set<Role>, roleVersion: Int): String =
        JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("roles", roles.map { it.name })
            .withClaim("roleVersion", roleVersion)
            .withClaim("type", "access")
            .withExpiresAt(Date(System.currentTimeMillis() + accessTokenExpirationMs))
            .sign(algorithm)

    fun generateRefreshToken(userId: String): String =
        JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("type", "refresh")
            .withExpiresAt(Date(System.currentTimeMillis() + refreshTokenExpirationMs))
            .sign(algorithm)

    fun generateMfaToken(userId: String): String =
        JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("type", "mfa")
            .withExpiresAt(Date(System.currentTimeMillis() + mfaTokenExpirationMs))
            .sign(algorithm)

    /** A week-long-by-default, single-purpose token an invited user redeems (once) to set their own password. */
    fun generateInviteToken(userId: String, expirationMs: Long = INVITE_TOKEN_EXPIRATION_MS): String =
        JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("type", "invite")
            .withExpiresAt(Date(System.currentTimeMillis() + expirationMs))
            .sign(algorithm)

    private fun verifier() = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun verifyAccessToken(token: String): TokenClaims? = runCatching {
        val decoded = verifier().verify(token)
        if (decoded.getClaim("type").asString() != "access") return null
        val rolesRaw = decoded.getClaim("roles").asList(String::class.java) ?: emptyList()
        val roles = rolesRaw.mapNotNull { runCatching { Role.valueOf(it) }.getOrNull() }.toSet()
        val roleVersion = decoded.getClaim("roleVersion").asInt() ?: 0
        TokenClaims(userId = decoded.subject, roles = roles, roleVersion = roleVersion)
    }.getOrElse { if (it is JWTVerificationException) null else throw it }

    fun verifyRefreshToken(token: String): String? = runCatching {
        val decoded = verifier().verify(token)
        if (decoded.getClaim("type").asString() != "refresh") return null
        decoded.subject
    }.getOrElse { if (it is JWTVerificationException) null else throw it }

    fun verifyMfaToken(token: String): String? = runCatching {
        val decoded = verifier().verify(token)
        if (decoded.getClaim("type").asString() != "mfa") return null
        decoded.subject
    }.getOrElse { if (it is JWTVerificationException) null else throw it }

    fun verifyInviteToken(token: String): String? = runCatching {
        val decoded = verifier().verify(token)
        if (decoded.getClaim("type").asString() != "invite") return null
        decoded.subject
    }.getOrElse { if (it is JWTVerificationException) null else throw it }

    companion object {
        private const val INVITE_TOKEN_EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

data class TokenClaims(val userId: String, val roles: Set<Role>, val roleVersion: Int = 0)
