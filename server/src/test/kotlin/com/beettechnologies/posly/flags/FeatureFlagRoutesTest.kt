package com.beettechnologies.posly.flags

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig
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

class FeatureFlagRoutesTest {

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
    fun `admin can create, list, and update a feature flag`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val createResp = client.post("/feature-flags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"new-checkout","description":"New checkout flow"}""")
        }
        assertEquals(HttpStatusCode.Created, createResp.status)

        val listResp = client.get("/feature-flags") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
        assertTrue(listResp.bodyAsText().contains("new-checkout"))

        val updateResp = client.patch("/feature-flags/new-checkout") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":true,"rolloutPercentage":25}""")
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)
        val updated = Json.parseToJsonElement(updateResp.bodyAsText()).jsonObject
        assertEquals(true, updated["enabled"]?.jsonPrimitive?.boolean)
        assertEquals(25, updated["rolloutPercentage"]?.jsonPrimitive?.int)
    }

    @Test
    fun `creating a flag with a duplicate key returns 409`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)
        client.post("/feature-flags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"dup","description":"first"}""")
        }

        val resp = client.post("/feature-flags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"dup","description":"second"}""")
        }

        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `updating an unknown flag returns 404, an out-of-range percentage returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)
        client.post("/feature-flags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"range-flag","description":"d"}""")
        }

        val notFoundResp = client.patch("/feature-flags/does-not-exist") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":true}""")
        }
        assertEquals(HttpStatusCode.NotFound, notFoundResp.status)

        val badPercentResp = client.patch("/feature-flags/range-flag") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"rolloutPercentage":150}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badPercentResp.status)
    }

    @Test
    fun `cashier cannot create, list, update, or view the audit log`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = cashierToken(client)

        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/feature-flags") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"key":"x","description":"y"}""")
            }.status
        )
        assertEquals(HttpStatusCode.Forbidden, client.get("/feature-flags") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.patch("/feature-flags/whatever") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":true}""")
            }.status
        )
        assertEquals(HttpStatusCode.Forbidden, client.get("/feature-flags/audit-log") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    @Test
    fun `evaluate is reachable by a non-admin role and reflects the current rollout`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminAuth = adminToken(client)
        client.post("/feature-flags") {
            header(HttpHeaders.Authorization, "Bearer $adminAuth")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"evaluated-flag","description":"d","enabled":true,"rolloutPercentage":100}""")
        }

        val cashierAuth = cashierToken(client)
        val evalResp = client.get("/feature-flags/evaluated-flag/evaluate?storeId=store-1") {
            header(HttpHeaders.Authorization, "Bearer $cashierAuth")
        }

        assertEquals(HttpStatusCode.OK, evalResp.status)
        val body = Json.parseToJsonElement(evalResp.bodyAsText()).jsonObject
        assertEquals(true, body["enabled"]?.jsonPrimitive?.boolean)
        assertEquals("PERCENTAGE_ROLLOUT", body["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `evaluate without a storeId query parameter returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = cashierToken(client)

        val resp = client.get("/feature-flags/whatever/evaluate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `admin audit-log endpoint lists creation and update events`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)
        client.post("/feature-flags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"audit-flag","description":"d"}""")
        }
        client.patch("/feature-flags/audit-flag") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":true}""")
        }

        val resp = client.get("/feature-flags/audit-log") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val entries = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(2, entries.size)
        val events = entries.map { it.jsonObject["event"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("FEATURE_FLAG_CREATED", "FEATURE_FLAG_UPDATED"), events)
    }
}
