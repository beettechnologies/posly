package com.beettechnologies.posly.audit

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.db.AuditTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditRetentionServiceTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        TestDatabase.reset()
        tempDir = createTempDirectory("posly-audit-archive-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun backdate(recordId: String, timestamp: Instant) {
        transaction {
            AuditTable.update({ AuditTable.id eq recordId }) {
                it[AuditTable.timestamp] = timestamp
            }
        }
    }

    @Test
    fun `runRetentionNow archives and purges only records older than the cutoff`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        AuditService.record(AuditEvent.ORDER_CREATED, detail = "orderId=old-order")
        val oldId = AuditService.list().single().id
        backdate(oldId, now.minusSeconds(200 * 24 * 60 * 60))

        AuditService.record(AuditEvent.ORDER_CREATED, detail = "orderId=recent-order")

        val service = AuditRetentionService(
            archiveDirectory = tempDir.absolutePath,
            nowProvider = { now },
            retentionDays = 90
        )
        val result = service.runRetentionNow()

        assertEquals(1, result.purgedCount)
        val archiveFilePath = assertNotNull(result.archiveFilePath)
        val archiveFile = File(archiveFilePath)
        assertTrue(archiveFile.exists())
        val archivedLine = archiveFile.readText().trim()
        assertTrue(archivedLine.contains("orderId=old-order"))
        assertFalse(archivedLine.contains("orderId=recent-order"))

        val remainingDetails = AuditService.list().map { it.detail }
        assertTrue("orderId=recent-order" in remainingDetails)
        assertTrue("orderId=old-order" !in remainingDetails)
    }

    @Test
    fun `runRetentionNow writes no archive file when nothing is purged`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        AuditService.record(AuditEvent.ORDER_CREATED, detail = "recent")

        val service = AuditRetentionService(archiveDirectory = tempDir.absolutePath, nowProvider = { now }, retentionDays = 90)
        val result = service.runRetentionNow()

        assertEquals(0, result.purgedCount)
        assertNull(result.archiveFilePath)
        assertTrue(tempDir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun `runRetentionNow records its own completion audit event without self-purging`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        AuditService.record(AuditEvent.ORDER_CREATED, detail = "orderId=old-order")
        val oldId = AuditService.list().single().id
        backdate(oldId, now.minusSeconds(200 * 24 * 60 * 60))

        val service = AuditRetentionService(archiveDirectory = tempDir.absolutePath, nowProvider = { now }, retentionDays = 90)
        service.runRetentionNow()

        val completionEvents = AuditService.list(event = AuditEvent.AUDIT_RETENTION_COMPLETED)
        assertEquals(1, completionEvents.size)
        assertTrue(completionEvents.single().detail?.contains("purgedCount=1") == true)
    }
}
