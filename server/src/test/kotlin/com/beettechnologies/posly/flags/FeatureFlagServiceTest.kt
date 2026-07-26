package com.beettechnologies.posly.flags

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureFlagServiceTest {

    @BeforeTest
    fun resetDb() {
        TestDatabase.reset()
        AuditService.clearForTests()
    }

    @Test
    fun `createFlag persists a flag with the given defaults`() {
        val service = FeatureFlagService()

        val result = service.createFlag("new-receipt-layout", "Redesigned receipt layout")

        val created = assertIs<CreateFlagResult.Success>(result).flag
        assertEquals("new-receipt-layout", created.key)
        assertFalse(created.enabled)
        assertEquals(0, created.rolloutPercentage)
        assertEquals(created, service.getFlag("new-receipt-layout"))
    }

    @Test
    fun `createFlag rejects a duplicate key`() {
        val service = FeatureFlagService()
        service.createFlag("dup-key", "First")

        val result = service.createFlag("dup-key", "Second")

        assertEquals(CreateFlagResult.DuplicateKey, result)
        assertEquals(1, service.listFlags().size)
    }

    @Test
    fun `createFlag records a FEATURE_FLAG_CREATED audit event`() {
        val service = FeatureFlagService()

        service.createFlag("audited-flag", "desc", enabled = true, rolloutPercentage = 25)

        val entries = AuditService.list(event = AuditEvent.FEATURE_FLAG_CREATED)
        assertEquals(1, entries.size)
        assertTrue(entries.single().detail!!.contains("key=audited-flag"))
    }

    @Test
    fun `updateFlag changes fields and records a diff in the audit detail`() {
        val service = FeatureFlagService()
        service.createFlag("checkout-v2", "desc")

        val result = service.updateFlag("checkout-v2", enabled = true, rolloutPercentage = 50, actorUserId = "admin-1")

        val updated = assertIs<UpdateFlagResult.Updated>(result).flag
        assertTrue(updated.enabled)
        assertEquals(50, updated.rolloutPercentage)
        val entry = AuditService.list(event = AuditEvent.FEATURE_FLAG_UPDATED).single()
        assertEquals("admin-1", entry.userId)
        assertTrue(entry.detail!!.contains("enabled: false->true"))
        assertTrue(entry.detail!!.contains("rolloutPercentage: 0->50"))
    }

    @Test
    fun `updateFlag on an unknown key returns NotFound`() {
        val service = FeatureFlagService()

        val result = service.updateFlag("does-not-exist", enabled = true)

        assertEquals(UpdateFlagResult.NotFound, result)
    }

    @Test
    fun `updateFlag rejects a rollout percentage outside 0 to 100`() {
        val service = FeatureFlagService()
        service.createFlag("range-check", "desc")

        assertEquals(UpdateFlagResult.InvalidPercentage, service.updateFlag("range-check", rolloutPercentage = 101))
        assertEquals(UpdateFlagResult.InvalidPercentage, service.updateFlag("range-check", rolloutPercentage = -1))
    }

    @Test
    fun `evaluate returns disabled with FLAG_NOT_FOUND for an unknown key`() {
        val service = FeatureFlagService()

        val evaluation = service.evaluate("unknown", "store-1")

        assertFalse(evaluation.enabled)
        assertEquals(EvaluationReason.FLAG_NOT_FOUND, evaluation.reason)
    }

    @Test
    fun `the kill switch overrides both store overrides and rollout percentage`() {
        val service = FeatureFlagService()
        service.createFlag("kill-switch-test", "desc", enabled = false, rolloutPercentage = 100)
        service.updateFlag("kill-switch-test", enabledStoreIds = setOf("store-1"))

        val evaluation = service.evaluate("kill-switch-test", "store-1")

        assertFalse(evaluation.enabled)
        assertEquals(EvaluationReason.KILL_SWITCH_OFF, evaluation.reason)
    }

    @Test
    fun `a store override always wins over the rollout percentage while the flag is on`() {
        val service = FeatureFlagService()
        service.createFlag("store-override-test", "desc", enabled = true, rolloutPercentage = 0)
        service.updateFlag("store-override-test", enabledStoreIds = setOf("store-42"))

        val evaluation = service.evaluate("store-override-test", "store-42")

        assertTrue(evaluation.enabled)
        assertEquals(EvaluationReason.STORE_OVERRIDE, evaluation.reason)
    }

    @Test
    fun `percentage bucketing is deterministic for the same flag and store`() {
        val service = FeatureFlagService()
        service.createFlag("bucket-determinism", "desc", enabled = true, rolloutPercentage = 37)

        val first = service.evaluate("bucket-determinism", "store-777")
        val second = service.evaluate("bucket-determinism", "store-777")

        assertEquals(first.enabled, second.enabled)
        assertEquals(EvaluationReason.PERCENTAGE_ROLLOUT, first.reason)
    }

    @Test
    fun `percentage bucketing is roughly proportional across many distinct stores`() {
        val service = FeatureFlagService()
        service.createFlag("proportional-rollout", "desc", enabled = true, rolloutPercentage = 10)

        val storeIds = (1..2000).map { "store-$it" }
        val onCount = storeIds.count { service.evaluate("proportional-rollout", it).enabled }

        // Expect ~200 of 2000 at 10% - allow a generous tolerance since this is a hash bucket, not a precise draw.
        assertTrue(onCount in 150..250, "expected roughly 200 stores enabled at 10%, got $onCount")
    }

    @Test
    fun `toggling enabled takes effect on the very next evaluation with no caching`() {
        val service = FeatureFlagService()
        service.createFlag("immediate-effect", "desc", enabled = true, rolloutPercentage = 100)
        assertTrue(service.evaluate("immediate-effect", "store-1").enabled)

        service.updateFlag("immediate-effect", enabled = false)

        assertFalse(service.evaluate("immediate-effect", "store-1").enabled)
    }

    @Test
    fun `evaluate increments a metrics counter tagged by flag key and ON or OFF result`() {
        val registry = SimpleMeterRegistry()
        val service = FeatureFlagService(registry)
        service.createFlag("metered-flag", "desc", enabled = true, rolloutPercentage = 100)
        service.createFlag("metered-flag-off", "desc", enabled = false)

        service.evaluate("metered-flag", "store-1")
        service.evaluate("metered-flag-off", "store-1")

        assertEquals(1.0, registry.get("feature_flag_evaluations").tags("flag", "metered-flag", "result", "ON").counter().count())
        assertEquals(1.0, registry.get("feature_flag_evaluations").tags("flag", "metered-flag-off", "result", "OFF").counter().count())
    }
}
