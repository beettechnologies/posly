package com.beettechnologies.posly.auth

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

class AuthRoutesTest {

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

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    @Test
    fun `login with valid credentials returns access and refresh tokens`() = testApplication {
        configureApp()
        val client = jsonClient()
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["accessToken"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(body["refreshToken"]?.jsonPrimitive?.contentOrNull)
        assertFalse(body["mfaRequired"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `login with invalid password returns 401`() = testApplication {
        configureApp()
        val client = jsonClient()
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["error"])
    }

    @Test
    fun `login with unknown user returns 401`() = testApplication {
        configureApp()
        val client = jsonClient()
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"nobody","password":"pass"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    @Test
    fun `valid refresh token returns new access token`() = testApplication {
        configureApp()
        val client = jsonClient()
        // First login
        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"cashier","password":"cashier123"}""")
        }
        val loginBody = Json.parseToJsonElement(loginResp.bodyAsText()).jsonObject
        val refreshToken = loginBody["refreshToken"]!!.jsonPrimitive.content

        // Refresh
        val refreshResp = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.OK, refreshResp.status)
        val refreshBody = Json.parseToJsonElement(refreshResp.bodyAsText()).jsonObject
        assertNotNull(refreshBody["accessToken"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `expired or invalid refresh token returns 401`() = testApplication {
        configureApp()
        val client = jsonClient()
        val response = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"not-a-valid-token"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `used refresh token cannot be reused after rotation`() = testApplication {
        configureApp()
        val client = jsonClient()
        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"manager","password":"manager123"}""")
        }
        val loginBody = Json.parseToJsonElement(loginResp.bodyAsText()).jsonObject
        val oldRefreshToken = loginBody["refreshToken"]!!.jsonPrimitive.content

        // Use it once
        client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$oldRefreshToken"}""")
        }

        // Use the same token again — must fail
        val replayResp = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$oldRefreshToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, replayResp.status)
    }

    // -------------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------------

    @Test
    fun `logout invalidates refresh token`() = testApplication {
        configureApp()
        val client = jsonClient()
        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"cashier","password":"cashier123"}""")
        }
        val loginBody = Json.parseToJsonElement(loginResp.bodyAsText()).jsonObject
        val refreshToken = loginBody["refreshToken"]!!.jsonPrimitive.content

        val logoutResp = client.post("/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.NoContent, logoutResp.status)

        // Refresh after logout must fail
        val afterLogout = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, afterLogout.status)
    }

    // -------------------------------------------------------------------------
    // MFA
    // -------------------------------------------------------------------------

    @Test
    fun `login with MFA-enabled account returns mfaRequired and mfaToken`() = testApplication {
        configureApp()
        val client = jsonClient()

        // Register a user with MFA enabled at the service level via a seeded user
        // We hit the login endpoint with the mfa-user injected via DI. Since the
        // module() re-creates a UserService on each test, we use a workaround:
        // create the MFA user through a helper endpoint (not available in prod) or
        // instead test via the AuthService directly.
        // Here we test via the HTTP layer by leveraging MfaService to compute the code.
        val mfaSecret = MfaService.generateSecret()

        // Directly exercise AuthService + JwtService for MFA flow
        val jwtService = JwtService(
            secret = "test-secret-at-least-32-characters-long!!",
            issuer = "posly",
            audience = "posly-api",
            accessTokenExpirationMs = 900_000L,
            refreshTokenExpirationMs = 604_800_000L,
            mfaTokenExpirationMs = 300_000L
        )
        val userService = UserService()
        userService.createUser("mfauser", "mfapass123", mfaEnabled = true, mfaSecret = mfaSecret)
        val authService = AuthService(userService, jwtService)

        // Login should return MFA required
        val loginResult = authService.login("mfauser", "mfapass123")
        assertIs<AuthResult.MfaRequired>(loginResult)
        val mfaToken = (loginResult as AuthResult.MfaRequired).mfaToken

        // Generate a valid TOTP code
        val code = generateCurrentTotpCode(mfaSecret)

        // MFA verify should succeed
        val mfaResult = authService.verifyMfa(mfaToken, code)
        assertIs<AuthResult.Success>(mfaResult)
    }

    @Test
    fun `MFA verify with wrong code returns MfaInvalid`() = testApplication {
        configureApp()
        val mfaSecret = MfaService.generateSecret()
        val jwtService = JwtService(
            secret = "test-secret-at-least-32-characters-long!!",
            issuer = "posly",
            audience = "posly-api",
            accessTokenExpirationMs = 900_000L,
            refreshTokenExpirationMs = 604_800_000L,
            mfaTokenExpirationMs = 300_000L
        )
        val userService = UserService()
        userService.createUser("mfauser2", "mfapass123", mfaEnabled = true, mfaSecret = mfaSecret)
        val authService = AuthService(userService, jwtService)

        val loginResult = authService.login("mfauser2", "mfapass123")
        assertIs<AuthResult.MfaRequired>(loginResult)
        val mfaToken = (loginResult as AuthResult.MfaRequired).mfaToken

        val result = authService.verifyMfa(mfaToken, "000000")
        assertIs<AuthResult.MfaInvalid>(result)
    }

    @Test
    fun `MFA verify via HTTP endpoint with invalid mfaToken returns 401`() = testApplication {
        configureApp()
        val client = jsonClient()
        val response = client.post("/auth/mfa/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"mfaToken":"invalid","code":"123456"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -------------------------------------------------------------------------
    // RBAC
    // -------------------------------------------------------------------------

    @Test
    fun `accessing protected route without token returns 401`() = testApplication {
        configureApp()
        val client = jsonClient()
        val response = client.get("/admin/dashboard")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `cashier accessing admin-only route returns 403`() = testApplication {
        configureApp()
        val client = jsonClient()

        // Login as cashier
        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"cashier","password":"cashier123"}""")
        }
        val loginBody = Json.parseToJsonElement(loginResp.bodyAsText()).jsonObject
        val accessToken = loginBody["accessToken"]!!.jsonPrimitive.content

        val response = client.get("/admin/dashboard") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `admin accessing admin-only route returns 200`() = testApplication {
        configureApp()
        val client = jsonClient()

        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin123"}""")
        }
        val loginBody = Json.parseToJsonElement(loginResp.bodyAsText()).jsonObject
        val accessToken = loginBody["accessToken"]!!.jsonPrimitive.content

        val response = client.get("/admin/dashboard") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `cashier accessing manager+admin route returns 403`() = testApplication {
        configureApp()
        val client = jsonClient()

        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"cashier","password":"cashier123"}""")
        }
        val loginBody = Json.parseToJsonElement(loginResp.bodyAsText()).jsonObject
        val accessToken = loginBody["accessToken"]!!.jsonPrimitive.content

        val response = client.get("/protected/reports") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `manager accessing manager+admin route returns 200`() = testApplication {
        configureApp()
        val client = jsonClient()

        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"manager","password":"manager123"}""")
        }
        val loginBody = Json.parseToJsonElement(loginResp.bodyAsText()).jsonObject
        val accessToken = loginBody["accessToken"]!!.jsonPrimitive.content

        val response = client.get("/protected/reports") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Generates the current TOTP code for [secret] using the same algorithm as MfaService. */
    private fun generateCurrentTotpCode(secret: String): String {
        val secretBytes = MfaService.decodeBase32(secret)
        val counter = System.currentTimeMillis() / 1000 / 30
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(secretBytes, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = (hash.last().toInt() and 0x0F)
        val truncated = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val otp = truncated % 1_000_000
        return otp.toString().padStart(6, '0')
    }
}
