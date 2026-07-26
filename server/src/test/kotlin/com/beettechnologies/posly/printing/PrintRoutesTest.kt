package com.beettechnologies.posly.printing

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

class PrintRoutesTest {

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

    private suspend fun seedPrinterId(client: HttpClient, token: String, storeId: String): String {
        val resp = client.post("/printers") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","name":"Front Counter","connectionType":"USB"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `registering a printer returns it online by default`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = accessToken(client, "admin", "admin123")
        val storeId = seedStoreId(client, token)

        val resp = client.post("/printers") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","name":"Front Counter","connectionType":"USB"}""")
        }

        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("ONLINE", body["status"]?.jsonPrimitive?.content)
        assertEquals("USB", body["connectionType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `printing to an online printer succeeds with 200`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val cashierToken = accessToken(client, "cashier", "cashier123")
        val storeId = seedStoreId(client, adminToken)
        val orderId = seedOrderId(client, adminToken, cashierToken, storeId)
        val printerId = seedPrinterId(client, adminToken, storeId)

        val resp = client.post("/orders/$orderId/print") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"printerId":"$printerId"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("PRINTED", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an offline printer queues the job with 202, offering a fallback`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val cashierToken = accessToken(client, "cashier", "cashier123")
        val storeId = seedStoreId(client, adminToken)
        val orderId = seedOrderId(client, adminToken, cashierToken, storeId)
        val printerId = seedPrinterId(client, adminToken, storeId)

        client.patch("/printers/$printerId/status") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"OFFLINE"}""")
        }

        val resp = client.post("/orders/$orderId/print") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"printerId":"$printerId"}""")
        }

        assertEquals(HttpStatusCode.Accepted, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("QUEUED", body["status"]?.jsonPrimitive?.content)
        assertEquals("Printer is offline", body["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `printing for an unknown printer returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val cashierToken = accessToken(client, "cashier", "cashier123")
        val storeId = seedStoreId(client, adminToken)
        val orderId = seedOrderId(client, adminToken, cashierToken, storeId)

        val resp = client.post("/orders/$orderId/print") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"printerId":"does-not-exist"}""")
        }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `a cashier cannot register a printer but can list them and print`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = accessToken(client, "admin", "admin123")
        val cashierToken = accessToken(client, "cashier", "cashier123")
        val storeId = seedStoreId(client, adminToken)

        val registerResp = client.post("/printers") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"$storeId","name":"Front Counter","connectionType":"USB"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, registerResp.status)

        seedPrinterId(client, adminToken, storeId)
        val listResp = client.get("/printers?storeId=$storeId") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
        assertEquals(1, Json.parseToJsonElement(listResp.bodyAsText()).jsonArray.size)
    }
}
