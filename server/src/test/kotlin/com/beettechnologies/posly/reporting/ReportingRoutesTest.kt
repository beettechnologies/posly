package com.beettechnologies.posly.reporting

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig

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

class ReportingRoutesTest {

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

    private suspend fun HttpClient.loginAs(username: String, password: String): String {
        val resp = post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    /** Store creation is ADMIN-only, so this always logs in as admin regardless of the token the caller will use afterward. */
    private suspend fun HttpClient.createStore(): String {
        val adminToken = loginAs("admin", "admin123")
        val resp = post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"Downtown","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},"timezone":"America/New_York","currency":"USD"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    // -------------------------------------------------------------------------
    // RBAC
    // -------------------------------------------------------------------------

    @Test
    fun `a cashier cannot run the pipeline`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("cashier", "cashier123")

        val resp = client.post("/reports/pipeline/run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"period":"DAILY"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // -------------------------------------------------------------------------
    // Pipeline run + queries
    // -------------------------------------------------------------------------

    @Test
    fun `running the pipeline stores queryable sales, inventory, and staff aggregates`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        val runResp = client.post("/reports/pipeline/run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"period":"DAILY","storeIds":["$storeId"]}""")
        }
        assertEquals(HttpStatusCode.Created, runResp.status)
        val run = Json.parseToJsonElement(runResp.bodyAsText()).jsonObject
        assertEquals("SUCCESS", run["status"]!!.jsonPrimitive.content)
        val periodStart = run["periodStart"]!!.jsonPrimitive.content

        val salesResp = client.get("/reports/sales?storeId=$storeId&period=DAILY&periodStart=$periodStart") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, salesResp.status)
        val sales = Json.parseToJsonElement(salesResp.bodyAsText()).jsonObject
        assertEquals(0, sales["orderCount"]!!.jsonPrimitive.int)

        val inventoryResp = client.get("/reports/inventory?storeId=$storeId&period=DAILY&periodStart=$periodStart") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, inventoryResp.status)

