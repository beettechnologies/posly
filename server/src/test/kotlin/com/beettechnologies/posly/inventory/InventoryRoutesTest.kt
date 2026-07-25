package com.beettechnologies.posly.inventory

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

class InventoryRoutesTest {

    private fun ApplicationTestBuilder.configureApp() {
        environment {
            config = MapApplicationConfig(
                "jwt.secret" to "test-secret-at-least-32-characters-long!!",
                "jwt.issuer" to "posly",
                "jwt.audience" to "posly-api",
                "jwt.accessTokenExpirationMs" to "900000",
                "jwt.refreshTokenExpirationMs" to "604800000",
                "jwt.mfaTokenExpirationMs" to "300000"
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

    private suspend fun seedProductId(client: HttpClient, token: String, sku: String = "SKU-1"): String {
        val resp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"$sku","name":"Widget","price":9.99,"taxCategory":"STANDARD"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

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

    private suspend fun adjustStock(client: HttpClient, token: String, productId: String, storeId: String, delta: Int) {
        val resp = client.post("/inventory/adjustments") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","delta":$delta,"reason":"Initial stock"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status, "seed adjustment failed: ${resp.bodyAsText()}")
    }

    @Test
    fun `reserving decrements available stock and records the reservation`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 10)

        val cashierTok = cashierToken(client)
        val resp = client.post("/inventory/reservations") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","quantity":3,"referenceId":"cart-1"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("ACTIVE", body["status"]?.jsonPrimitive?.content)
        assertEquals("cart-1", body["referenceId"]?.jsonPrimitive?.content)

        val snapshotResp = client.get("/inventory/snapshot?productId=$productId&storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        val snapshot = Json.parseToJsonElement(snapshotResp.bodyAsText()).jsonObject
        assertEquals(7, snapshot["available"]?.jsonPrimitive?.int)
        assertEquals(3, snapshot["reserved"]?.jsonPrimitive?.int)
    }

    @Test
    fun `completing an order converts reservation to sold and reduces inventory`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 10)

        val cashierTok = cashierToken(client)
        val reserveResp = client.post("/inventory/reservations") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","quantity":4,"referenceId":"cart-2"}""")
        }
        val reservationId = Json.parseToJsonElement(reserveResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val commitResp = client.post("/inventory/reservations/$reservationId/commit") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals(HttpStatusCode.OK, commitResp.status)
        assertEquals("COMMITTED", Json.parseToJsonElement(commitResp.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)

        val snapshotResp = client.get("/inventory/snapshot?productId=$productId&storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        val snapshot = Json.parseToJsonElement(snapshotResp.bodyAsText()).jsonObject
        assertEquals(6, snapshot["onHand"]?.jsonPrimitive?.int)
        assertEquals(0, snapshot["reserved"]?.jsonPrimitive?.int)
        assertEquals(6, snapshot["available"]?.jsonPrimitive?.int)
    }

    @Test
    fun `admin adjustment corrects counts and appears in the transaction log`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 10)

        val correctionResp = client.post("/inventory/adjustments") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","delta":-2,"reason":"Damaged in transit"}""")
        }
        assertEquals(HttpStatusCode.Created, correctionResp.status)
        val snapshot = Json.parseToJsonElement(correctionResp.bodyAsText()).jsonObject
        assertEquals(8, snapshot["onHand"]?.jsonPrimitive?.int)

        val txResp = client.get("/inventory/transactions?productId=$productId&storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        val transactions = Json.parseToJsonElement(txResp.bodyAsText()).jsonArray
        assertTrue(transactions.any { it.jsonObject["reason"]?.jsonPrimitive?.content == "Damaged in transit" })
    }

    @Test
    fun `releasing a reservation restores available stock`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 5)

        val reserveResp = client.post("/inventory/reservations") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","quantity":5,"referenceId":"cart-3"}""")
        }
        val reservationId = Json.parseToJsonElement(reserveResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val releaseResp = client.post("/inventory/reservations/$reservationId/release") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(HttpStatusCode.OK, releaseResp.status)

        val snapshotResp = client.get("/inventory/snapshot?productId=$productId&storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        val snapshot = Json.parseToJsonElement(snapshotResp.bodyAsText()).jsonObject
        assertEquals(5, snapshot["available"]?.jsonPrimitive?.int)
    }

    @Test
    fun `reserving beyond available stock returns 409`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 1)

        val resp = client.post("/inventory/reservations") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","quantity":5,"referenceId":"cart-4"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `cashier cannot post stock adjustments`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)

        val cashierTok = cashierToken(client)
        val resp = client.post("/inventory/adjustments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","delta":10,"reason":"x"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `manager can post adjustments`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)

        val managerTok = managerToken(client)
        val resp = client.post("/inventory/adjustments") {
            header(HttpHeaders.Authorization, "Bearer $managerTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","delta":10,"reason":"Initial stock"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
    }
}
