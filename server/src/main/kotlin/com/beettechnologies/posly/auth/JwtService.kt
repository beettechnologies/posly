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

    fun generateAccessToken(userId: String, roles: Set<Role>): String =
        JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("roles", roles.map { it.name })
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

    private fun verifier() = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun verifyAccessToken(token: String): TokenClaims? = runCatching {
        val decoded = verifier().verify(token)
        if (decoded.getClaim("type").asString() != "access") return null
        val rolesRaw = decoded.getClaim("roles").asList(String::class.java) ?: emptyList()
        val roles = rolesRaw.mapNotNull { runCatching { Role.valueOf(it) }.getOrNull() }.toSet()
        TokenClaims(userId = decoded.subject, roles = roles)
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
}

data class TokenClaims(val userId: String, val roles: Set<Role>)
