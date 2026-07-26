package com.beettechnologies.posly.secrets

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TestClock(var instant: Instant)

private const val INITIAL_JWT_SECRET = "initial-jwt-secret"
private const val INITIAL_WEBHOOK_SECRET = "initial-webhook-secret"

private fun newManager(clock: TestClock, gracePeriodMs: Long = 3_600_000L): InMemorySecretsManager =
    InMemorySecretsManager(
        mapOf(SecretName.JWT_SIGNING_KEY to INITIAL_JWT_SECRET, SecretName.PAYMENT_WEBHOOK_SECRET to INITIAL_WEBHOOK_SECRET),
        gracePeriodMs = gracePeriodMs,
        nowProvider = { clock.instant }
    )

class SecretsManagerTest {

    @Test
    fun `current returns the config-seeded value before any rotation`() {
        val manager = newManager(TestClock(Instant.parse("2026-01-01T00:00:00Z")))

        assertEquals(INITIAL_JWT_SECRET, manager.current(SecretName.JWT_SIGNING_KEY).value)
        assertEquals(INITIAL_WEBHOOK_SECRET, manager.current(SecretName.PAYMENT_WEBHOOK_SECRET).value)
        assertNull(manager.current(SecretName.JWT_SIGNING_KEY).validUntil)
    }

    @Test
    fun `rotate generates a new current version and returns its raw value`() {
        val manager = newManager(TestClock(Instant.parse("2026-01-01T00:00:00Z")))

        val rotated = manager.rotate(SecretName.JWT_SIGNING_KEY, actorUserId = "admin-1")

        assertNull(rotated.validUntil)
        assertNotEquals(INITIAL_JWT_SECRET, rotated.value)
        assertEquals(rotated.value, manager.current(SecretName.JWT_SIGNING_KEY).value)
        assertEquals(rotated.id, manager.current(SecretName.JWT_SIGNING_KEY).id)
    }

    @Test
    fun `validVersions includes the previous version during its grace period`() {
        val clock = TestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val manager = newManager(clock, gracePeriodMs = 3_600_000L)
        val originalValue = manager.current(SecretName.JWT_SIGNING_KEY).value

        manager.rotate(SecretName.JWT_SIGNING_KEY, actorUserId = null)

        val validValues = manager.validVersions(SecretName.JWT_SIGNING_KEY).map { it.value }
        assertTrue(originalValue in validValues, "the just-superseded version should still verify during its grace period")
        assertEquals(2, validValues.size)
    }

    @Test
    fun `validVersions excludes a previous version once its grace period has elapsed`() {
        val clock = TestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val manager = newManager(clock, gracePeriodMs = 3_600_000L)
        val originalValue = manager.current(SecretName.JWT_SIGNING_KEY).value

        manager.rotate(SecretName.JWT_SIGNING_KEY, actorUserId = null)
        clock.instant = clock.instant.plusMillis(3_600_001L)

        val validValues = manager.validVersions(SecretName.JWT_SIGNING_KEY).map { it.value }
        assertTrue(originalValue !in validValues, "a superseded version should stop verifying once its grace period elapses")
        assertEquals(1, validValues.size)
    }

    @Test
    fun `rotating one secret name never affects the other`() {
        val manager = newManager(TestClock(Instant.parse("2026-01-01T00:00:00Z")))

        manager.rotate(SecretName.JWT_SIGNING_KEY, actorUserId = null)

        assertEquals(INITIAL_WEBHOOK_SECRET, manager.current(SecretName.PAYMENT_WEBHOOK_SECRET).value)
        assertEquals(1, manager.history(SecretName.PAYMENT_WEBHOOK_SECRET).size)
    }

    @Test
    fun `history reports status transitions and never exposes raw values`() {
        val clock = TestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val manager = newManager(clock, gracePeriodMs = 1_000L)

        manager.rotate(SecretName.JWT_SIGNING_KEY, actorUserId = null)
        clock.instant = clock.instant.plusMillis(1_001L)

        val history = manager.history(SecretName.JWT_SIGNING_KEY)
        assertEquals(2, history.size)
        assertEquals(SecretVersionStatus.EXPIRED, history.first { it.validUntil != null }.status)
        assertEquals(SecretVersionStatus.CURRENT, history.first { it.validUntil == null }.status)
        // SecretVersionSummary has no `value` field at all - the compiler itself enforces this,
        // this assertion just documents the intent for anyone reading the test.
        assertTrue(history.all { it.id.isNotBlank() })
    }
}
