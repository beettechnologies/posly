package com.beettechnologies.posly.sync

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncRoutesTest {

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

    private suspend fun seedProductSku(client: HttpClient, token: String, sku: String, price: Double): String {
        val resp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"$sku","name":"Widget","price":$price,"taxCategory":"STANDARD"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Returns (clientId, clientSecret) for a freshly enrolled device belonging to [storeId]. */
    private suspend fun enrollDevice(client: HttpClient, adminToken: String, storeId: String): Pair<String, String> {
        val createResp = client.post("/devices/create-pair-code") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId"}""")
        }
        val code = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        val enrollResp = client.post("/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }
        val body = Json.parseToJsonElement(enrollResp.bodyAsText()).jsonObject
        return body["clientId"]!!.jsonPrimitive.content to body["clientSecret"]!!.jsonPrimitive.content
    }

    private fun saleBody(
        idempotencyKey: String,
        sku: String,
        unitPriceAtSale: Double,
        amount: Double = unitPriceAtSale
    ) = """{"idempotencyKey":"$idempotencyKey","items":[{"sku":"$sku","productName":"Widget","quantity":1,
        |"unitPriceAtSale":$unitPriceAtSale,"taxCategoryAtSale":"STANDARD"}],
        |"payments":[{"method":"CASH","amount":$amount}],"soldAt":"2026-01-01T00:00:00Z"}""".trimMargin()

    private fun batchBody(clientId: String, clientSecret: String, conflictPolicy: String, sales: List<String>) =
        """{"clientId":"$clientId","clientSecret":"$clientSecret","conflictPolicy":"$conflictPolicy","sales":[${sales.joinToString(",")}]}"""

    @Test
    fun `a clean sale is created and paid, and the order id is returned`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminToken)
        seedProductSku(client, adminToken, "SKU-1", 10.0)
        val (clientId, clientSecret) = enrollDevice(client, adminToken, storeId)

        val resp = client.post("/sync/offline-sales") {
            contentType(ContentType.Application.Json)
            setBody(batchBody(clientId, clientSecret, "REJECT", listOf(saleBody("key-1", "SKU-1", 10.0))))
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val result = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["results"]!!.jsonArray.single().jsonObject
        assertEquals("CREATED", result["outcome"]!!.jsonPrimitive.content)
        assertEquals(false, result["replayed"]!!.jsonPrimitive.content.toBoolean())
        val orderId = result["orderId"]!!.jsonPrimitive.content

        val orderResp = client.get("/orders/$orderId") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val order = Json.parseToJsonElement(orderResp.bodyAsText()).jsonObject
        assertEquals("PAID", order["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resubmitting the same batch replays the original outcome`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminToken)
        seedProductSku(client, adminToken, "SKU-1", 10.0)
        val (clientId, clientSecret) = enrollDevice(client, adminToken, storeId)
        val body = batchBody(clientId, clientSecret, "REJECT", listOf(saleBody("key-1", "SKU-1", 10.0)))

        val first = client.post("/sync/offline-sales") { contentType(ContentType.Application.Json); setBody(body) }
        val second = client.post("/sync/offline-sales") { contentType(ContentType.Application.Json); setBody(body) }

        val firstResult = Json.parseToJsonElement(first.bodyAsText()).jsonObject["results"]!!.jsonArray.single().jsonObject
        val secondResult = Json.parseToJsonElement(second.bodyAsText()).jsonObject["results"]!!.jsonArray.single().jsonObject
        assertEquals(false, firstResult["replayed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, secondResult["replayed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(firstResult["orderId"]!!.jsonPrimitive.content, secondResult["orderId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a price mismatch under REJECT is not persisted, under MAP it is`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminToken)
        seedProductSku(client, adminToken, "SKU-1", 12.0)
        val (clientId, clientSecret) = enrollDevice(client, adminToken, storeId)

        val rejectResp = client.post("/sync/offline-sales") {
            contentType(ContentType.Application.Json)
            setBody(batchBody(clientId, clientSecret, "REJECT", listOf(saleBody("key-reject", "SKU-1", 10.0))))
        }
        val rejectResult = Json.parseToJsonElement(rejectResp.bodyAsText()).jsonObject["results"]!!.jsonArray.single().jsonObject
        assertEquals("CONFLICT_REJECTED", rejectResult["outcome"]!!.jsonPrimitive.content)
        assertTrue(rejectResult["orderId"] is kotlinx.serialization.json.JsonNull)
        val conflict = rejectResult["conflicts"]!!.jsonArray.single().jsonObject
        assertEquals("PRICE_CHANGED", conflict["reason"]!!.jsonPrimitive.content)

        val mapResp = client.post("/sync/offline-sales") {
            contentType(ContentType.Application.Json)
            setBody(batchBody(clientId, clientSecret, "MAP", listOf(saleBody("key-map", "SKU-1", 10.0))))
        }
        val mapResult = Json.parseToJsonElement(mapResp.bodyAsText()).jsonObject["results"]!!.jsonArray.single().jsonObject
        assertEquals("CONFLICT_RESOLVED_MAP", mapResult["outcome"]!!.jsonPrimitive.content)
        val orderId = mapResult["orderId"]!!.jsonPrimitive.content
        val orderResp = client.get("/orders/$orderId") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val order = Json.parseToJsonElement(orderResp.bodyAsText()).jsonObject
        assertEquals(12.0, order["totals"]!!.jsonObject["total"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `invalid device credentials are rejected with 401`() = testApplication {
        configureApp()
        val client = jsonClient()

        val resp = client.post("/sync/offline-sales") {
            contentType(ContentType.Application.Json)
            setBody(batchBody("unknown-client", "wrong-secret", "REJECT", listOf(saleBody("key-1", "SKU-1", 10.0))))
        }

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `a deprovisioned device is rejected with 403`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminToken)
        val (clientId, clientSecret) = enrollDevice(client, adminToken, storeId)

        val devicesResp = client.get("/devices") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val deviceId = Json.parseToJsonElement(devicesResp.bodyAsText()).jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        client.post("/devices/$deviceId/deprovision") { header(HttpHeaders.Authorization, "Bearer $adminToken") }

        val resp = client.post("/sync/offline-sales") {
            contentType(ContentType.Application.Json)
            setBody(batchBody(clientId, clientSecret, "REJECT", listOf(saleBody("key-1", "SKU-1", 10.0))))
        }

        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `admin can list conflicts but a cashier is forbidden`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val cashierToken = accessToken(client, "cashier", "cashier123")
        val storeId = seedStoreId(client, adminToken)
        seedProductSku(client, adminToken, "SKU-1", 12.0)
        val (clientId, clientSecret) = enrollDevice(client, adminToken, storeId)

        client.post("/sync/offline-sales") {
            contentType(ContentType.Application.Json)
            setBody(batchBody(clientId, clientSecret, "REJECT", listOf(saleBody("key-1", "SKU-1", 10.0))))
        }

        val adminResp = client.get("/sync/conflicts") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        assertEquals(HttpStatusCode.OK, adminResp.status)
        val conflicts = Json.parseToJsonElement(adminResp.bodyAsText()).jsonArray
        assertEquals(1, conflicts.size)
        assertEquals("key-1", conflicts.single().jsonObject["idempotencyKey"]!!.jsonPrimitive.content)

        val cashierResp = client.get("/sync/conflicts") { header(HttpHeaders.Authorization, "Bearer $cashierToken") }
        assertEquals(HttpStatusCode.Forbidden, cashierResp.status)
    }

    @Test
    fun `an invalid conflictPolicy value is rejected with 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminToken)
        val (clientId, clientSecret) = enrollDevice(client, adminToken, storeId)

        val resp = client.post("/sync/offline-sales") {
            contentType(ContentType.Application.Json)
            setBody(batchBody(clientId, clientSecret, "BOGUS", listOf(saleBody("key-1", "SKU-1", 10.0))))
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
