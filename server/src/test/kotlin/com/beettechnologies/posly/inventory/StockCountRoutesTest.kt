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

class StockCountRoutesTest {

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
    fun `submitting a stock count with a shortage returns a variance report and posts an adjustment`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 10)

        val resp = client.post("/inventory/stock-counts") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","lines":[{"productId":"$productId","countedQuantity":6}]}""")
        }

        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(true, body["hasVariance"]?.jsonPrimitive?.boolean)
        assertEquals(4, body["totalVarianceUnits"]?.jsonPrimitive?.int)
        val variance = body["variances"]!!.jsonArray.single().jsonObject
        assertEquals(10, variance["expectedQuantity"]?.jsonPrimitive?.int)
        assertEquals(6, variance["countedQuantity"]?.jsonPrimitive?.int)
        assertEquals(-4, variance["delta"]?.jsonPrimitive?.int)
        assertEquals("SHORTAGE", variance["cause"]?.jsonPrimitive?.content)
        assertTrue(variance["probableCause"]!!.jsonPrimitive.content.isNotBlank())
        assertNotNull(variance["adjustmentTransactionId"]?.jsonPrimitive?.content)

        val snapshotResp = client.get("/inventory/snapshot?productId=$productId&storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(6, Json.parseToJsonElement(snapshotResp.bodyAsText()).jsonObject["onHand"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a stock count matching expected on-hand reports no variance`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 10)

        val resp = client.post("/inventory/stock-counts") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","lines":[{"productId":"$productId","countedQuantity":10}]}""")
        }

        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(false, body["hasVariance"]?.jsonPrimitive?.boolean)
        val variance = body["variances"]!!.jsonArray.single().jsonObject
        assertEquals("NONE", variance["cause"]?.jsonPrimitive?.content)
        assertNull(variance["adjustmentTransactionId"])
    }

    @Test
    fun `fetching a stock count by id returns the stored report`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 10)

        val submitResp = client.post("/inventory/stock-counts") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","lines":[{"productId":"$productId","countedQuantity":9}]}""")
        }
        val stockCountId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val getResp = client.get("/inventory/stock-counts/$stockCountId") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(HttpStatusCode.OK, getResp.status)
        assertEquals(stockCountId, Json.parseToJsonElement(getResp.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fetching an unknown stock count returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)

        val resp = client.get("/inventory/stock-counts/does-not-exist") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `listing stock counts for a store returns only that store's counts`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeA = seedStoreId(client, adminTok, name = "Downtown")
        val storeB = seedStoreId(client, adminTok, name = "Uptown")
        adjustStock(client, adminTok, productId, storeA, delta = 10)
        adjustStock(client, adminTok, productId, storeB, delta = 10)

        client.post("/inventory/stock-counts") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeA","lines":[{"productId":"$productId","countedQuantity":10}]}""")
        }
        client.post("/inventory/stock-counts") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeB","lines":[{"productId":"$productId","countedQuantity":10}]}""")
        }

        val resp = client.get("/inventory/stock-counts?storeId=$storeA") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        val stockCounts = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(1, stockCounts.size)
        assertEquals(storeA, stockCounts.single().jsonObject["storeId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a count below the reserved quantity is rejected with 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 10)
        client.post("/inventory/reservations") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","quantity":5,"referenceId":"cart-1"}""")
        }

        val resp = client.post("/inventory/stock-counts") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","lines":[{"productId":"$productId","countedQuantity":2}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `submitting for an unknown store returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)

        val resp = client.post("/inventory/stock-counts") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"does-not-exist","lines":[{"productId":"$productId","countedQuantity":1}]}""")
        }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `manager can submit a stock count`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 10)

        val managerTok = managerToken(client)
        val resp = client.post("/inventory/stock-counts") {
            header(HttpHeaders.Authorization, "Bearer $managerTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","lines":[{"productId":"$productId","countedQuantity":10}]}""")
        }

        assertEquals(HttpStatusCode.Created, resp.status)
    }

    @Test
    fun `cashier cannot submit a stock count but can view one`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val productId = seedProductId(client, adminTok)
        val storeId = seedStoreId(client, adminTok)
        adjustStock(client, adminTok, productId, storeId, delta = 10)

        val cashierTok = cashierToken(client)
        val submitResp = client.post("/inventory/stock-counts") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","lines":[{"productId":"$productId","countedQuantity":10}]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, submitResp.status)

        val listResp = client.get("/inventory/stock-counts?storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
    }
}
