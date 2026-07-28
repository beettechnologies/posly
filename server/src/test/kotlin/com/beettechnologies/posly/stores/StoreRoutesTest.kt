package com.beettechnologies.posly.stores

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig

import com.beettechnologies.posly.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

private fun pngBytes(): ByteArray {
    val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
    val output = ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return output.toByteArray()
}

class StoreRoutesTest {

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
    fun `create store defaults locale to en-US and accepts a custom locale on update`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val createResp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(validStoreBody)
        }
        val createdBody = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject
        assertEquals("en-US", createdBody["locale"]?.jsonPrimitive?.content)
        assertTrue(createdBody["logoUrl"]!!.jsonPrimitive.content.endsWith("/logo"))
        val id = createdBody["id"]!!.jsonPrimitive.content

        val updateResp = client.put("/stores/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"locale":"de-DE"}""")
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)
        val updatedBody = Json.parseToJsonElement(updateResp.bodyAsText()).jsonObject
        assertEquals("de-DE", updatedBody["locale"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an invalid locale tag returns 400 on create and update`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)

        val createResp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"Bad","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},
                    |"timezone":"America/New_York","currency":"USD","locale":"   "}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, createResp.status)

        val validResp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(validStoreBody)
        }
        val id = Json.parseToJsonElement(validResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updateResp = client.put("/stores/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"locale":"!!!"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, updateResp.status)
    }

    @Test
    fun `uploading and fetching a store logo roundtrips the bytes, admin-only`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)
        val createResp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(validStoreBody)
        }
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val notFoundResp = client.get("/stores/$id/logo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, notFoundResp.status)

        val logoBytes = pngBytes()
        val uploadResp = client.post("/stores/$id/logo") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", logoBytes, Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"logo.png\"")
                            append(HttpHeaders.ContentType, "image/png")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Created, uploadResp.status)
        val logoUrl = Json.parseToJsonElement(uploadResp.bodyAsText()).jsonObject["logoUrl"]?.jsonPrimitive?.content
        assertEquals("/stores/$id/logo", logoUrl)

        val getResp = client.get("/stores/$id/logo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getResp.status)
        assertEquals(ContentType.Image.PNG, getResp.contentType()?.withoutParameters())
        assertTrue(logoBytes.contentEquals(getResp.bodyAsBytes()))
    }

    @Test
    fun `an invalid image upload returns 400 and a manager cannot upload a logo`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = adminToken(client)
        val createResp = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(validStoreBody)
        }
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val invalidResp = client.post("/stores/$id/logo") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", "not an image".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"notanimage.txt\"")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidResp.status)

        val managerAuth = managerToken(client)
        val forbiddenResp = client.post("/stores/$id/logo") {
            header(HttpHeaders.Authorization, "Bearer $managerAuth")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", pngBytes(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"logo.png\"")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenResp.status)
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
