package com.beettechnologies.posly.secrets

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig
import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService

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
import kotlin.test.*

class SecretsRoutesTest {

    private fun ApplicationTestBuilder.configureApp() {
        TestDatabase.reset()
        AuditService.clearForTests()
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

    @Test
    fun `admin can list secrets and no raw value is ever serialized`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val resp = client.get("/ops/secrets") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("jwt-signing-key"))
        assertTrue(body.contains("payment-webhook-secret"))
        assertFalse(body.contains("newValue"), "the listing endpoint must never carry a raw secret value")
    }

    @Test
    fun `cashier cannot list or rotate secrets`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = cashierToken(client)

        val listResp = client.get("/ops/secrets") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, listResp.status)

        val rotateResp = client.post("/ops/secrets/jwt-signing-key/rotate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, rotateResp.status)
    }

    @Test
    fun `rotating a secret returns its new value once and audit-logs the rotation`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val rotateResp = client.post("/ops/secrets/jwt-signing-key/rotate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, rotateResp.status)
        val rotated = Json.parseToJsonElement(rotateResp.bodyAsText()).jsonObject
        assertEquals("jwt-signing-key", rotated["secretName"]?.jsonPrimitive?.content)
        assertTrue(rotated["newValue"]!!.jsonPrimitive.content.isNotBlank())

        val history = AuditService.list(event = AuditEvent.SECRET_ROTATED)
        assertEquals(1, history.size)
        assertTrue(history.single().detail!!.contains("JWT_SIGNING_KEY"))
    }

    @Test
    fun `rotating an unknown secret name returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val resp = client.post("/ops/secrets/not-a-real-secret/rotate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `rotating jwt-signing-key does not affect the payment-webhook-secret's history`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        client.post("/ops/secrets/jwt-signing-key/rotate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val listResp = client.get("/ops/secrets") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val summaries = Json.parseToJsonElement(listResp.bodyAsText()).jsonArray
        val webhookSummary = summaries.first { it.jsonObject["name"]!!.jsonPrimitive.content == "payment-webhook-secret" }
        assertEquals(1, webhookSummary.jsonObject["history"]!!.jsonArray.size)
    }
}
