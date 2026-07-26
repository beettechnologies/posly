package com.beettechnologies.posly.shifts

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

class ShiftRoutesTest {

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

    private suspend fun loginAs(client: HttpClient, username: String, password: String): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun adminToken(client: HttpClient) = loginAs(client, "admin", "admin123")
    private suspend fun managerToken(client: HttpClient) = loginAs(client, "manager", "manager123")
    private suspend fun cashierToken(client: HttpClient) = loginAs(client, "cashier", "cashier123")

    private suspend fun seedStoreId(client: HttpClient, token: String, name: String = "Downtown"): String {
        val resp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"$name","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},
                    |"timezone":"America/New_York","currency":"USD"}""".trimMargin()
            )
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun openShift(client: HttpClient, token: String, storeId: String, openingFloat: Double): String {
        val resp = client.post("/shifts/open") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","openingFloat":$openingFloat}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status, "seed shift open failed: ${resp.bodyAsText()}")
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `opening a shift persists the opening float and marks it open`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)

        val cashierTok = cashierToken(client)
        val resp = client.post("/shifts/open") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","openingFloat":150.0}""")
        }

        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(150.0, body["openingFloat"]?.jsonPrimitive?.double)
        assertEquals("OPEN", body["status"]?.jsonPrimitive?.content)
        assertNull(body["closedAt"])
    }

    @Test
    fun `opening a shift for an unknown store returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierTok = cashierToken(client)

        val resp = client.post("/shifts/open") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"does-not-exist","openingFloat":100.0}""")
        }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `a cashier cannot open a second shift at the same store`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        openShift(client, cashierTok, storeId, 100.0)

        val resp = client.post("/shifts/open") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","openingFloat":100.0}""")
        }

        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `closing a shift with a matching count reports zero variance`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        val shiftId = openShift(client, cashierTok, storeId, 100.0)

        val resp = client.post("/shifts/$shiftId/close") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"closingCount":100.0}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("CLOSED", body["status"]?.jsonPrimitive?.content)
        assertEquals(100.0, body["expectedCash"]?.jsonPrimitive?.double)
        assertEquals(0.0, body["variance"]?.jsonPrimitive?.double)
        assertEquals("NONE", body["varianceCause"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an over-threshold variance without a note is rejected for a cashier closer`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        val shiftId = openShift(client, cashierTok, storeId, 100.0)

        val resp = client.post("/shifts/$shiftId/close") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"closingCount":80.0}""")
        }

        assertEquals(HttpStatusCode.Conflict, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(-20.0, body["variance"]?.jsonPrimitive?.double)
        assertEquals(5.0, body["threshold"]?.jsonPrimitive?.double)

        // The shift must still be open after a rejected close.
        val getResp = client.get("/shifts/$shiftId") { header(HttpHeaders.Authorization, "Bearer $cashierTok") }
        assertEquals("OPEN", Json.parseToJsonElement(getResp.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a note lets a cashier close an over-threshold variance`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        val shiftId = openShift(client, cashierTok, storeId, 100.0)

        val resp = client.post("/shifts/$shiftId/close") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"closingCount":80.0,"note":"Till was short, escalated to manager"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("CLOSED", body["status"]?.jsonPrimitive?.content)
        assertEquals("Till was short, escalated to manager", body["note"]?.jsonPrimitive?.content)
        assertEquals("SHORT", body["varianceCause"]?.jsonPrimitive?.content)
        assertTrue(body["possibleReasons"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `a manager can close an over-threshold variance without a note`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        val shiftId = openShift(client, cashierTok, storeId, 100.0)

        val managerTok = managerToken(client)
        val resp = client.post("/shifts/$shiftId/close") {
            header(HttpHeaders.Authorization, "Bearer $managerTok")
            contentType(ContentType.Application.Json)
            setBody("""{"closingCount":80.0}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("CLOSED", body["status"]?.jsonPrimitive?.content)
        assertNull(body["note"])
    }

    @Test
    fun `expected cash reflects a cash sale made during the shift`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val productId = Json.parseToJsonElement(
            client.post("/products") {
                header(HttpHeaders.Authorization, "Bearer $adminTok")
                contentType(ContentType.Application.Json)
                setBody("""{"sku":"SKU-1","name":"Widget","price":10.0,"taxCategory":"STANDARD"}""")
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

        val cashierTok = cashierToken(client)
        val shiftId = openShift(client, cashierTok, storeId, 100.0)

        val cartId = Json.parseToJsonElement(
            client.post("/carts") {
                header(HttpHeaders.Authorization, "Bearer $cashierTok")
                contentType(ContentType.Application.Json)
                setBody("""{"storeId":"$storeId"}""")
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/carts/$cartId/items") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","quantity":1}""")
        }
        val orderId = Json.parseToJsonElement(
            client.post("/carts/$cartId/checkout") {
                header(HttpHeaders.Authorization, "Bearer $cashierTok")
                contentType(ContentType.Application.Json)
                setBody("""{"idempotencyKey":"key-${(0..1_000_000).random()}"}""")
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CASH","amount":10.0}""")
        }

        val previewResp = client.get("/shifts/$shiftId/expected-cash") { header(HttpHeaders.Authorization, "Bearer $cashierTok") }
        assertEquals(HttpStatusCode.OK, previewResp.status)
        assertEquals(110.0, Json.parseToJsonElement(previewResp.bodyAsText()).jsonObject["expectedCash"]?.jsonPrimitive?.double)

        val closeResp = client.post("/shifts/$shiftId/close") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"closingCount":110.0}""")
        }
        assertEquals(HttpStatusCode.OK, closeResp.status)
        assertEquals(0.0, Json.parseToJsonElement(closeResp.bodyAsText()).jsonObject["variance"]?.jsonPrimitive?.double)
    }

    @Test
    fun `fetching an unknown shift returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierTok = cashierToken(client)

        val resp = client.get("/shifts/does-not-exist") { header(HttpHeaders.Authorization, "Bearer $cashierTok") }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `listing shifts filters by store`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeA = seedStoreId(client, adminTok, "Downtown")
        val storeB = seedStoreId(client, adminTok, "Uptown")
        val cashierTok = cashierToken(client)
        openShift(client, cashierTok, storeA, 100.0)
        openShift(client, cashierTok, storeB, 100.0)

        val resp = client.get("/shifts?storeId=$storeA") { header(HttpHeaders.Authorization, "Bearer $cashierTok") }
        val shifts = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(1, shifts.size)
        assertEquals(storeA, shifts.single().jsonObject["storeId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `closing an already-closed shift returns 409`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        val shiftId = openShift(client, cashierTok, storeId, 100.0)
        client.post("/shifts/$shiftId/close") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"closingCount":100.0}""")
        }

        val resp = client.post("/shifts/$shiftId/close") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"closingCount":100.0}""")
        }

        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `the audit trail records opening and closing a shift`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        val shiftId = openShift(client, cashierTok, storeId, 100.0)
        client.post("/shifts/$shiftId/close") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"closingCount":100.0}""")
        }

        val resp = client.get("/shifts/$shiftId/audit-events") { header(HttpHeaders.Authorization, "Bearer $adminTok") }

        assertEquals(HttpStatusCode.OK, resp.status)
        val events = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(listOf("OPENED", "CLOSED"), events.map { it.jsonObject["type"]?.jsonPrimitive?.content })
    }

    @Test
    fun `a manager override reason is logged and linked to the shift in the audit trail`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        val shiftId = openShift(client, cashierTok, storeId, 100.0)

        val managerTok = managerToken(client)
        client.post("/shifts/$shiftId/close") {
            header(HttpHeaders.Authorization, "Bearer $managerTok")
            contentType(ContentType.Application.Json)
            setBody("""{"closingCount":80.0,"note":"Verified with cashier, register was miscounted"}""")
        }

        val resp = client.get("/shifts/$shiftId/audit-events") { header(HttpHeaders.Authorization, "Bearer $adminTok") }
        val events = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        val override = events.single { it.jsonObject["type"]?.jsonPrimitive?.content == "MANAGER_OVERRIDE" }.jsonObject
        assertEquals(shiftId, override["shiftId"]?.jsonPrimitive?.content)
        assertTrue(override["detail"]!!.jsonPrimitive.content.contains("Verified with cashier, register was miscounted"))

        val discrepancy = events.single { it.jsonObject["type"]?.jsonPrimitive?.content == "DISCREPANCY_RECORDED" }
        assertTrue(discrepancy.jsonObject["detail"]!!.jsonPrimitive.content.contains("SHORT"))
    }

    @Test
    fun `a cashier cannot view the audit trail`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        val shiftId = openShift(client, cashierTok, storeId, 100.0)

        val resp = client.get("/shifts/$shiftId/audit-events") { header(HttpHeaders.Authorization, "Bearer $cashierTok") }

        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `fetching audit events for an unknown shift returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)

        val resp = client.get("/shifts/does-not-exist/audit-events") { header(HttpHeaders.Authorization, "Bearer $adminTok") }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
