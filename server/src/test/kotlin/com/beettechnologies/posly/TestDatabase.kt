package com.beettechnologies.posly

import com.beettechnologies.posly.db.OrderEventsTable
import com.beettechnologies.posly.db.OrdersTable
import com.beettechnologies.posly.db.ProductImagesTable
import com.beettechnologies.posly.db.ProductsTable
import com.beettechnologies.posly.db.ShiftAuditEventsTable
import com.beettechnologies.posly.db.ShiftsTable
import com.beettechnologies.posly.db.StoresTable
import com.beettechnologies.posly.db.TaxProfilesTable
import com.beettechnologies.posly.db.UsersTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val TEST_JDBC_URL = "jdbc:h2:mem:posly-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"

/**
 * Docker on this machine can't run even a trivial container (`exec format error` on every image,
 * a broken Docker Desktop install unrelated to this codebase) - so Testcontainers-backed real
 * Postgres isn't viable here. Tests instead run against H2 in PostgreSQL-compatibility mode: same
 * schema/DSL code path as production, not a byte-identical Postgres engine. Real deployments point
 * `DATABASE_URL` at a real Postgres instance (see application.conf).
 *
 * The `init` block runs exactly once per test JVM (Kotlin `object` semantics) - every test file
 * that touches a migrated service should call [reset] (typically from a `@BeforeTest`/shared
 * harness) so each test starts from an empty database, mirroring how a fresh `ConcurrentHashMap`
 * per test used to behave.
 */
object TestDatabase {
    init {
        Database.connect(url = TEST_JDBC_URL, driver = "org.h2.Driver", user = "sa", password = "")
        transaction {
            SchemaUtils.create(
                StoresTable, TaxProfilesTable, ProductsTable, ProductImagesTable,
                UsersTable, OrdersTable, OrderEventsTable, ShiftsTable, ShiftAuditEventsTable
            )
        }
    }

    fun reset() {
        transaction {
            OrderEventsTable.deleteAll()
            OrdersTable.deleteAll()
            ShiftAuditEventsTable.deleteAll()
            ShiftsTable.deleteAll()
            ProductImagesTable.deleteAll()
            ProductsTable.deleteAll()
            UsersTable.deleteAll()
            TaxProfilesTable.deleteAll()
            StoresTable.deleteAll()
        }
    }
}

/**
 * The [io.ktor.server.config.MapApplicationConfig] entries every `*RoutesTest.kt` splices in:
 * `database.*` points `Application.module()`'s `DatabaseFactory.init` at the same H2 instance as
 * [TestDatabase]; `backup.directory` satisfies `Application.module()`'s unconditional read of that
 * property (used to construct `BackupService`) with a throwaway location under the build directory.
 */
object TestDatabaseConfig {
    val entries: Array<Pair<String, String>> = arrayOf(
        "database.jdbcUrl" to TEST_JDBC_URL,
        "database.username" to "sa",
        "database.password" to "",
        "database.maxPoolSize" to "5",
        "backup.directory" to "build/test-backups"
    )
}
