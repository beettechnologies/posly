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
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PRODUCTION_URL_FOR_TESTS = "jdbc:h2:mem:posly-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"

/**
 * The ticket's literal suggested test: "Restore from nightly backup into sandbox and verify data
 * integrity." Runs the real backup -> restore -> row-count-comparison sequence end to end against
 * H2 (see [TestDatabase]'s comment on why real Postgres isn't reachable in this environment) - a
 * genuine DR drill, not a mocked approximation of one.
 */
class RestoreServiceTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        TestDatabase.reset()
        tempDir = createTempDirectory("posly-dr-drill").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `DR drill - backup then restore into a sandbox target and verify row counts match`() {
        val stores = StoreService(TaxProfileService())
        repeat(3) { i ->
            stores.createStore(
                "Store $i",
                Address(line1 = "1 Main St", city = "NY", postalCode = "10001", country = "US"),
                "America/New_York", "USD", null
            )
        }

        val backupService = BackupService(jdbcUrl = "jdbc:h2:mem:ignored-for-dispatch-only", backupDirectory = tempDir.absolutePath)
        val backup = backupService.runBackupNow()
        assertEquals(BackupStatus.SUCCESS, backup.status)

        val restoreService = RestoreService(backupService, productionJdbcUrl = PRODUCTION_URL_FOR_TESTS)
        val targetUrl = "jdbc:h2:mem:posly-dr-drill-target;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"

        val result = restoreService.restore(backup.id, targetUrl)

        val success = assertIs<RestoreResult.Success>(result)
        assertEquals(BackupStatus.SUCCESS, success.drill.status)
        assertTrue(success.drill.rowCountsMatched, "restored row counts should match the source: ${success.drill.tableRowCounts}")
        assertEquals(3L, success.drill.tableRowCounts["stores"])
        assertTrue(success.drill.durationMillis >= 0)
    }

    @Test
    fun `restore refuses to target the application's own live database`() {
        val backupService = BackupService(jdbcUrl = "jdbc:h2:mem:ignored-for-dispatch-only", backupDirectory = tempDir.absolutePath)
        val backup = backupService.runBackupNow()
        val restoreService = RestoreService(backupService, productionJdbcUrl = PRODUCTION_URL_FOR_TESTS)

        val result = restoreService.restore(backup.id, PRODUCTION_URL_FOR_TESTS)

        assertEquals(RestoreResult.RefusedProductionTarget, result)
    }

    @Test
    fun `restoring an unknown backup id returns BackupNotFound`() {
        val backupService = BackupService(jdbcUrl = "jdbc:h2:mem:ignored-for-dispatch-only", backupDirectory = tempDir.absolutePath)
        val restoreService = RestoreService(backupService, productionJdbcUrl = PRODUCTION_URL_FOR_TESTS)

        val result = restoreService.restore("does-not-exist", "jdbc:h2:mem:posly-dr-drill-target2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")

        assertEquals(RestoreResult.BackupNotFound, result)
    }

    @Test
    fun `restoring a failed backup is refused`() {
        val backupService = BackupService(jdbcUrl = "jdbc:sqlite:whatever", backupDirectory = tempDir.absolutePath)
        val failedBackup = backupService.runBackupNow()
        assertEquals(BackupStatus.FAILED, failedBackup.status)
        val restoreService = RestoreService(backupService, productionJdbcUrl = PRODUCTION_URL_FOR_TESTS)

        val result = restoreService.restore(failedBackup.id, "jdbc:h2:mem:posly-dr-drill-target3;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")

        assertEquals(RestoreResult.SourceBackupNotUsable, result)
    }
}
