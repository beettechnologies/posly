package com.beettechnologies.posly.products.search

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRoutesTest {

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

    private suspend fun managerToken(client: HttpClient): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"manager","password":"manager123"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun cashierToken(client: HttpClient): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"cashier","password":"cashier123"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun createProduct(client: HttpClient, token: String, body: String) {
        val resp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, resp.status, "seed product creation failed: ${resp.bodyAsText()}")
    }

    @Test
    fun `typeahead search matches on name prefix`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)
        createProduct(
            client, token,
            """{"sku":"SRCH-1","name":"Coffee Mug","price":9.99,"taxCategory":"STANDARD"}"""
        )
        createProduct(
            client, token,
            """{"sku":"SRCH-2","name":"Tea Kettle","price":19.99,"taxCategory":"STANDARD"}"""
        )

        val resp = client.get("/search?q=Cof") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val results = body["results"]!!.jsonArray
        assertEquals(1, results.size)
        assertEquals("Coffee Mug", results[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cashier can search`() = testApplication {
        configureApp()
        val client = jsonClient()
        val managerTok = managerToken(client)
        createProduct(
            client, managerTok,
            """{"sku":"SRCH-CASHIER","name":"Notebook","price":2.5,"taxCategory":"STANDARD"}"""
        )

        val cashierTok = cashierToken(client)
        val resp = client.get("/search?q=Note") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `barcode returns exact match immediately`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)
        createProduct(
            client, token,
            """{"sku":"SRCH-3","name":"Chocolate Bar","price":2.5,"taxCategory":"STANDARD","barcode":"0123456789012"}"""
        )
        createProduct(
            client, token,
            """{"sku":"SRCH-4","name":"Chocolate Cake","price":12.5,"taxCategory":"STANDARD","barcode":"9998887776665"}"""
        )

        val resp = client.get("/search?barcode=0123456789012") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val results = body["results"]!!.jsonArray
        assertEquals(1, results.size)
        assertEquals("SRCH-3", results[0].jsonObject["sku"]?.jsonPrimitive?.content)
    }

    @Test
    fun `filters by category and in_stock combine with query`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)
        createProduct(
            client, token,
            """{"sku":"SRCH-5","name":"Wireless Mouse","price":25.0,"taxCategory":"STANDARD",
                "category":"electronics","inStock":true}"""
        )
        createProduct(
            client, token,
            """{"sku":"SRCH-6","name":"Wireless Keyboard","price":45.0,"taxCategory":"STANDARD",
                "category":"electronics","inStock":false}"""
        )
        createProduct(
            client, token,
            """{"sku":"SRCH-7","name":"Wireless Charger","price":15.0,"taxCategory":"STANDARD",
                "category":"accessories","inStock":true}"""
        )

        val resp = client.get("/search?q=Wireless&category=electronics&in_stock=true") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val results = body["results"]!!.jsonArray
        assertEquals(1, results.size)
        assertEquals("SRCH-5", results[0].jsonObject["sku"]?.jsonPrimitive?.content)
    }

    @Test
    fun `invalid in_stock value returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val resp = client.get("/search?in_stock=maybe") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `pagination respects page and size`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)
        repeat(5) { i ->
            createProduct(
                client, token,
                """{"sku":"PAGE-$i","name":"Paginated Widget $i","price":1.0,"taxCategory":"STANDARD"}"""
            )
        }

        val firstPage = client.get("/search?q=Paginated&page=0&size=2") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val firstBody = Json.parseToJsonElement(firstPage.bodyAsText()).jsonObject
        assertEquals(2, firstBody["results"]!!.jsonArray.size)
        assertEquals(5, firstBody["total"]?.jsonPrimitive?.content?.toInt())

        val secondPage = client.get("/search?q=Paginated&page=1&size=2") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val secondBody = Json.parseToJsonElement(secondPage.bodyAsText()).jsonObject
        assertEquals(2, secondBody["results"]!!.jsonArray.size)

        val firstSkus = firstBody["results"]!!.jsonArray.map { it.jsonObject["sku"]?.jsonPrimitive?.content }
        val secondSkus = secondBody["results"]!!.jsonArray.map { it.jsonObject["sku"]?.jsonPrimitive?.content }
        assertTrue(firstSkus.none { it in secondSkus }, "pages should not overlap")
    }

    @Test
    fun `unauthenticated search returns 401`() = testApplication {
        configureApp()
        val client = jsonClient()
        val resp = client.get("/search?q=anything")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `typeahead search responds within a generous latency budget`() = testApplication {
        // Smoke check only: confirms the search path isn't grossly slow in this
        // environment. Real SLA/load testing needs a dedicated tool (k6, Gatling)
        // against a running instance, not a unit test on shared CI hardware.
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)
        repeat(20) { i ->
            createProduct(
                client, token,
                """{"sku":"PERF-$i","name":"Perf Widget $i","price":1.0,"taxCategory":"STANDARD"}"""
            )
        }

        val start = System.nanoTime()
        val resp = client.get("/search?q=Perf") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(elapsedMs < 2_000, "search took ${elapsedMs}ms, expected a fast in-process response")
    }
}
