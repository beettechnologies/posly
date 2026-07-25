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

class StoreRoutesTest {

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

    private suspend fun managerToken(client: HttpClient): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"manager","password":"manager123"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private val validStoreBody = """
        {"name":"Downtown","address":{"line1":"1 Main St","city":"New York","postalCode":"10001","country":"US"},
         "timezone":"America/New_York","currency":"usd"}
    """.trimIndent()

    @Test
    fun `create store persists with address timezone currency and unique id`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val resp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(validStoreBody)
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertNotNull(body["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Downtown", body["name"]?.jsonPrimitive?.content)
        assertEquals("America/New_York", body["timezone"]?.jsonPrimitive?.content)
        assertEquals("USD", body["currency"]?.jsonPrimitive?.content)
        assertEquals("New York", body["address"]?.jsonObject?.get("city")?.jsonPrimitive?.content)
    }

    @Test
    fun `invalid timezone returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val resp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"Bad","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},
                    |"timezone":"Not/AZone","currency":"USD"}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `invalid currency returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val resp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"Bad","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},
                    |"timezone":"America/New_York","currency":"NOTREAL"}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `unknown tax profile id returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val resp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"Bad","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},
                    |"timezone":"America/New_York","currency":"USD","taxProfileId":"does-not-exist"}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `manager cannot manage stores`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val resp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(validStoreBody)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `get update and delete a store`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val createResp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(validStoreBody)
        }
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val getResp = client.get("/stores/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getResp.status)

        val updateResp = client.put("/stores/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"timezone":"Asia/Tokyo","currency":"JPY"}""")
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)
        val updatedBody = Json.parseToJsonElement(updateResp.bodyAsText()).jsonObject
        assertEquals("Asia/Tokyo", updatedBody["timezone"]?.jsonPrimitive?.content)
        assertEquals("JPY", updatedBody["currency"]?.jsonPrimitive?.content)
        assertEquals("Downtown", updatedBody["name"]?.jsonPrimitive?.content)

        val deleteResp = client.delete("/stores/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        val afterDeleteResp = client.get("/stores/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, afterDeleteResp.status)
    }

    @Test
    fun `list returns all created stores`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(validStoreBody)
        }
        client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(validStoreBody)
        }

        val listResp = client.get("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
        val list = Json.parseToJsonElement(listResp.bodyAsText()).jsonArray
        assertEquals(2, list.size)
    }
}
