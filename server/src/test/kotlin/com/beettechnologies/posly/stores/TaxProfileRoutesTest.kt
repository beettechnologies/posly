package com.beettechnologies.posly.stores

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

class TaxProfileRoutesTest {

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

    private suspend fun adminToken(client: HttpClient): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin123"}""")
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

    @Test
    fun `create tax profile with multiple rates`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val resp = client.post("/tax-profiles") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"NY Combined","rates":[{"name":"State","ratePercent":4.0},{"name":"City","ratePercent":4.5}]}"""
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertNotNull(body["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, body["rates"]?.jsonArray?.size)
    }

    @Test
    fun `cashier cannot create tax profiles`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = cashierToken(client)

        val resp = client.post("/tax-profiles") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"X","rates":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `calculate tax sums rates and returns a breakdown`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val createResp = client.post("/tax-profiles") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"NY Combined","rates":[{"name":"State","ratePercent":4.0},{"name":"City","ratePercent":4.5}]}"""
            )
        }
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val calcResp = client.post("/tax-profiles/$id/calculate") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"amount":100.0}""")
        }
        assertEquals(HttpStatusCode.OK, calcResp.status)
        val body = Json.parseToJsonElement(calcResp.bodyAsText()).jsonObject
        assertEquals(100.0, body["subtotal"]?.jsonPrimitive?.double)
        assertEquals(8.5, body["totalTax"]?.jsonPrimitive?.double)
        assertEquals(108.5, body["total"]?.jsonPrimitive?.double)
        assertEquals(2, body["breakdown"]?.jsonArray?.size)
    }

    @Test
    fun `cashier can calculate tax even though they cannot manage profiles`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)

        val createResp = client.post("/tax-profiles") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Flat","rates":[{"name":"Sales","ratePercent":10.0}]}""")
        }
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val cashierTok = cashierToken(client)
        val calcResp = client.post("/tax-profiles/$id/calculate") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"amount":50.0}""")
        }
        assertEquals(HttpStatusCode.OK, calcResp.status)
        val body = Json.parseToJsonElement(calcResp.bodyAsText()).jsonObject
        assertEquals(5.0, body["totalTax"]?.jsonPrimitive?.double)
    }

    @Test
    fun `assigning a tax profile to a store makes it visible on the store`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val profileResp = client.post("/tax-profiles") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Flat","rates":[{"name":"Sales","ratePercent":7.0}]}""")
        }
        val profileId = Json.parseToJsonElement(profileResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val storeResp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"Downtown","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},
                    |"timezone":"America/New_York","currency":"USD","taxProfileId":"$profileId"}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.Created, storeResp.status)
        val storeBody = Json.parseToJsonElement(storeResp.bodyAsText()).jsonObject
        assertEquals(profileId, storeBody["taxProfileId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `calculate against unknown profile returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val resp = client.post("/tax-profiles/does-not-exist/calculate") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"amount":10.0}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
