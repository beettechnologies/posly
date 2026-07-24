package com.beettechnologies.posly

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {

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

    @Test
    fun testRoot() = testApplication {
        configureApp()
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, Ktor!", response.bodyAsText())
    }

    @Test
    fun testHealth() = testApplication {
        configureApp()
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `health request echoes correlation id and includes trace id`() = testApplication {
        configureApp()
        val response = client.get("/health") {
            header("X-Correlation-Id", "req-123")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("req-123", response.headers["X-Correlation-Id"])
        assertNotNull(response.headers["X-Trace-Id"])
    }

    @Test
    fun `metrics endpoint exposes auth counters after synthetic traffic`() = testApplication {
        configureApp()
        client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin123"}""")
        }

        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("posly_auth_login_total"))
    }
}