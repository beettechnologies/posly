package com.beettechnologies.posly.audit

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig
import com.beettechnologies.posly.db.AuditTable
import com.beettechnologies.posly.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.test.*

class AuditRoutesTest {

    private fun ApplicationTestBuilder.configureApp() {
        TestDatabase.reset()
        environment {
            config = MapApplicationConfig(
                *TestDatabaseConfig.entries,
                "jwt.secret" to "test-secret-at-least-32-characters-long!!",
                "jwt.issuer" to "posly",
                "jwt.audience" to "posly-api",
                "jwt.accessTokenExpirationMs" to "900000",
                "jwt.refreshTokenExpirationMs" to "604800000",
                "jwt.mfaTokenExpirationMs" to "300000",
                "payments.webhookSecret" to "test-webhook-secret"
            )
        }
        application { module() }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ContentNegotiation) { json() } }

    private suspend fun adminToken(client: HttpClient): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin123"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun cashierToken(client: HttpClient): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"cashier","password":"cashier123"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private fun backdate(recordId: String, timestamp: Instant) {
        transaction {
            AuditTable.update({ AuditTable.id eq recordId }) {
                it[AuditTable.timestamp] = timestamp
            }
        }
    }

    @Test
    fun `admin audit-log endpoint filters by correlationId`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        AuditService.record(AuditEvent.ORDER_CREATED, correlationId = "corr-match", detail = "orderId=order-1")
        AuditService.record(AuditEvent.ORDER_CREATED, correlationId = "corr-other", detail = "orderId=order-2")

        val resp = client.get("/ops/audit-log?correlationId=corr-match") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val entries = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(1, entries.size)
        assertEquals("orderId=order-1", entries.single().jsonObject["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `admin audit-log endpoint filters by event and username`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        AuditService.record(AuditEvent.ORDER_REFUNDED, username = "alice", detail = "refund-1")
        AuditService.record(AuditEvent.ORDER_CREATED, username = "alice", detail = "order-1")

        val resp = client.get("/ops/audit-log?event=ORDER_REFUNDED&username=alice") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val entries = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(1, entries.size)
        assertEquals("refund-1", entries.single().jsonObject["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cashier cannot view the audit log or trigger retention`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = cashierToken(client)

        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/ops/audit-log") { header(HttpHeaders.Authorization, "Bearer $token") }.status
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/ops/audit/retention/run-now") { header(HttpHeaders.Authorization, "Bearer $token") }.status
        )
    }

    @Test
    fun `admin can trigger retention run-now which archives only records past the cutoff`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        AuditService.record(AuditEvent.ORDER_CREATED, detail = "orderId=stale-order")
        val staleId = AuditService.list().single { it.detail == "orderId=stale-order" }.id
        backdate(staleId, Instant.now().minusSeconds(200L * 24 * 60 * 60))

        val resp = client.post("/ops/audit/retention/run-now") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(1, body["purgedCount"]?.jsonPrimitive?.int)
        assertNotNull(body["archiveFilePath"]?.jsonPrimitive?.content)

        val remaining = AuditService.list()
        assertTrue(remaining.none { it.detail == "orderId=stale-order" })
    }
}
