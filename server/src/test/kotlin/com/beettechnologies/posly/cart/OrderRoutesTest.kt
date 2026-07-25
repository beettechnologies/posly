package com.beettechnologies.posly.cart

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

class OrderRoutesTest {

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

    private suspend fun seedProductId(client: HttpClient, token: String, price: Double = 10.0): String {
        val resp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"SKU-${(0..1_000_000).random()}","name":"Widget","price":$price,"taxCategory":"STANDARD"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Creates a store, product, cart with one item, and checks it out. Returns (token, orderId). */
    private suspend fun seedPendingOrder(client: HttpClient, adminToken: String, cashierToken: String): String {
        val storeId = seedStoreId(client, adminToken)
        val productId = seedProductId(client, adminToken)

        val cartResp = client.post("/carts") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId"}""")
        }
        val cartId = Json.parseToJsonElement(cartResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.post("/carts/$cartId/items") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","quantity":1}""")
        }

        val checkoutResp = client.post("/carts/$cartId/checkout") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"idempotencyKey":"key-${(0..1_000_000).random()}"}""")
        }
        return Json.parseToJsonElement(checkoutResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `confirming payment transitions the order to paid`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)

        val resp = client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":10.0,"reference":"auth-123"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("PAID", body["status"]?.jsonPrimitive?.content)
        assertEquals("CARD", body["payment"]?.jsonObject?.get("method")?.jsonPrimitive?.content)
    }

    @Test
    fun `confirming payment twice is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)

        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":10.0}""")
        }
        val secondResp = client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":10.0}""")
        }
        assertEquals(HttpStatusCode.Conflict, secondResp.status)
    }

    @Test
    fun `refunding a paid order transitions it to refunded`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":10.0}""")
        }

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","reason":"Customer request"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("REFUNDED", body["status"]?.jsonPrimitive?.content)
        assertEquals("Customer request", body["refund"]?.jsonObject?.get("reason")?.jsonPrimitive?.content)
        assertEquals("refund-1", body["refund"]?.jsonObject?.get("refundId")?.jsonPrimitive?.content)
    }

    @Test
    fun `refunding twice with the same refundId replays the original result`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":10.0}""")
        }

        val firstResp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"retry-refund-1"}""")
        }
        val secondResp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"retry-refund-1"}""")
        }

        assertEquals(HttpStatusCode.OK, firstResp.status)
        assertEquals(HttpStatusCode.OK, secondResp.status)
        assertEquals(firstResp.bodyAsText(), secondResp.bodyAsText())

        val eventsResp = client.get("/orders/$orderId/events") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        val types = Json.parseToJsonElement(eventsResp.bodyAsText()).jsonArray.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertEquals(listOf("CREATED", "PAYMENT_CONFIRMED", "REFUNDED"), types, "a replayed refund must not double up the audit trail")
    }

    @Test
    fun `refunding an already-refunded order with a different refundId is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":10.0}""")
        }
        client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1"}""")
        }

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-2"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `refunding without a refundId is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `refunding a pending (unpaid) order is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `cashier cannot issue a refund`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":10.0}""")
        }

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `the order's audit trail records creation, payment, and refund in order`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":10.0}""")
        }
        client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1"}""")
        }

        val resp = client.get("/orders/$orderId/events") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val types = Json.parseToJsonElement(resp.bodyAsText()).jsonArray.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertEquals(listOf("CREATED", "PAYMENT_CONFIRMED", "REFUNDED"), types)
    }

    @Test
    fun `fetching an unknown order returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierTok = accessToken(client, "cashier", "cashier123")

        val resp = client.get("/orders/does-not-exist") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
