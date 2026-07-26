package com.beettechnologies.posly.cart

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig
import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val WEBHOOK_SECRET = "test-webhook-secret"

private fun hmac(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
}

class OrderRoutesTest {

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

    private suspend fun firstItemId(client: HttpClient, token: String, orderId: String): String {
        val resp = client.get("/orders/$orderId") { header(HttpHeaders.Authorization, "Bearer $token") }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
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
        val payments = body["payments"]!!.jsonArray
        assertEquals("CARD", payments.single().jsonObject["method"]?.jsonPrimitive?.content)
        assertEquals(0.0, body["remainingBalance"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `confirming payment writes an ORDER_PAYMENT_CONFIRMED audit record with the actor and correlation id`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)

        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            header("X-Correlation-Id", "test-correlation-payment")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":10.0,"reference":"auth-123"}""")
        }

        val entries = AuditService.list(event = AuditEvent.ORDER_PAYMENT_CONFIRMED, correlationId = "test-correlation-payment")
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("orderId=$orderId method=CARD", entry.detail)
        assertNotNull(entry.userId)
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
    fun `split tenders summing to the total mark the order paid with a breakdown of each tender`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)

        val firstResp = client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CASH","amount":4.0}""")
        }
        assertEquals(HttpStatusCode.OK, firstResp.status)
        val firstBody = Json.parseToJsonElement(firstResp.bodyAsText()).jsonObject
        assertEquals("PENDING", firstBody["status"]?.jsonPrimitive?.content)
        assertEquals(6.0, firstBody["remainingBalance"]?.jsonPrimitive?.content?.toDouble())

        val secondResp = client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"GIFT_CARD","amount":6.0,"reference":"gift-ref-1"}""")
        }
        assertEquals(HttpStatusCode.OK, secondResp.status)
        val secondBody = Json.parseToJsonElement(secondResp.bodyAsText()).jsonObject
        assertEquals("PAID", secondBody["status"]?.jsonPrimitive?.content)
        assertEquals(0.0, secondBody["remainingBalance"]?.jsonPrimitive?.content?.toDouble())
        val payments = secondBody["payments"]!!.jsonArray
        assertEquals(listOf("CASH", "GIFT_CARD"), payments.map { it.jsonObject["method"]?.jsonPrimitive?.content })
        assertEquals(listOf(4.0, 6.0), payments.map { it.jsonObject["amount"]?.jsonPrimitive?.content?.toDouble() })
    }

    @Test
    fun `a tender exceeding the remaining balance is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CASH","amount":4.0}""")
        }

        val resp = client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":7.0}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `refunding a paid order in full via MANUAL transitions it to refunded`() = testApplication {
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
        val itemId = firstItemId(client, adminTok, orderId)

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody(
                """{"refundId":"refund-1","method":"MANUAL","reason":"Customer request",
                    |"lineItems":[{"cartItemId":"$itemId","quantity":1}]}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("REFUNDED", body["status"]?.jsonPrimitive?.content)
        assertEquals(10.0, body["amountRefunded"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(0.0, body["remainingRefundable"]?.jsonPrimitive?.content?.toDouble())
        val refund = body["refunds"]!!.jsonArray.single().jsonObject
        assertEquals("Customer request", refund["reason"]?.jsonPrimitive?.content)
        assertEquals("refund-1", refund["refundId"]?.jsonPrimitive?.content)
        assertEquals("MANUAL", refund["method"]?.jsonPrimitive?.content)
    }

    @Test
    fun `refunding a paid order in full via CARD transitions it to refunded`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        val paymentResp = client.post("/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"orderId":"$orderId","amount":10.0,"currency":"USD"}""")
        }
        val terminalTransactionId =
            Json.parseToJsonElement(paymentResp.bodyAsText()).jsonObject["terminalTransactionId"]!!.jsonPrimitive.content
        val webhookBody = """{"eventId":"evt-1","terminalTransactionId":"$terminalTransactionId","outcome":"APPROVED"}"""
        client.post("/payments/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Webhook-Signature", hmac(WEBHOOK_SECRET, webhookBody))
            setBody(webhookBody)
        }
        val itemId = firstItemId(client, adminTok, orderId)

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","method":"CARD","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("REFUNDED", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `refunding writes an ORDER_REFUNDED audit record carrying the method, actor, and correlation id`() = testApplication {
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
        val itemId = firstItemId(client, adminTok, orderId)

        client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            header("X-Correlation-Id", "test-correlation-refund")
            contentType(ContentType.Application.Json)
            setBody(
                """{"refundId":"refund-1","method":"MANUAL","reason":"Customer request",
                    |"lineItems":[{"cartItemId":"$itemId","quantity":1}]}""".trimMargin()
            )
        }

        val entries = AuditService.list(event = AuditEvent.ORDER_REFUNDED, correlationId = "test-correlation-refund")
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("orderId=$orderId refundId=refund-1 method=MANUAL", entry.detail)
        assertNotNull(entry.userId)
    }

    @Test
    fun `partially refunding one unit with restock adjusts inventory`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val storeId = seedStoreId(client, adminTok)
        val productId = seedProductId(client, adminTok, price = 10.0)
        client.post("/inventory/adjustments") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","delta":10,"reason":"Initial stock"}""")
        }

        val cartResp = client.post("/carts") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId"}""")
        }
        val cartId = Json.parseToJsonElement(cartResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/carts/$cartId/items") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","quantity":2}""")
        }
        val checkoutResp = client.post("/carts/$cartId/checkout") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"idempotencyKey":"key-${(0..1_000_000).random()}"}""")
        }
        val orderId = Json.parseToJsonElement(checkoutResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CARD","amount":20.0}""")
        }
        val itemId = firstItemId(client, adminTok, orderId)

        val stockBefore = client.get("/inventory/snapshot?productId=$productId&storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        val onHandBefore =
            Json.parseToJsonElement(stockBefore.bodyAsText()).jsonObject["onHand"]!!.jsonPrimitive.content.toInt()

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody(
                """{"refundId":"refund-1","method":"MANUAL","reason":"Damaged item",
                    |"lineItems":[{"cartItemId":"$itemId","quantity":1,"restock":true}]}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("PARTIALLY_REFUNDED", body["status"]?.jsonPrimitive?.content)
        assertEquals(10.0, body["remainingRefundable"]?.jsonPrimitive?.content?.toDouble())

        val stockAfter = client.get("/inventory/snapshot?productId=$productId&storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        val onHandAfter =
            Json.parseToJsonElement(stockAfter.bodyAsText()).jsonObject["onHand"]!!.jsonPrimitive.content.toInt()
        assertEquals(onHandBefore + 1, onHandAfter, "restocking a refunded unit must increase on-hand inventory")
    }

    @Test
    fun `refunding without restock leaves inventory untouched`() = testApplication {
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
        val orderBefore = client.get("/orders/$orderId") { header(HttpHeaders.Authorization, "Bearer $adminTok") }
        val orderJson = Json.parseToJsonElement(orderBefore.bodyAsText()).jsonObject
        val itemId = orderJson["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        val productId = orderJson["items"]!!.jsonArray.first().jsonObject["productId"]!!.jsonPrimitive.content
        val storeId = orderJson["storeId"]!!.jsonPrimitive.content
        client.post("/inventory/adjustments") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","storeId":"$storeId","delta":10,"reason":"Initial stock"}""")
        }

        val stockBefore = client.get("/inventory/snapshot?productId=$productId&storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        val onHandBefore =
            Json.parseToJsonElement(stockBefore.bodyAsText()).jsonObject["onHand"]!!.jsonPrimitive.content.toInt()

        client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","method":"MANUAL","reason":"No restock","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
        }

        val stockAfter = client.get("/inventory/snapshot?productId=$productId&storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        val onHandAfter =
            Json.parseToJsonElement(stockAfter.bodyAsText()).jsonObject["onHand"]!!.jsonPrimitive.content.toInt()
        assertEquals(onHandBefore, onHandAfter, "a refund without restock must not change on-hand inventory")
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
        val itemId = firstItemId(client, adminTok, orderId)
        val requestBody = """{"refundId":"retry-refund-1","method":"MANUAL","reason":"Retry test",
            |"lineItems":[{"cartItemId":"$itemId","quantity":1}]}""".trimMargin()

        val firstResp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val secondResp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
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
    fun `refunding an already fully-refunded order with a different refundId is rejected`() = testApplication {
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
        val itemId = firstItemId(client, adminTok, orderId)
        client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","method":"MANUAL","reason":"First refund","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
        }

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-2","method":"MANUAL","reason":"Second refund","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
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
        val itemId = firstItemId(client, adminTok, orderId)

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"","method":"MANUAL","reason":"x","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `refunding with an unsupported method is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        val itemId = firstItemId(client, adminTok, orderId)

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","method":"BANK_TRANSFER","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `a manual refund without a reason is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        val itemId = firstItemId(client, adminTok, orderId)

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","method":"MANUAL","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
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
        val itemId = firstItemId(client, adminTok, orderId)

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","method":"MANUAL","reason":"x","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `refunding a card order that has no approved card payment is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        client.post("/orders/$orderId/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"CASH","amount":10.0}""")
        }
        val itemId = firstItemId(client, adminTok, orderId)

        val resp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","method":"CARD","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
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
        val itemId = firstItemId(client, adminTok, orderId)
        client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","method":"MANUAL","reason":"x","lineItems":[{"cartItemId":"$itemId","quantity":1}]}""")
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

    // -------------------------------------------------------------------------
    // GET /orders - drill-down transaction list
    // -------------------------------------------------------------------------

    @Test
    fun `listing orders for a store returns only orders checked out within the given window`() = testApplication {
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
        val storeId = Json.parseToJsonElement(
            client.get("/orders/$orderId") { header(HttpHeaders.Authorization, "Bearer $adminTok") }.bodyAsText()
        ).jsonObject["storeId"]!!.jsonPrimitive.content

        val resp = client.get("/orders?storeId=$storeId&from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val orders = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(1, orders.size)
        assertEquals(orderId, orders.single().jsonObject["id"]!!.jsonPrimitive.content)

        val outsideWindowResp = client.get("/orders?storeId=$storeId&from=2000-01-01T00:00:00Z&to=2000-01-02T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(0, Json.parseToJsonElement(outsideWindowResp.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `listing orders without a storeId returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")

        val resp = client.get("/orders?from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `a cashier cannot list orders for a store`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierTok = accessToken(client, "cashier", "cashier123")

        val resp = client.get("/orders?storeId=store-1&from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }
}
