package com.beettechnologies.posly.e2e

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WEBHOOK_SECRET = "test-webhook-secret"

private fun hmac(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * One continuous walk through the whole purchase lifecycle - POS -> Payment -> Receipt -> Sync ->
 * Refund - as a single real HTTP flow through `module()` against an in-memory H2 database, the
 * same pattern every `*RoutesTest.kt` in this suite already uses. Each individual leg (checkout,
 * card payment, refund, offline sync, printing, emailing) already has thorough dedicated coverage
 * elsewhere (`OrderRoutesTest`, `SyncRoutesTest`, `PrintServiceTest`, `EmailServiceTest`, etc.) -
 * this test's job is different: it proves the legs actually chain together as one coherent
 * narrative (the same order gets paid, printed, emailed, and refunded; a *different*,
 * offline-originated sale rides through the same store independently), which per-module tests
 * can't demonstrate by construction.
 *
 * Lives in its own package/Gradle test filter (see `.github/workflows/ci.yml`'s `e2e` job and
 * `E2E_TESTING.md`) so it can be run and reported on as a distinct, named suite in CI, separate
 * from the broader unit/integration test run.
 */
class PurchaseLifecycleE2ETest {

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
                "payments.webhookSecret" to WEBHOOK_SECRET
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

    @Test
    fun `full lifecycle - checkout, card payment, receipt, offline sync, and refund all chain together`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val cashierToken = accessToken(client, "cashier", "cashier123")

        // ---------------------------------------------------------------
        // Setup: a store, a product, and a receipt printer.
        // ---------------------------------------------------------------
        val storeResp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"E2E Store","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},
                    |"timezone":"America/New_York","currency":"USD"}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.Created, storeResp.status)
        val storeId = Json.parseToJsonElement(storeResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val productResp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"E2E-SKU-1","name":"E2E Widget","price":20.0,"taxCategory":"STANDARD"}""")
        }
        val productId = Json.parseToJsonElement(productResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val printerResp = client.post("/printers") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","name":"Front Counter","connectionType":"USB"}""")
        }
        val printerId = Json.parseToJsonElement(printerResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // ---------------------------------------------------------------
        // 1. POS: a cashier opens a cart, adds an item, and checks out.
        // ---------------------------------------------------------------
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
            setBody("""{"idempotencyKey":"e2e-checkout-key-1"}""")
        }
        assertEquals(HttpStatusCode.Created, checkoutResp.status)
        val order = Json.parseToJsonElement(checkoutResp.bodyAsText()).jsonObject
        val orderId = order["id"]!!.jsonPrimitive.content
        assertEquals("PENDING", order["status"]!!.jsonPrimitive.content)

        // ---------------------------------------------------------------
        // 2. Payment: a card payment is created, then approved via the gateway's async webhook -
        //    exactly how a real terminal confirms a charge out-of-band from the initial request.
        // ---------------------------------------------------------------
        val createPaymentResp = client.post("/payments") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"orderId":"$orderId","amount":20.0,"currency":"USD"}""")
        }
        assertEquals(HttpStatusCode.Created, createPaymentResp.status)
        val terminalTransactionId = Json.parseToJsonElement(createPaymentResp.bodyAsText())
            .jsonObject["terminalTransactionId"]!!.jsonPrimitive.content

        val webhookBody = """{"eventId":"e2e-webhook-1","terminalTransactionId":"$terminalTransactionId","outcome":"APPROVED"}"""
        val webhookResp = client.post("/payments/webhook") {
            header("X-Webhook-Signature", hmac(WEBHOOK_SECRET, webhookBody))
            contentType(ContentType.Application.Json)
            setBody(webhookBody)
        }
        assertEquals(HttpStatusCode.OK, webhookResp.status)

        val paidOrderResp = client.get("/orders/$orderId") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        assertEquals("PAID", Json.parseToJsonElement(paidOrderResp.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        // ---------------------------------------------------------------
        // 3. Receipt: the same paid order is printed and emailed.
        // ---------------------------------------------------------------
        val printResp = client.post("/orders/$orderId/print") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"printerId":"$printerId"}""")
        }
        assertEquals(HttpStatusCode.OK, printResp.status)
        assertEquals("PRINTED", Json.parseToJsonElement(printResp.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val emailResp = client.post("/orders/$orderId/email-receipt") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"recipient":"customer@example.com"}""")
        }
        assertEquals(HttpStatusCode.OK, emailResp.status)
        assertEquals("SENT", Json.parseToJsonElement(emailResp.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        // ---------------------------------------------------------------
        // 4. Offline sync: a *different* sale, rung up on a paired device while the store was
        //    offline, arrives later as a batch - proving the sync path and the online path are
        //    both live in the same store/session, not mutually exclusive setups.
        // ---------------------------------------------------------------
        val pairCodeResp = client.post("/devices/create-pair-code") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId"}""")
        }
        val pairCode = Json.parseToJsonElement(pairCodeResp.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        val enrollResp = client.post("/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$pairCode"}""")
        }
        val enrollBody = Json.parseToJsonElement(enrollResp.bodyAsText()).jsonObject
        val deviceClientId = enrollBody["clientId"]!!.jsonPrimitive.content
        val deviceClientSecret = enrollBody["clientSecret"]!!.jsonPrimitive.content

        val offlineSaleBody = """
            {"clientId":"$deviceClientId","clientSecret":"$deviceClientSecret","conflictPolicy":"REJECT",
             "sales":[{"idempotencyKey":"e2e-offline-sale-1","items":[{"sku":"E2E-SKU-1","productName":"E2E Widget",
             "quantity":2,"unitPriceAtSale":20.0,"taxCategoryAtSale":"STANDARD"}],
             "payments":[{"method":"CASH","amount":40.0}],"soldAt":"2026-01-01T12:00:00Z"}]}
        """.trimIndent()
        val syncResp = client.post("/sync/offline-sales") {
            contentType(ContentType.Application.Json)
            setBody(offlineSaleBody)
        }
        assertEquals(HttpStatusCode.OK, syncResp.status)
        val syncResult = Json.parseToJsonElement(syncResp.bodyAsText()).jsonObject["results"]!!.jsonArray.single().jsonObject
        assertEquals("CREATED", syncResult["outcome"]!!.jsonPrimitive.content)
        val offlineOrderId = syncResult["orderId"]!!.jsonPrimitive.content

        val offlineOrderResp = client.get("/orders/$offlineOrderId") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        assertEquals(
            "PAID",
            Json.parseToJsonElement(offlineOrderResp.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content,
            "an offline sale paid in full by CASH should sync straight to PAID, independent of the card order above"
        )

        // ---------------------------------------------------------------
        // 5. Refund: the ORIGINAL card-paid order (not the offline one) is refunded in full.
        // ---------------------------------------------------------------
        val orderDetailResp = client.get("/orders/$orderId") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val firstItemId = Json.parseToJsonElement(orderDetailResp.bodyAsText())
            .jsonObject["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content

        val refundResp = client.post("/orders/$orderId/refund") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"refundId":"e2e-refund-1","method":"CARD",
                    |"lineItems":[{"cartItemId":"$firstItemId","quantity":1,"restock":true}]}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.OK, refundResp.status)
        assertEquals("REFUNDED", Json.parseToJsonElement(refundResp.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        // ---------------------------------------------------------------
        // Final sanity: the refunded order's event trail reflects the whole lifecycle in order,
        // and the offline-synced order is untouched by the refund (independent aggregates).
        // ---------------------------------------------------------------
        val eventsResp = client.get("/orders/$orderId/events") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val eventTypes = Json.parseToJsonElement(eventsResp.bodyAsText()).jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("CREATED", "PAYMENT_CONFIRMED", "REFUNDED"), eventTypes)

        val offlineOrderFinalResp = client.get("/orders/$offlineOrderId") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        assertEquals("PAID", Json.parseToJsonElement(offlineOrderFinalResp.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(offlineOrderId != orderId, "the offline sale must be a genuinely separate order from the card-paid one")
    }
}
