package com.beettechnologies.posly.payments

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TEST_WEBHOOK_SECRET = "test-webhook-secret-at-least-this-long"

private fun sign(body: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(TEST_WEBHOOK_SECRET.toByteArray(), "HmacSHA256"))
    return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
}

class PaymentRoutesTest {

    private fun ApplicationTestBuilder.configureApp() {
        environment {
            config = MapApplicationConfig(
                "jwt.secret" to "test-secret-at-least-32-characters-long!!",
                "jwt.issuer" to "posly",
                "jwt.audience" to "posly-api",
                "jwt.accessTokenExpirationMs" to "900000",
                "jwt.refreshTokenExpirationMs" to "604800000",
                "jwt.mfaTokenExpirationMs" to "300000",
                "payments.webhookSecret" to TEST_WEBHOOK_SECRET
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

    private suspend fun createPayment(client: HttpClient, token: String, orderId: String, amount: Double = 10.0): kotlinx.serialization.json.JsonObject {
        val resp = client.post("/payments") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"orderId":"$orderId","amount":$amount,"currency":"USD"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    private suspend fun postWebhook(client: HttpClient, body: String, signature: String? = sign(body)): io.ktor.client.statement.HttpResponse =
        client.post("/payments/webhook") {
            contentType(ContentType.Application.Json)
            if (signature != null) header("X-Webhook-Signature", signature)
            setBody(body)
        }

    @Test
    fun `creating a payment for a checked-out order returns initiated status`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)

        val payment = createPayment(client, cashierTok, orderId)

        assertEquals("INITIATED", payment["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `creating a payment for an unknown order returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierTok = accessToken(client, "cashier", "cashier123")

        val resp = client.post("/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"orderId":"does-not-exist","amount":10.0,"currency":"USD"}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `an approved webhook confirms the order as paid`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        val payment = createPayment(client, cashierTok, orderId)
        val terminalTransactionId = payment["terminalTransactionId"]!!.jsonPrimitive.content

        val body = """{"eventId":"evt-1","terminalTransactionId":"$terminalTransactionId","outcome":"APPROVED"}"""
        val webhookResp = postWebhook(client, body)
        assertEquals(HttpStatusCode.OK, webhookResp.status)

        val orderResp = client.get("/orders/$orderId") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals("PAID", Json.parseToJsonElement(orderResp.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a webhook with an invalid signature is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        val payment = createPayment(client, cashierTok, orderId)
        val terminalTransactionId = payment["terminalTransactionId"]!!.jsonPrimitive.content

        val body = """{"eventId":"evt-1","terminalTransactionId":"$terminalTransactionId","outcome":"APPROVED"}"""
        val resp = postWebhook(client, body, signature = "not-the-right-signature")

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `a declined webhook does not confirm the order`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        val payment = createPayment(client, cashierTok, orderId)
        val terminalTransactionId = payment["terminalTransactionId"]!!.jsonPrimitive.content

        val body = """{"eventId":"evt-1","terminalTransactionId":"$terminalTransactionId","outcome":"DECLINED","declineReason":"Card expired"}"""
        val webhookResp = postWebhook(client, body)
        assertEquals(HttpStatusCode.OK, webhookResp.status)
        assertEquals("DECLINED", Json.parseToJsonElement(webhookResp.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)

        val orderResp = client.get("/orders/$orderId") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals("PENDING", Json.parseToJsonElement(orderResp.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `refunding an approved payment refunds the order`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        val payment = createPayment(client, cashierTok, orderId)
        val paymentId = payment["id"]!!.jsonPrimitive.content
        val terminalTransactionId = payment["terminalTransactionId"]!!.jsonPrimitive.content
        postWebhook(client, """{"eventId":"evt-1","terminalTransactionId":"$terminalTransactionId","outcome":"APPROVED"}""")

        val refundResp = client.post("/payments/$paymentId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","amount":10.0}""")
        }
        assertEquals(HttpStatusCode.OK, refundResp.status)
        assertEquals("REFUNDED", Json.parseToJsonElement(refundResp.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)

        val orderResp = client.get("/orders/$orderId") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals("REFUNDED", Json.parseToJsonElement(orderResp.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cashier cannot issue a refund`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val cashierTok = accessToken(client, "cashier", "cashier123")
        val orderId = seedPendingOrder(client, adminTok, cashierTok)
        val payment = createPayment(client, cashierTok, orderId)
        val paymentId = payment["id"]!!.jsonPrimitive.content
        val terminalTransactionId = payment["terminalTransactionId"]!!.jsonPrimitive.content
        postWebhook(client, """{"eventId":"evt-1","terminalTransactionId":"$terminalTransactionId","outcome":"APPROVED"}""")

        val resp = client.post("/payments/$paymentId/refund") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"refundId":"refund-1","amount":10.0}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }
}
