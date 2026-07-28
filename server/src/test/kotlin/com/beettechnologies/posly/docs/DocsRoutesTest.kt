package com.beettechnologies.posly.docs

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig
import com.beettechnologies.posly.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocsRoutesTest {

    private fun configureApp() {
        TestDatabase.reset()
    }

    @Test
    fun `GET openapi yaml is public and returns the spec`() = testApplication {
        configureApp()
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

        // No Authorization header at all - this route must be reachable without a token.
        val resp = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("application/yaml", resp.contentType()?.let { "${it.contentType}/${it.contentSubtype}" })

        val body = resp.bodyAsText()
        assertTrue(body.contains("openapi: 3.0.3"))
        assertTrue(body.contains("title: Posly API"))
        assertTrue(body.contains("/auth/login"))
    }

    @Test
    fun `GET docs is public and returns a Swagger UI page pointing at the spec`() = testApplication {
        configureApp()
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

        val resp = client.get("/docs")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("text/html", resp.contentType()?.let { "${it.contentType}/${it.contentSubtype}" })

        val body = resp.bodyAsText()
        assertTrue(body.contains("/openapi.yaml"))
        assertTrue(body.contains("SwaggerUIBundle"))
    }
}

private fun io.ktor.client.statement.HttpResponse.contentType() = this.headers["Content-Type"]?.let {
    io.ktor.http.ContentType.parse(it)
}
