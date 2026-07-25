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

class TaxRoutesTest {

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

    private suspend fun seedProfileId(client: HttpClient, token: String, body: String): String {
        val resp = client.post("/tax-profiles") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `calculating tax for a mix of taxable and exempt lines surfaces the exempt amount explicitly`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)
        val profileId = seedProfileId(client, token, """{"name":"Flat","rates":[{"name":"Sales","ratePercent":10.0}]}""")

        val resp = client.post("/taxes/calculate") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"taxProfileId":"$profileId","lines":[
                    |{"id":"1","amount":50.0,"taxCategory":"STANDARD"},
                    |{"id":"2","amount":30.0,"taxCategory":"EXEMPT"}
                    |]}""".trimMargin()
            )
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(50.0, body["taxableAmount"]?.jsonPrimitive?.double)
        assertEquals(30.0, body["exemptAmount"]?.jsonPrimitive?.double)
        assertEquals(5.0, body["totalTax"]?.jsonPrimitive?.double)
        assertEquals(85.0, body["total"]?.jsonPrimitive?.double)
    }

    @Test
    fun `calculating against an unknown tax profile returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val resp = client.post("/taxes/calculate") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"taxProfileId":"does-not-exist","lines":[]}""")
        }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `an invalid taxCategory is rejected with 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)
        val profileId = seedProfileId(client, token, """{"name":"Flat","rates":[{"name":"Sales","ratePercent":10.0}]}""")

        val resp = client.post("/taxes/calculate") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"taxProfileId":"$profileId","lines":[{"id":"1","amount":10.0,"taxCategory":"BOGUS"}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `a negative line amount is rejected with 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)
        val profileId = seedProfileId(client, token, """{"name":"Flat","rates":[{"name":"Sales","ratePercent":10.0}]}""")

        val resp = client.post("/taxes/calculate") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"taxProfileId":"$profileId","lines":[{"id":"1","amount":-5.0,"taxCategory":"STANDARD"}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `a cashier can calculate tax, matching the existing calculate endpoint's role set`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)
        val profileId = seedProfileId(client, adminTok, """{"name":"Flat","rates":[{"name":"Sales","ratePercent":10.0}]}""")

        val cashierTok = cashierToken(client)
        val resp = client.post("/taxes/calculate") {
            header(HttpHeaders.Authorization, "Bearer $cashierTok")
            contentType(ContentType.Application.Json)
            setBody("""{"taxProfileId":"$profileId","lines":[{"id":"1","amount":10.0,"taxCategory":"STANDARD"}]}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `an inclusive composite profile computes the same breakdown as the engine unit tests`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)
        val profileId = seedProfileId(
            client, token,
            """{"name":"Compound VAT","pricingMode":"INCLUSIVE",
                |"rates":[{"name":"VAT","ratePercent":20.0,"order":1,"compoundsOnPrior":false},
                |{"name":"Municipal","ratePercent":5.0,"order":2,"compoundsOnPrior":true}]}""".trimMargin()
        )

        val resp = client.post("/taxes/calculate") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"taxProfileId":"$profileId","lines":[{"id":"1","amount":126.0,"taxCategory":"STANDARD"}]}""")
        }

        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(100.0, body["taxableAmount"]?.jsonPrimitive?.double)
        assertEquals(26.0, body["totalTax"]?.jsonPrimitive?.double)
        assertEquals(126.0, body["total"]?.jsonPrimitive?.double)
    }
}
