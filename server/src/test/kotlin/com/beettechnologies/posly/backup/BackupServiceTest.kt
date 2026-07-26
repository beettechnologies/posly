package com.beettechnologies.posly.backup

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupServiceTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        TestDatabase.reset()
        tempDir = createTempDirectory("posly-backup-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `runBackupNow against H2 produces a validated backup with metadata and a sha-256 checksum`() {
        val stores = StoreService(TaxProfileService())
        stores.createStore("Downtown", Address(line1 = "1 Main St", city = "NY", postalCode = "10001", country = "US"), "America/New_York", "USD", null)

        val service = BackupService(jdbcUrl = "jdbc:h2:mem:ignored-for-dispatch-only", backupDirectory = tempDir.absolutePath)
        val metadata = service.runBackupNow()

        assertEquals(BackupStatus.SUCCESS, metadata.status)
        assertTrue(metadata.validated, "a real dump of a non-empty database should validate")
        assertTrue(metadata.sizeBytes > 0)
        assertEquals(64, metadata.checksumSha256.length, "sha-256 hex digest is 64 characters")
        assertTrue(File(metadata.filePath).exists())
        val dumpText = File(metadata.filePath).readText()
        assertTrue(dumpText.contains("TABLE", ignoreCase = true) && dumpText.contains("STORES", ignoreCase = true))
    }

    @Test
    fun `an unsupported jdbc dialect fails the backup with no checksum`() {
        val service = BackupService(jdbcUrl = "jdbc:sqlite:whatever", backupDirectory = tempDir.absolutePath)

        val metadata = service.runBackupNow()

        assertEquals(BackupStatus.FAILED, metadata.status)
        assertFalse(metadata.validated)
        assertEquals("", metadata.checksumSha256)
        assertTrue(metadata.errorMessage?.contains("Unsupported") == true)
    }

    @Test
    fun `listBackups returns every run, newest first`() {
        val service = BackupService(jdbcUrl = "jdbc:h2:mem:ignored-for-dispatch-only", backupDirectory = tempDir.absolutePath)

        val first = service.runBackupNow()
        val second = service.runBackupNow()

        val listed = service.listBackups()
        assertEquals(listOf(second.id, first.id), listed.map { it.id })
    }

    @Test
    fun `getBackup finds a run by id and returns null for an unknown one`() {
        val service = BackupService(jdbcUrl = "jdbc:h2:mem:ignored-for-dispatch-only", backupDirectory = tempDir.absolutePath)
        val metadata = service.runBackupNow()

        assertEquals(metadata, service.getBackup(metadata.id))
        assertEquals(null, service.getBackup("does-not-exist"))
    }
}
