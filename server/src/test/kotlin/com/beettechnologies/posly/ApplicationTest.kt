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
    fun testMetricsEndpoint() = testApplication {
        configureApp()
        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ktor_http_server_requests_seconds"))
    }

    @Test
    fun testCorrelationIdEchoedInResponseHeader() = testApplication {
        configureApp()
        val expectedCorrelationId = "obs-correlation-test-id"
        val response = client.get("/health") {
            header("X-Correlation-Id", expectedCorrelationId)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(expectedCorrelationId, response.headers["X-Correlation-Id"])
    }
}