package com.beettechnologies.posly.backup

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.db.OrdersTable
import com.beettechnologies.posly.db.ProductsTable
import com.beettechnologies.posly.db.ShiftsTable
import com.beettechnologies.posly.db.StoresTable
import com.beettechnologies.posly.db.TaxProfilesTable
import com.beettechnologies.posly.db.UsersTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

sealed class RestoreResult {
    data class Success(val drill: RestoreDrillResult) : RestoreResult()
    data object BackupNotFound : RestoreResult()
    data object SourceBackupNotUsable : RestoreResult()
    /** Refuses to restore into whatever database the app itself is currently running against - a destructive operation must only ever target a separate sandbox/DR database. */
    data object RefusedProductionTarget : RestoreResult()
}

private val CORE_TABLES: List<Table> = listOf(StoresTable, TaxProfilesTable, ProductsTable, UsersTable, OrdersTable, ShiftsTable)

/**
 * Restores a [BackupMetadata] artifact into an explicit sandbox/DR database and verifies data
 * integrity by comparing per-table row counts against the live source - the ticket's literal
 * suggested test ("restore from nightly backup into sandbox and verify data integrity").
 */
class RestoreService(
    private val backupService: BackupService,
    private val productionJdbcUrl: String,
    private val nowProvider: () -> Instant = { Instant.now() }
) {

    fun restore(backupId: String, targetJdbcUrl: String): RestoreResult {
        if (targetJdbcUrl == productionJdbcUrl) return RestoreResult.RefusedProductionTarget
        val backup = backupService.getBackup(backupId) ?: return RestoreResult.BackupNotFound
        if (backup.status != BackupStatus.SUCCESS) return RestoreResult.SourceBackupNotUsable

        val start = nowProvider()
        val sourceCounts = CORE_TABLES.associate { it.tableName to transaction { it.selectAll().count() } }

        var status = BackupStatus.SUCCESS
        var errorMessage: String? = null
        var targetCounts: Map<String, Long> = emptyMap()
        try {
            when {
                targetJdbcUrl.startsWith("jdbc:h2:") -> {
                    // TransactionManager.primaryDatabase falls back to "the last database
                    // Database.connect() registered" whenever no default has been set explicitly -
                    // which is exactly our case, since neither DatabaseFactory nor TestDatabase ever
                    // calls setDefaultDatabase. So connecting to the restore target here would
                    // permanently hijack every bare transaction{} in the process (both in tests and,
                    // more importantly, in the live app) unless we pin the default back explicitly
                    // afterward - restoring the old (nullable) defaultDatabase value is NOT enough,
                    // since that just puts us back to "falls back to last-connected", which is now
                    // the target.
                    val originalPrimary = TransactionManager.primaryDatabase
                    val targetDb = Database.connect(url = targetJdbcUrl, driver = "org.h2.Driver")
                    try {
                        // Unlike H2's SCRIPT command, RUNSCRIPT does not return a result set - it
                        // must be executed as an update, which is Exposed's default dispatch for
                        // an unrecognized statement keyword.
                        transaction(targetDb) {
                            exec("RUNSCRIPT FROM '${backup.filePath.replace("'", "''")}'")
                        }
                        targetCounts = CORE_TABLES.associate { it.tableName to transaction(targetDb) { it.selectAll().count() } }
                    } finally {
                        TransactionManager.defaultDatabase = originalPrimary
                    }
                }
                targetJdbcUrl.startsWith("jdbc:postgresql:") -> {
                    restorePostgres(targetJdbcUrl, backup.filePath)
                    // Row-count verification against a Postgres target would need a psql-based query per
                    // table - out of scope for this environment (no local Postgres to run it against);
                    // documented in DR_RUNBOOK.md as a manual verification step for a real drill.
                }
                else -> error("Unsupported database dialect for restore: $targetJdbcUrl")
            }
        } catch (e: Exception) {
            status = BackupStatus.FAILED
            errorMessage = e.message
        }

        val completed = nowProvider()
        val matched = status == BackupStatus.SUCCESS && targetCounts.isNotEmpty() && targetCounts == sourceCounts
        val drill = RestoreDrillResult(
            backupId = backupId,
            targetJdbcUrl = targetJdbcUrl,
            startedAt = start,
            completedAt = completed,
            durationMillis = completed.toEpochMilli() - start.toEpochMilli(),
            tableRowCounts = targetCounts,
            rowCountsMatched = matched,
            status = status,
            errorMessage = errorMessage
        )
        AuditService.record(
            if (status == BackupStatus.SUCCESS) AuditEvent.RESTORE_DRILL_COMPLETED else AuditEvent.RESTORE_DRILL_FAILED,
            detail = "backupId=$backupId target=$targetJdbcUrl rowCountsMatched=$matched durationMs=${drill.durationMillis}"
        )
        return RestoreResult.Success(drill)
    }

    private fun restorePostgres(targetJdbcUrl: String, scriptFilePath: String) {
        val connectionArgs = targetJdbcUrl.removePrefix("jdbc:")
        val process = ProcessBuilder("psql", connectionArgs, "-f", scriptFilePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) error("psql restore exited with code $exitCode: $output")
    }
}
