package com.beettechnologies.posly.audit

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.db.AuditTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditServiceTest {

    @BeforeTest
    fun resetDb() {
        TestDatabase.reset()
    }

    /** [AuditService.record] always stamps real [Instant.now], so tests simulate age by directly backdating the row afterward. */
    private fun backdate(recordId: String, timestamp: Instant) {
        transaction {
            AuditTable.update({ AuditTable.id eq recordId }) {
                it[AuditTable.timestamp] = timestamp
            }
        }
    }

    @Test
    fun `record persists and list retrieves username, userId, deviceId, correlationId, and detail`() {
        AuditService.record(
            AuditEvent.ORDER_CREATED,
            username = "cashier-1",
            userId = "user-1",
            deviceId = "device-1",
            correlationId = "corr-1",
            remoteIp = "10.0.0.1",
            detail = "orderId=order-1"
        )

        val records = AuditService.list()
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals(AuditEvent.ORDER_CREATED, record.event)
        assertEquals("cashier-1", record.username)
        assertEquals("user-1", record.userId)
        assertEquals("device-1", record.deviceId)
        assertEquals("corr-1", record.correlationId)
        assertEquals("10.0.0.1", record.remoteIp)
        assertEquals("orderId=order-1", record.detail)
    }

    @Test
    fun `list filters by username, event, and correlationId independently`() {
        AuditService.record(AuditEvent.ORDER_CREATED, username = "alice", correlationId = "corr-a")
        AuditService.record(AuditEvent.ORDER_REFUNDED, username = "alice", correlationId = "corr-b")
        AuditService.record(AuditEvent.ORDER_CREATED, username = "bob", correlationId = "corr-c")

        assertEquals(2, AuditService.list(username = "alice").size)
        assertEquals(2, AuditService.list(event = AuditEvent.ORDER_CREATED).size)
        assertEquals(1, AuditService.list(correlationId = "corr-b").size)
        assertEquals("bob", AuditService.list(correlationId = "corr-c").single().username)
    }

    @Test
    fun `list returns records newest first`() {
        AuditService.record(AuditEvent.ORDER_CREATED, detail = "first")
        AuditService.record(AuditEvent.ORDER_CREATED, detail = "second")

        val records = AuditService.list()
        assertEquals(listOf("second", "first"), records.map { it.detail })
    }

    @Test
    fun `purgeOlderThan removes only records past the cutoff and returns what it removed`() {
        AuditService.record(AuditEvent.ORDER_CREATED, detail = "old")
        val oldId = AuditService.list().single().id
        AuditService.record(AuditEvent.ORDER_CREATED, detail = "recent")

        val cutoff = Instant.parse("2026-01-01T00:00:00Z")
        backdate(oldId, cutoff.minusSeconds(60))

        val purged = AuditService.purgeOlderThan(cutoff)

        assertEquals(1, purged.size)
        assertEquals("old", purged.single().detail)
        val remaining = AuditService.list()
        assertEquals(1, remaining.size)
        assertEquals("recent", remaining.single().detail)
    }

    @Test
    fun `purgeOlderThan is a no-op when nothing is past the cutoff`() {
        AuditService.record(AuditEvent.ORDER_CREATED, detail = "recent")

        val purged = AuditService.purgeOlderThan(Instant.parse("2020-01-01T00:00:00Z"))

        assertTrue(purged.isEmpty())
        assertEquals(1, AuditService.list().size)
    }

    @Test
    fun `clearForTests removes all records`() {
        AuditService.record(AuditEvent.ORDER_CREATED)
        AuditService.clearForTests()

        assertNull(AuditService.list().firstOrNull())
    }
}
