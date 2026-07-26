package com.beettechnologies.posly.finance

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

class FinanceReportRoutesTest {

    private fun ApplicationTestBuilder.configureApp() {
        environment {
            config = MapApplicationConfig(
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
    fun `a cashier cannot generate a report`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("cashier", "cashier123")
        val storeId = client.createStore()

        val resp = client.get("/finance/reports/generate?storeId=$storeId&type=SALES&format=CSV&timezone=UTC&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `a manager can generate a report but cannot create a schedule`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val storeId = client.createStore()

        val generateResp = client.get("/finance/reports/generate?storeId=$storeId&type=SALES&format=CSV&timezone=UTC&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, generateResp.status)

        val scheduleResp = client.post("/finance/reports/schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","type":"SALES","format":"CSV","timezone":"UTC","frequency":"DAILY","recipients":["a@b.com"]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, scheduleResp.status)
    }

    // -------------------------------------------------------------------------
    // Generate
    // -------------------------------------------------------------------------

    @Test
    fun `generating a CSV sales report returns an attachment with a filename`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val storeId = client.createStore()

        val resp = client.get("/finance/reports/generate?storeId=$storeId&type=SALES&format=CSV&timezone=America/New_York&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(ContentType.Text.CSV, resp.contentType()?.withoutParameters())
        assertTrue(resp.headers[HttpHeaders.ContentDisposition]?.contains("sales-report") == true)
        assertTrue(resp.bodyAsText().contains("Date"))
    }

    @Test
    fun `generating a report for an unknown store returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")

        val resp = client.get("/finance/reports/generate?storeId=no-such-store&type=TAX&format=PDF&timezone=UTC&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `generating a report with a missing required parameter returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val storeId = client.createStore()

        val resp = client.get("/finance/reports/generate?storeId=$storeId&type=SALES&format=CSV&timezone=UTC") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // -------------------------------------------------------------------------
    // Schedules
    // -------------------------------------------------------------------------

    @Test
    fun `creating, listing, running, and deleting a schedule works end to end`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val storeId = client.createStore()

        val createResp = client.post("/finance/reports/schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","type":"RECONCILIATION","format":"PDF","timezone":"UTC","frequency":"WEEKLY","recipients":["finance@example.com"]}""")
        }
        assertEquals(HttpStatusCode.Created, createResp.status)
        val schedule = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject
        val scheduleId = schedule["id"]!!.jsonPrimitive.content
        assertEquals("WEEKLY", schedule["frequency"]!!.jsonPrimitive.content)

        val listResp = client.get("/finance/reports/schedules?storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
        assertEquals(1, Json.parseToJsonElement(listResp.bodyAsText()).jsonArray.size)

        val runNowResp = client.post("/finance/reports/schedules/$scheduleId/run-now") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, runNowResp.status)
        val run = Json.parseToJsonElement(runNowResp.bodyAsText()).jsonObject
        assertEquals("SUCCESS", run["status"]!!.jsonPrimitive.content)

        val runsResp = client.get("/finance/reports/schedules/$scheduleId/runs") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, runsResp.status)
        assertEquals(1, Json.parseToJsonElement(runsResp.bodyAsText()).jsonArray.size)

        val deleteResp = client.delete("/finance/reports/schedules/$scheduleId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        val listAfterDeleteResp = client.get("/finance/reports/schedules?storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(0, Json.parseToJsonElement(listAfterDeleteResp.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `creating a schedule with an invalid recipient returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val storeId = client.createStore()

        val resp = client.post("/finance/reports/schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","type":"SALES","format":"CSV","timezone":"UTC","frequency":"DAILY","recipients":["not-an-email"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `deleting an unknown schedule returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")

        val resp = client.delete("/finance/reports/schedules/does-not-exist") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
