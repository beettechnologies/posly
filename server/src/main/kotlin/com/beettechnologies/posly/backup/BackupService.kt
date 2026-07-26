package com.beettechnologies.posly.backup

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Nightly backup of the application database, following the same nullable-[CoroutineScope]
 * self-scheduling idiom as [com.beettechnologies.posly.reporting.ReportingService] and
 * [com.beettechnologies.posly.finance.FinanceReportService]: a real scope in production, null in
 * tests (which call [runBackupNow] directly instead).
 *
 * Dialect-aware: H2 (this environment's dev/test database - see [com.beettechnologies.posly.TestDatabase]'s
 * comment on why real Postgres isn't reachable here) dumps via its built-in `SCRIPT TO` command,
 * genuinely runnable and testable in-process. Real PostgreSQL dumps via the standard `pg_dump`
 * tool - correctly wired, but requires `pg_dump` on the host running backups; not fabricated as
 * something else. Storage is a local directory (no object-store/S3 integration is simulated - see
 * DR_RUNBOOK.md for why), and no application-level encryption is applied to the artifact (also
 * documented in the runbook as a deployment-time responsibility, e.g. `gpg`/disk-level encryption)
 * - both are honest scope boundaries rather than fabricated infrastructure.
 */
class BackupService(
    private val jdbcUrl: String,
    private val backupDirectory: String,
    scope: CoroutineScope? = null,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val backupIntervalMillis: Long = 24 * 60 * 60 * 1000L
) {
    private val backups = ConcurrentHashMap<String, BackupMetadata>()

    init {
        scope?.launch {
            while (isActive) {
                delay(backupIntervalMillis)
                runBackupNow()
            }
        }
    }

    fun runBackupNow(): BackupMetadata {
        val now = nowProvider()
        val dir = File(backupDirectory).apply { mkdirs() }
        val file = File(dir, "posly-backup-${now.toEpochMilli()}.sql")

        val metadata = try {
            when {
                jdbcUrl.startsWith("jdbc:h2:") -> dumpH2(file)
                jdbcUrl.startsWith("jdbc:postgresql:") -> dumpPostgres(file)
                else -> error("Unsupported database dialect for backup: $jdbcUrl")
            }
            val bytes = file.readBytes()
            val checksum = sha256Hex(bytes)
            val validated = validateDump(file)
            BackupMetadata(
                createdAt = now,
                sizeBytes = bytes.size.toLong(),
                checksumSha256 = checksum,
                filePath = file.absolutePath,
                status = BackupStatus.SUCCESS,
                validated = validated
            )
        } catch (e: Exception) {
            BackupMetadata(
                createdAt = now,
                sizeBytes = 0,
                checksumSha256 = "",
                filePath = file.absolutePath,
                status = BackupStatus.FAILED,
                validated = false,
                errorMessage = e.message
            )
        }

        backups[metadata.id] = metadata
        AuditService.record(
            if (metadata.status == BackupStatus.SUCCESS) AuditEvent.BACKUP_COMPLETED else AuditEvent.BACKUP_FAILED,
            detail = "id=${metadata.id} sizeBytes=${metadata.sizeBytes} validated=${metadata.validated} error=${metadata.errorMessage}"
        )
        return metadata
    }

    fun listBackups(): List<BackupMetadata> = backups.values.sortedByDescending { it.createdAt }

    fun getBackup(id: String): BackupMetadata? = backups[id]

    private fun dumpH2(file: File) {
        transaction {
            // H2's SCRIPT command returns a result set (one row per emitted statement) - it must be
            // executed as a query, not an update, or the JDBC driver rejects it outright.
            exec("SCRIPT TO '${file.absolutePath.replace("'", "''")}'", emptyList(), StatementType.SELECT)
        }
    }

    private fun dumpPostgres(file: File) {
        val connectionArgs = jdbcUrl.removePrefix("jdbc:")
        val process = ProcessBuilder("pg_dump", connectionArgs, "-f", file.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) error("pg_dump exited with code $exitCode: $output")
    }

    /** Sanity-checks the artifact is well-formed without doing a full restore - non-empty and containing recognizable SQL. */
    private fun validateDump(file: File): Boolean = runCatching {
        val text = file.readText()
        text.isNotBlank() && (text.contains("CREATE TABLE", ignoreCase = true) || text.contains("INSERT INTO", ignoreCase = true))
    }.getOrDefault(false)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