        val staffResp = client.get("/reports/staff?storeId=$storeId&period=DAILY&periodStart=$periodStart") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, staffResp.status)

        val listResp = client.get("/reports/pipeline/runs") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, listResp.status)
        assertEquals(1, Json.parseToJsonElement(listResp.bodyAsText()).jsonArray.size)

        val runId = run["id"]!!.jsonPrimitive.content
        val getRunResp = client.get("/reports/pipeline/runs/$runId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, getRunResp.status)
    }

    @Test
    fun `running the pipeline with an invalid period returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val resp = client.post("/reports/pipeline/run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"period":"YEARLY"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `getting an unknown pipeline run returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")

        val resp = client.get("/reports/pipeline/runs/does-not-exist") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // -------------------------------------------------------------------------
    // Backfill
    // -------------------------------------------------------------------------

    @Test
    fun `backfill returns one run per period in the range`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val storeId = client.createStore()

        val resp = client.post("/reports/pipeline/backfill") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"period":"DAILY","from":"2026-01-01T00:00:00Z","to":"2026-01-04T00:00:00Z","storeIds":["$storeId"]}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        assertEquals(3, Json.parseToJsonElement(resp.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `backfill with from after to returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")

        val resp = client.post("/reports/pipeline/backfill") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"period":"DAILY","from":"2026-01-04T00:00:00Z","to":"2026-01-01T00:00:00Z"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // -------------------------------------------------------------------------
    // Realtime + not-found aggregates
    // -------------------------------------------------------------------------

    @Test
    fun `the realtime sales endpoint returns today-so-far for a store`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        val resp = client.get("/reports/sales/realtime?storeId=$storeId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("DAILY", body["period"]!!.jsonPrimitive.content)
        assertEquals(storeId, body["storeId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `realtime sales without a storeId returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val resp = client.get("/reports/sales/realtime") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `querying a sales aggregate that was never computed returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        val resp = client.get("/reports/sales?storeId=$storeId&period=MONTHLY&periodStart=2020-01-01T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // -------------------------------------------------------------------------
    // Top products
    // -------------------------------------------------------------------------

    @Test
    fun `top products for a store with no sales returns an empty list`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        val resp = client.get("/reports/top-products?storeId=$storeId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(0, Json.parseToJsonElement(resp.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `top products respects a custom limit`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        val resp = client.get("/reports/top-products?storeId=$storeId&limit=3") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `top products without a storeId returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val resp = client.get("/reports/top-products") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `a cashier cannot view top products`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("cashier", "cashier123")

        val resp = client.get("/reports/top-products?storeId=store-1") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // -------------------------------------------------------------------------
    // Cash on hand
    // -------------------------------------------------------------------------

    @Test
    fun `cash on hand for a store with no open shifts is zero`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        val resp = client.get("/reports/cash-on-hand?storeId=$storeId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(0, body["openShiftCount"]!!.jsonPrimitive.int)
        assertEquals(0.0, body["totalExpectedCash"]!!.jsonPrimitive.double)
    }

    @Test
    fun `cash on hand reflects an open shift's opening float`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        val openResp = client.post("/shifts/open") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","openingFloat":150.0}""")
        }
        assertEquals(HttpStatusCode.Created, openResp.status)

        val resp = client.get("/reports/cash-on-hand?storeId=$storeId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(1, body["openShiftCount"]!!.jsonPrimitive.int)
        assertEquals(150.0, body["totalExpectedCash"]!!.jsonPrimitive.double)
    }

    @Test
    fun `cash on hand without a storeId returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val resp = client.get("/reports/cash-on-hand") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // -------------------------------------------------------------------------
    // Capacity / degradation
    // -------------------------------------------------------------------------

    @Test
    fun `exceeding the heavy-analytics rate limit returns 429, not 500`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        // The shared bucket allows 5 heavy-analytics calls per minute; the 6th must be shed.
        val statuses = (1..6).map { n ->
            client.post("/reports/pipeline/run") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"period":"DAILY"}""")
            }.status
        }
        assertEquals(HttpStatusCode.Created, statuses[0])
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last(), "expected the 6th call in one minute to be rate-limited: $statuses")
    }

    @Test
    fun `light reporting reads are unaffected by the heavy-analytics rate limit`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        // Exhaust the shared heavy-analytics bucket.
        repeat(6) {
            client.post("/reports/pipeline/run") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"period":"DAILY"}""")
            }
        }

        val resp = client.get("/reports/sales?storeId=$storeId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status, "a plain aggregate read must not share the heavy-analytics rate limit")
    }

    @Test
    fun `flipping the heavy-analytics kill switch off returns 503 on the pipeline, leaving reads unaffected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = client.loginAs("admin", "admin123")
        val managerToken = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        val createFlagResp = client.post("/feature-flags") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"heavy_analytics_pipeline","description":"Capacity incident lever","enabled":true}""")
        }
        assertEquals(HttpStatusCode.Created, createFlagResp.status)

        val disableResp = client.patch("/feature-flags/heavy_analytics_pipeline") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, disableResp.status)

        val blockedResp = client.post("/reports/pipeline/run") {
            header(HttpHeaders.Authorization, "Bearer $managerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"period":"DAILY","storeIds":["$storeId"]}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, blockedResp.status)
        assertNotNull(blockedResp.headers[HttpHeaders.RetryAfter])

        val readResp = client.get("/reports/sales?storeId=$storeId") { header(HttpHeaders.Authorization, "Bearer $managerToken") }
        assertEquals(HttpStatusCode.OK, readResp.status, "a plain aggregate read must not be gated by the heavy-analytics kill switch")
    }

    @Test
    fun `the heavy-analytics pipeline works normally when the flag has never been created`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val resp = client.post("/reports/pipeline/run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"period":"DAILY"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status, "the kill switch must default to allowed when unprovisioned")
    }
}
