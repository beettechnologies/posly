package com.beettechnologies.posly.devices

import com.beettechnologies.posly.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.createClient
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DeviceRoutesTest {

    private fun ApplicationTestBuilder.configureApp() {
        environment {
            config = MapApplicationConfig(
                "jwt.secret" to "test-secret-at-least-32-characters-long!!",
                "jwt.issuer" to "posly",
                "jwt.audience" to "posly-api",
                "jwt.accessTokenExpirationMs" to "900000",
                "jwt.refreshTokenExpirationMs" to "604800000",
                "jwt.mfaTokenExpirationMs" to "300000"
            )
        }
        application { module() }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ContentNegotiation) { json() } }

    private suspend fun managerAccessToken(client: HttpClient): String {
        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"manager","password":"manager123"}""")
        }
        val loginBody = Json.parseToJsonElement(loginResp.bodyAsText()).jsonObject
        return loginBody["accessToken"]!!.jsonPrimitive.content
    }

    @Test
    fun `create pair code and enroll device returns credentials`() = testApplication {
        configureApp()
        val client = jsonClient()
        val accessToken = managerAccessToken(client)

        val createResp = client.post("/devices/create-pair-code") {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"store-1","expiresInSeconds":300}""")
        }
        assertEquals(HttpStatusCode.Created, createResp.status)
        val createBody = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject
        val code = createBody["code"]?.jsonPrimitive?.content
        val expiresAt = createBody["expiresAt"]?.jsonPrimitive?.content
        assertNotNull(code)
        assertNotNull(expiresAt)

        val enrollResp = client.post("/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code","storeId":"store-1","name":"Front Register"}""")
        }
        assertEquals(HttpStatusCode.OK, enrollResp.status)
        val enrollBody = Json.parseToJsonElement(enrollResp.bodyAsText()).jsonObject
        assertNotNull(enrollBody["deviceId"]?.jsonPrimitive?.content)
        assertNotNull(enrollBody["clientId"]?.jsonPrimitive?.content)
        assertNotNull(enrollBody["clientSecret"]?.jsonPrimitive?.content)
    }

    @Test
    fun `used code cannot be reused for enrollment`() = testApplication {
        configureApp()
        val client = jsonClient()
        val accessToken = managerAccessToken(client)

        val createResp = client.post("/devices/create-pair-code") {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"store-2"}""")
        }
        val code = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        client.post("/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }

        val reuseResp = client.post("/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, reuseResp.status)
        val body = Json.parseToJsonElement(reuseResp.bodyAsText()).jsonObject
        assertEquals("Pairing code already used", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `expired code enrollment fails with clear error`() = testApplication {
        configureApp()
        val client = jsonClient()
        val accessToken = managerAccessToken(client)

        val createResp = client.post("/devices/create-pair-code") {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"store-3","expiresInSeconds":0}""")
        }
        val code = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        val enrollResp = client.post("/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, enrollResp.status)
        val body = Json.parseToJsonElement(enrollResp.bodyAsText()).jsonObject
        assertEquals("Pairing code expired", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `revoked code is invalid for enrollment and validation`() = testApplication {
        configureApp()
        val client = jsonClient()
        val accessToken = managerAccessToken(client)

        val createResp = client.post("/devices/create-pair-code") {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"store-4"}""")
        }
        val code = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        val revokeResp = client.post("/devices/revoke-pair-code") {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }
        assertEquals(HttpStatusCode.OK, revokeResp.status)

        val validateResp = client.post("/devices/validate-pair-code") {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, validateResp.status)
        val validateBody = Json.parseToJsonElement(validateResp.bodyAsText()).jsonObject
        assertFalse(validateBody["valid"]?.jsonPrimitive?.boolean ?: true)

        val enrollResp = client.post("/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, enrollResp.status)
        val enrollBody = Json.parseToJsonElement(enrollResp.bodyAsText()).jsonObject
        assertEquals("Pairing code revoked", enrollBody["error"]?.jsonPrimitive?.content)
    }
}
