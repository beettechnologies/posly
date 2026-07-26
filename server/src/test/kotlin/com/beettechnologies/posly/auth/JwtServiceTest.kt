package com.beettechnologies.posly.auth

import com.auth0.jwt.JWT
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.secrets.InMemorySecretsManager
import com.beettechnologies.posly.secrets.SecretName
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class TestClock(var instant: Instant)

private fun newJwtService(clock: TestClock, gracePeriodMs: Long = 3_600_000L): Pair<JwtService, InMemorySecretsManager> {
    val secretsManager = InMemorySecretsManager(
        mapOf(
            SecretName.JWT_SIGNING_KEY to "initial-jwt-secret-at-least-32-chars!!",
            SecretName.PAYMENT_WEBHOOK_SECRET to "unused-in-these-tests"
        ),
        gracePeriodMs = gracePeriodMs,
        nowProvider = { clock.instant }
    )
    val jwtService = JwtService(
        secretsManager,
        issuer = "posly",
        audience = "posly-api",
        accessTokenExpirationMs = 900_000L,
        refreshTokenExpirationMs = 604_800_000L,
        mfaTokenExpirationMs = 300_000L
    )
    return jwtService to secretsManager
}

class JwtServiceTest {

    @Test
    fun `generateAccessToken embeds the signing secret's version id as the JWT kid`() {
        val (jwtService, secretsManager) = newJwtService(TestClock(Instant.parse("2026-01-01T00:00:00Z")))

        val token = jwtService.generateAccessToken("user-1", setOf(Role.CASHIER), roleVersion = 0)

        assertEquals(secretsManager.current(SecretName.JWT_SIGNING_KEY).id, JWT.decode(token).keyId)
    }

    @Test
    fun `verifyAccessToken round-trips userId, roles, and roleVersion`() {
        val (jwtService, _) = newJwtService(TestClock(Instant.parse("2026-01-01T00:00:00Z")))

        val token = jwtService.generateAccessToken("user-1", setOf(Role.MANAGER, Role.CASHIER), roleVersion = 3)
        val claims = jwtService.verifyAccessToken(token)

        assertNotNull(claims)
        assertEquals("user-1", claims.userId)
        assertEquals(setOf(Role.MANAGER, Role.CASHIER), claims.roles)
        assertEquals(3, claims.roleVersion)
    }

    @Test
    fun `verifyAccessToken rejects a token of a different claimed type`() {
        val (jwtService, _) = newJwtService(TestClock(Instant.parse("2026-01-01T00:00:00Z")))

        val refreshToken = jwtService.generateRefreshToken("user-1")

        assertNull(jwtService.verifyAccessToken(refreshToken))
    }

    @Test
    fun `secret rotation dry-run - a token signed before rotation still verifies during the grace period`() {
        val clock = TestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val (jwtService, secretsManager) = newJwtService(clock, gracePeriodMs = 3_600_000L)

        val tokenSignedBeforeRotation = jwtService.generateAccessToken("user-1", setOf(Role.CASHIER), roleVersion = 0)
        secretsManager.rotate(SecretName.JWT_SIGNING_KEY, actorUserId = "admin-1")

        // Zero downtime: the token issued under the old secret still authenticates immediately
        // after rotation, and a freshly-issued token uses the new secret/kid straight away.
        val claims = jwtService.verifyAccessToken(tokenSignedBeforeRotation)
        assertNotNull(claims)
        assertEquals("user-1", claims.userId)

        val tokenSignedAfterRotation = jwtService.generateAccessToken("user-2", setOf(Role.ADMIN), roleVersion = 0)
        assertNotEquals(JWT.decode(tokenSignedBeforeRotation).keyId, JWT.decode(tokenSignedAfterRotation).keyId)
        assertNotNull(jwtService.verifyAccessToken(tokenSignedAfterRotation))
    }

    @Test
    fun `secret rotation dry-run - a token signed before rotation fails once the grace period elapses`() {
        val clock = TestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val (jwtService, secretsManager) = newJwtService(clock, gracePeriodMs = 3_600_000L)

        val tokenSignedBeforeRotation = jwtService.generateAccessToken("user-1", setOf(Role.CASHIER), roleVersion = 0)
        secretsManager.rotate(SecretName.JWT_SIGNING_KEY, actorUserId = "admin-1")
        clock.instant = clock.instant.plusMillis(3_600_001L)

        assertNull(jwtService.verifyAccessToken(tokenSignedBeforeRotation))
    }
}
