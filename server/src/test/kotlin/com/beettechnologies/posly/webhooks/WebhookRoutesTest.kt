package com.beettechnologies.posly.webhooks

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

class WebhookRoutesTest {

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
    private suspend fun cashierToken(client: HttpClient) = loginAs(client, "cashier", "cashier123")

    @Test
    fun `registering a subscription returns 201 without echoing the secret back`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)

        val resp = client.post("/webhooks/subscriptions") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/hook","secret":"shh","eventTypes":["ORDER_CREATED"]}""")
        }

        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("https://example.com/hook", body["url"]?.jsonPrimitive?.content)
        assertEquals(listOf("ORDER_CREATED"), body["eventTypes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(body["secret"], "the secret must never be echoed back in a response")
    }

    @Test
    fun `registering with an invalid url is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)

        val resp = client.post("/webhooks/subscriptions") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"not-a-url","secret":"shh","eventTypes":["ORDER_CREATED"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `registering with an unknown eventType is rejected`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)

        val resp = client.post("/webhooks/subscriptions") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/hook","secret":"shh","eventTypes":["NOT_A_REAL_EVENT"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `a cashier cannot register a webhook`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierTok = cashierToken(client)

        val resp = client.post("/webhooks/subscriptions") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/hook","secret":"shh","eventTypes":["ORDER_CREATED"]}""")
        }

        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `listing and fetching subscriptions by id`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val createResp = client.post("/webhooks/subscriptions") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/hook","secret":"shh","eventTypes":["ORDER_CREATED","PAYMENT_SUCCEEDED"]}""")
        }
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val listResp = client.get("/webhooks/subscriptions") { header(HttpHeaders.Authorization, "Bearer $adminTok") }
        assertEquals(1, Json.parseToJsonElement(listResp.bodyAsText()).jsonArray.size)

        val getResp = client.get("/webhooks/subscriptions/$id") { header(HttpHeaders.Authorization, "Bearer $adminTok") }
        assertEquals(HttpStatusCode.OK, getResp.status)
        assertEquals(id, Json.parseToJsonElement(getResp.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fetching an unknown subscription returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)

        val resp = client.get("/webhooks/subscriptions/does-not-exist") { header(HttpHeaders.Authorization, "Bearer $adminTok") }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `deliveries and dead-letter endpoints are reachable and start empty`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)

        val deliveriesResp = client.get("/webhooks/deliveries") { header(HttpHeaders.Authorization, "Bearer $adminTok") }
        assertEquals(HttpStatusCode.OK, deliveriesResp.status)
        assertTrue(Json.parseToJsonElement(deliveriesResp.bodyAsText()).jsonArray.isEmpty())

        val deadLetterResp = client.get("/webhooks/deliveries/dead-letter") { header(HttpHeaders.Authorization, "Bearer $adminTok") }
        assertEquals(HttpStatusCode.OK, deadLetterResp.status)
        assertTrue(Json.parseToJsonElement(deadLetterResp.bodyAsText()).jsonArray.isEmpty())
    }

    @Test
    fun `a cashier cannot view deliveries`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierTok = cashierToken(client)

        val resp = client.get("/webhooks/deliveries") { header(HttpHeaders.Authorization, "Bearer $cashierTok") }

        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }
}
