package com.beettechnologies.posly.cart

import com.beettechnologies.posly.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CartRoutesTest {

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

    private suspend fun cashierToken(client: HttpClient) = accessToken(client, "cashier", "cashier123")

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

    private suspend fun createCart(client: HttpClient, token: String, storeId: String): String {
        val resp = client.post("/carts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `creating a cart and adding an item recalculates totals server-side`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminTok)
        val productId = seedProductId(client, adminTok, price = 10.0)
        val cashierTok = cashierToken(client)
        val cartId = createCart(client, cashierTok, storeId)

        val addResp = client.post("/carts/$cartId/items") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","quantity":3}""")
        }
        assertEquals(HttpStatusCode.OK, addResp.status)
        val body = Json.parseToJsonElement(addResp.bodyAsText()).jsonObject
        val totals = body["totals"]!!.jsonObject
        assertEquals(30.0, totals["subtotal"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `removing an item updates the cart totals`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminTok)
        val productA = seedProductId(client, adminTok, price = 10.0)
        val productB = seedProductId(client, adminTok, price = 4.0)
        val cashierTok = cashierToken(client)
        val cartId = createCart(client, cashierTok, storeId)

        client.post("/carts/$cartId/items") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productA","quantity":1}""")
        }
        val secondAddResp = client.post("/carts/$cartId/items") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productB","quantity":1}""")
        }
        val itemToRemoveId = Json.parseToJsonElement(secondAddResp.bodyAsText()).jsonObject["items"]!!
            .let { it as JsonArray }
            .first { it.jsonObject["productId"]?.jsonPrimitive?.content == productA }
            .jsonObject["id"]!!.jsonPrimitive.content

        val removeResp = client.delete("/carts/$cartId/items/$itemToRemoveId") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals(HttpStatusCode.OK, removeResp.status)
        val totals = Json.parseToJsonElement(removeResp.bodyAsText()).jsonObject["totals"]!!.jsonObject
        assertEquals(4.0, totals["subtotal"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `checkout retried with the same idempotency key creates only one order`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminTok)
        val productId = seedProductId(client, adminTok, price = 10.0)
        val cashierTok = cashierToken(client)
        val cartId = createCart(client, cashierTok, storeId)
        client.post("/carts/$cartId/items") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","quantity":1}""")
        }

        val firstResp = client.post("/carts/$cartId/checkout") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"idempotencyKey":"retry-token-1"}""")
        }
        assertEquals(HttpStatusCode.Created, firstResp.status)
        val firstOrderId = Json.parseToJsonElement(firstResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val retryResp = client.post("/carts/$cartId/checkout") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"idempotencyKey":"retry-token-1"}""")
        }
        assertEquals(HttpStatusCode.OK, retryResp.status)
        val retryOrderId = Json.parseToJsonElement(retryResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(firstOrderId, retryOrderId)

        val orderResp = client.get("/orders/$firstOrderId") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals(HttpStatusCode.OK, orderResp.status)
    }

    @Test
    fun `checkout with a different idempotency key after checkout is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminTok)
        val productId = seedProductId(client, adminTok, price = 10.0)
        val cashierTok = cashierToken(client)
        val cartId = createCart(client, cashierTok, storeId)
        client.post("/carts/$cartId/items") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","quantity":1}""")
        }
        client.post("/carts/$cartId/checkout") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"idempotencyKey":"key-1"}""")
        }

        val secondResp = client.post("/carts/$cartId/checkout") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"idempotencyKey":"key-2"}""")
        }
        assertEquals(HttpStatusCode.Conflict, secondResp.status)
    }

    @Test
    fun `fetching a cart by id restores it after the device reconnects`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminTok)
        val productId = seedProductId(client, adminTok, price = 7.5)
        val cashierTok = cashierToken(client)
        val cartId = createCart(client, cashierTok, storeId)
        client.post("/carts/$cartId/items") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"productId":"$productId","quantity":2}""")
        }

        // Simulate the device coming back online later and fetching the same cart id again.
        val resumeResp = client.get("/carts/$cartId") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals(HttpStatusCode.OK, resumeResp.status)
        val body = Json.parseToJsonElement(resumeResp.bodyAsText()).jsonObject
        val items = body["items"]!! as JsonArray
        assertEquals(1, items.size)
        assertEquals(2, items.first().jsonObject["quantity"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `checkout on an empty cart is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, adminTok)
        val cashierTok = cashierToken(client)
        val cartId = createCart(client, cashierTok, storeId)

        val resp = client.post("/carts/$cartId/checkout") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"idempotencyKey":"key-1"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `unauthenticated requests to create a cart are rejected`() = testApplication {
        configureApp()
        val client = jsonClient()

        val resp = client.post("/carts") {
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"store-1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `fetching an unknown cart returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierTok = cashierToken(client)

        val resp = client.get("/carts/does-not-exist") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
