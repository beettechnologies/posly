package com.beettechnologies.posly.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.ApplicationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Wires the single, process-wide Exposed [Database] connection - once connected, every
 * `transaction { }` block anywhere in the process picks it up automatically, so none of the
 * migrated services need a `Database`/`DataSource` constructor parameter. Mirrors how JWT config
 * is read in Application.kt (`environment.config.config("...")`).
 */
object DatabaseFactory {
    fun init(config: ApplicationConfig) {
        val jdbcUrl = config.property("jdbcUrl").getString()
        val username = config.propertyOrNull("username")?.getString() ?: ""
        val password = config.propertyOrNull("password")?.getString() ?: ""
        val maxPoolSize = config.propertyOrNull("maxPoolSize")?.getString()?.toInt() ?: 10

        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            this.maximumPoolSize = maxPoolSize
        }
        val dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.create(
                StoresTable,
                TaxProfilesTable,
                ProductsTable,
                ProductImagesTable,
                UsersTable,
                OrdersTable,
                OrderEventsTable,
                ShiftsTable,
                ShiftAuditEventsTable,
                FeatureFlagsTable,
                AuditTable
            )
        }
    }
}
