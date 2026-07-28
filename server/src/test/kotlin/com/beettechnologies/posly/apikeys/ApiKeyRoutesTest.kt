package com.beettechnologies.posly.apikeys

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig
import com.beettechnologies.posly.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiKeyRoutesTest {

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

    private suspend fun accessToken(client: HttpClient, username: String, password: String): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun seedStoreId(client: HttpClient, token: String): String {
        val resp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"Downtown","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},
                    |"timezone":"America/New_York","currency":"USD"}""".trimMargin()
            )
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun seedProductSku(client: HttpClient, token: String, sku: String) {
        client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"$sku","name":"Widget","price":10.0,"taxCategory":"STANDARD"}""")
        }
    }

    private suspend fun createApiKey(client: HttpClient, adminToken: String, scopes: List<String>, name: String = "Integration"): Pair<String, String> {
        val resp = client.post("/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","scopes":${scopes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val id = body["apiKey"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val rawKey = body["rawKey"]!!.jsonPrimitive.content
        return id to rawKey
    }

    // -------------------------------------------------------------------------
    // Admin CRUD
    // -------------------------------------------------------------------------

    @Test
    fun `creating a key returns the secret exactly once and never again from list`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")

        val createResp = client.post("/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Accounting integration","scopes":["ORDERS_READ"]}""")
        }
        assertEquals(HttpStatusCode.Created, createResp.status)
        val created = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject
        val rawKey = created["rawKey"]!!.jsonPrimitive.content
        assertTrue(rawKey.startsWith("posly_"))

        val listResp = client.get("/api-keys") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val listBody = Json.parseToJsonElement(listResp.bodyAsText()).jsonArray.single().jsonObject
        assertEquals("Accounting integration", listBody["name"]!!.jsonPrimitive.content)
        assertTrue("rawKey" !in listBody, "the list response must never include the raw secret")
        assertTrue("secretHash" !in listBody, "the list response must never include the secret hash either")
    }

    @Test
    fun `a non-admin cannot create an API key`() = testApplication {
        configureApp()
        val client = jsonClient()
        val managerToken = accessToken(client, "manager", "manager123")

        val resp = client.post("/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $managerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"x","scopes":["ORDERS_READ"]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `creating a key with an unknown scope returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")

        val resp = client.post("/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"x","scopes":["NOT_A_REAL_SCOPE"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // -------------------------------------------------------------------------
    // Using a key for a real API call, revocation, and rotation
    // -------------------------------------------------------------------------

    @Test
    fun `an API key with ORDERS_READ can call GET orders, and stops working once revoked`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminToken)
        val (keyId, rawKey) = createApiKey(client, adminToken, listOf("ORDERS_READ"))

        val beforeRevoke = client.get("/orders?storeId=$storeId&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $rawKey")
        }
        assertEquals(HttpStatusCode.OK, beforeRevoke.status)

        val revokeResp = client.post("/api-keys/$keyId/revoke") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        assertEquals(HttpStatusCode.OK, revokeResp.status)

        val afterRevoke = client.get("/orders?storeId=$storeId&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $rawKey")
        }
        assertEquals(HttpStatusCode.Unauthorized, afterRevoke.status, "a revoked key must be rejected, not silently accepted")
    }

    @Test
    fun `an API key without the required scope is forbidden, even though it authenticates`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminToken)
        val (_, rawKey) = createApiKey(client, adminToken, listOf("PRODUCTS_READ")) // no ORDERS_READ

        val resp = client.get("/orders?storeId=$storeId&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $rawKey")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `an API key with PRODUCTS_READ can call GET search`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        seedProductSku(client, adminToken, "SKU-API-1")
        val (_, rawKey) = createApiKey(client, adminToken, listOf("PRODUCTS_READ"))

        val resp = client.get("/search") { header(HttpHeaders.Authorization, "Bearer $rawKey") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `an API key with REPORTS_READ can call GET reports sales, but not the pipeline endpoints`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminToken)
        val (_, rawKey) = createApiKey(client, adminToken, listOf("REPORTS_READ"))

        val readResp = client.get("/reports/sales?storeId=$storeId") { header(HttpHeaders.Authorization, "Bearer $rawKey") }
        assertEquals(HttpStatusCode.OK, readResp.status)

        // Pipeline management endpoints are JWT-only by design (see ReportingRoutes.kt) - an API
        // key should never be able to trigger a pipeline run, no matter its scopes.
        val pipelineResp = client.post("/reports/pipeline/run") {
            header(HttpHeaders.Authorization, "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"period":"DAILY"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, pipelineResp.status)
    }

    @Test
    fun `rotating a key invalidates the old secret immediately and the new one works`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        seedProductSku(client, adminToken, "SKU-API-2")
        val (keyId, oldRawKey) = createApiKey(client, adminToken, listOf("PRODUCTS_READ"))

        val rotateResp = client.post("/api-keys/$keyId/rotate") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        assertEquals(HttpStatusCode.OK, rotateResp.status)
        val newRawKey = Json.parseToJsonElement(rotateResp.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content
        assertTrue(newRawKey != oldRawKey)

        val withOldKey = client.get("/search") { header(HttpHeaders.Authorization, "Bearer $oldRawKey") }
        assertEquals(HttpStatusCode.Unauthorized, withOldKey.status)

        val withNewKey = client.get("/search") { header(HttpHeaders.Authorization, "Bearer $newRawKey") }
        assertEquals(HttpStatusCode.OK, withNewKey.status)
    }

    @Test
    fun `usage is logged and queryable per key`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        seedProductSku(client, adminToken, "SKU-API-3")
        val (keyId, rawKey) = createApiKey(client, adminToken, listOf("PRODUCTS_READ"))

        client.get("/search") { header(HttpHeaders.Authorization, "Bearer $rawKey") }
        client.get("/search?q=widget") { header(HttpHeaders.Authorization, "Bearer $rawKey") }

        val usageResp = client.get("/api-keys/$keyId/usage") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        assertEquals(HttpStatusCode.OK, usageResp.status)
        val entries = Json.parseToJsonElement(usageResp.bodyAsText()).jsonArray
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.jsonObject["statusCode"]!!.jsonPrimitive.int == 200 })
        assertTrue(entries.all { it.jsonObject["path"]!!.jsonPrimitive.content == "/search" })
    }

    @Test
    fun `a user JWT still works normally on a route that also accepts API keys`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        seedProductSku(client, adminToken, "SKU-API-4")

        val resp = client.get("/search") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `an unrecognized bearer token is rejected with 401`() = testApplication {
        configureApp()
        val client = jsonClient()

        val resp = client.get("/search") { header(HttpHeaders.Authorization, "Bearer garbage-not-a-jwt-or-an-api-key") }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
