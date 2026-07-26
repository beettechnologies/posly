package com.beettechnologies.posly.auth

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig

import com.beettechnologies.posly.module
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

class UserRoutesTest {

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

    private suspend fun io.ktor.client.HttpClient.loginAs(username: String, password: String): String {
        val resp = post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    // -------------------------------------------------------------------------
    // Admin CRUD - RBAC boundary
    // -------------------------------------------------------------------------

    @Test
    fun `listing users without admin role returns 403`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("cashier", "cashier123")
        val response = client.get("/users") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `admin can list seeded users`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val response = client.get("/users") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        val usernames = body.map { it.jsonObject["username"]!!.jsonPrimitive.content }
        assertTrue(usernames.containsAll(listOf("admin", "manager", "cashier")))
    }

    // -------------------------------------------------------------------------
    // Invite -> accept-invite -> login
    // -------------------------------------------------------------------------

    @Test
    fun `admin invites a user who then accepts and logs in with roles and store access intact`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = client.loginAs("admin", "admin123")

        val inviteResp = client.post("/users/invite") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"username":"newhire","email":"newhire@example.com","roles":["MANAGER"],"storeIds":["store-1"]}""")
        }
        assertEquals(HttpStatusCode.Created, inviteResp.status)
        val inviteBody = Json.parseToJsonElement(inviteResp.bodyAsText()).jsonObject
        assertTrue(inviteBody["emailDelivered"]!!.jsonPrimitive.boolean)
        val inviteToken = inviteBody["inviteToken"]!!.jsonPrimitive.content
        val userJson = inviteBody["user"]!!.jsonObject
        assertEquals("INVITED", userJson["status"]!!.jsonPrimitive.content)
        assertEquals(listOf("store-1"), userJson["storeIds"]!!.jsonArray.map { it.jsonPrimitive.content })

        val acceptResp = client.post("/users/accept-invite") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$inviteToken","newPassword":"brandnewpass123"}""")
        }
        assertEquals(HttpStatusCode.NoContent, acceptResp.status)

        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"newhire","password":"brandnewpass123"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResp.status)
    }

    @Test
    fun `inviting an existing username returns 409`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = client.loginAs("admin", "admin123")
        val response = client.post("/users/invite") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"username":"cashier","email":"dupe@example.com","roles":["CASHIER"]}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `accept-invite with an invalid token returns 401`() = testApplication {
        configureApp()
        val client = jsonClient()
        val response = client.post("/users/accept-invite") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"garbage","newPassword":"whatever123"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -------------------------------------------------------------------------
    // Role/status/store-access updates + immediate revocation
    // -------------------------------------------------------------------------

    @Test
    fun `admin updates a user's roles and the change is reflected immediately`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = client.loginAs("admin", "admin123")

        val usersResp = client.get("/users") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val cashierId = Json.parseToJsonElement(usersResp.bodyAsText()).jsonArray
            .first { it.jsonObject["username"]!!.jsonPrimitive.content == "cashier" }
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.patch("/users/$cashierId/roles") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"roles":["MANAGER"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val updated = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(listOf("MANAGER"), updated["roles"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a role change instantly invalidates the user's already-issued access token`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierToken = client.loginAs("cashier", "cashier123")

        // Sanity: the token works before the role change.
        val before = client.get("/protected/me") { header(HttpHeaders.Authorization, "Bearer $cashierToken") }
        assertEquals(HttpStatusCode.OK, before.status)

        val adminToken = client.loginAs("admin", "admin123")
        val usersResp = client.get("/users") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val cashierId = Json.parseToJsonElement(usersResp.bodyAsText()).jsonArray
            .first { it.jsonObject["username"]!!.jsonPrimitive.content == "cashier" }
            .jsonObject["id"]!!.jsonPrimitive.content
        client.patch("/users/$cashierId/roles") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"roles":["MANAGER"]}""")
        }

        // The OLD access token (stamped with the old roleVersion) must be rejected now, without waiting for expiry.
        val after = client.get("/protected/me") { header(HttpHeaders.Authorization, "Bearer $cashierToken") }
        assertEquals(HttpStatusCode.Unauthorized, after.status)
    }

    @Test
    fun `disabling a user instantly invalidates their access token and blocks future logins`() = testApplication {
        configureApp()
        val client = jsonClient()
        val cashierToken = client.loginAs("cashier", "cashier123")
        val adminToken = client.loginAs("admin", "admin123")

        val usersResp = client.get("/users") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val cashierId = Json.parseToJsonElement(usersResp.bodyAsText()).jsonArray
            .first { it.jsonObject["username"]!!.jsonPrimitive.content == "cashier" }
            .jsonObject["id"]!!.jsonPrimitive.content

        val disableResp = client.patch("/users/$cashierId/status") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DISABLED"}""")
        }
        assertEquals(HttpStatusCode.OK, disableResp.status)

        val after = client.get("/protected/me") { header(HttpHeaders.Authorization, "Bearer $cashierToken") }
        assertEquals(HttpStatusCode.Unauthorized, after.status)

        val loginAttempt = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"cashier","password":"cashier123"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, loginAttempt.status)
    }

    @Test
    fun `admin updates a user's store access`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = client.loginAs("admin", "admin123")
        val usersResp = client.get("/users") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val cashierId = Json.parseToJsonElement(usersResp.bodyAsText()).jsonArray
            .first { it.jsonObject["username"]!!.jsonPrimitive.content == "cashier" }
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.patch("/users/$cashierId/store-access") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"storeIds":["store-1","store-2"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val updated = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("store-1", "store-2"), updated["storeIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `updating roles for an unknown user returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = client.loginAs("admin", "admin123")
        val response = client.patch("/users/does-not-exist/roles") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"roles":["MANAGER"]}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // -------------------------------------------------------------------------
    // Audit log
    // -------------------------------------------------------------------------

    @Test
    fun `audit log surfaces a role change for the affected user`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = client.loginAs("admin", "admin123")
        val usersResp = client.get("/users") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        val cashierId = Json.parseToJsonElement(usersResp.bodyAsText()).jsonArray
            .first { it.jsonObject["username"]!!.jsonPrimitive.content == "cashier" }
            .jsonObject["id"]!!.jsonPrimitive.content

        client.patch("/users/$cashierId/roles") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"roles":["MANAGER"]}""")
        }

        val response = client.get("/users/audit-log?event=USER_ROLES_CHANGED") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val entries = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertTrue(entries.any { it.jsonObject["userId"]!!.jsonPrimitive.content == cashierId })
    }

    // -------------------------------------------------------------------------
    // SSO configure + callback
    // -------------------------------------------------------------------------

    @Test
    fun `admin configures SSO and a matching assertion logs in with mapped role`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = client.loginAs("admin", "admin123")

        val configureResp = client.post("/users/sso/configure") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"providerName":"Okta","roleMappings":[{"externalGroup":"store-managers","role":"MANAGER"}],"defaultRoles":["CASHIER"],"enabled":true}"""
            )
        }
        assertEquals(HttpStatusCode.OK, configureResp.status)

        val callbackResp = client.post("/auth/sso/callback") {
            contentType(ContentType.Application.Json)
            setBody("""{"externalId":"okta|person-1","email":"person@example.com","externalGroups":["store-managers"]}""")
        }
        assertEquals(HttpStatusCode.OK, callbackResp.status)
        val callbackBody = Json.parseToJsonElement(callbackResp.bodyAsText()).jsonObject
        assertNotNull(callbackBody["accessToken"]?.jsonPrimitive?.contentOrNull)

        val accessToken = callbackBody["accessToken"]!!.jsonPrimitive.content
        val claims = Json.parseToJsonElement(
            String(java.util.Base64.getUrlDecoder().decode(accessToken.split(".")[1]))
        ).jsonObject
        assertEquals(listOf("MANAGER"), claims["roles"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `sso callback before SSO is configured returns 503`() = testApplication {
        configureApp()
        val client = jsonClient()
        val response = client.post("/auth/sso/callback") {
            contentType(ContentType.Application.Json)
            setBody("""{"externalId":"okta|person-1","email":"person@example.com","externalGroups":["store-managers"]}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `getting SSO configuration without admin role returns 403`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val response = client.get("/users/sso/configuration") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
