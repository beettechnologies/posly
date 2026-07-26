package com.beettechnologies.posly.email

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig

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

class EmailRoutesTest {

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

    private suspend fun seedOrderId(client: HttpClient, adminToken: String, cashierToken: String, storeId: String): String {
        val productResp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"SKU-${(0..1_000_000).random()}","name":"Widget","price":10.0,"taxCategory":"STANDARD"}""")
        }
        val productId = Json.parseToJsonElement(productResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

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
    fun `emailing a receipt to a valid address succeeds with 200`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val cashierToken = accessToken(client, "cashier", "cashier123")
        val storeId = seedStoreId(client, adminToken)
        val orderId = seedOrderId(client, adminToken, cashierToken, storeId)

        val resp = client.post("/orders/$orderId/email-receipt") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"recipient":"customer@example.com"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("SENT", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a bounced address fails with 502 and a reported message`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val cashierToken = accessToken(client, "cashier", "cashier123")
        val storeId = seedStoreId(client, adminToken)
        val orderId = seedOrderId(client, adminToken, cashierToken, storeId)

        val resp = client.post("/orders/$orderId/email-receipt") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"recipient":"customer+bounce@example.com"}""")
        }

        assertEquals(HttpStatusCode.BadGateway, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("FAILED", body["status"]?.jsonPrimitive?.content)
        assertNotNull(body["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an invalid email address is rejected with 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val cashierToken = accessToken(client, "cashier", "cashier123")
        val storeId = seedStoreId(client, adminToken)
        val orderId = seedOrderId(client, adminToken, cashierToken, storeId)

        val resp = client.post("/orders/$orderId/email-receipt") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"recipient":"not-an-email"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `emailing a receipt for an unknown order returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierToken = accessToken(client, "cashier", "cashier123")

        val resp = client.post("/orders/does-not-exist/email-receipt") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"recipient":"customer@example.com"}""")
        }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
