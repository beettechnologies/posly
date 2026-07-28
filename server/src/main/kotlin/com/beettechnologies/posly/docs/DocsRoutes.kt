package com.beettechnologies.posly.docs

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** application/yaml has no dedicated Ktor constant - registered per RFC 9512. */
private val YAML_CONTENT_TYPE = ContentType("application", "yaml")

/**
 * Publishes the hand-authored OpenAPI spec (`openapi.yaml` at the repo root, copied onto the
 * classpath by `server/build.gradle.kts`'s `processResources` task so it's available in every
 * environment, including the production fat JAR) plus a minimal Swagger UI wrapper for browsing
 * it. Both routes are deliberately public/unauthenticated - API documentation, unlike the API
 * itself, has no reason to require a token to read.
 */
fun Application.configureDocsRoutes() {
    val specBytes = object {}.javaClass.getResourceAsStream("/openapi.yaml")?.readBytes()
        ?: error("openapi.yaml not found on the classpath - check server/build.gradle.kts's processResources task")

    routing {
        get("/openapi.yaml") {
            call.respondBytes(specBytes, YAML_CONTENT_TYPE)
        }

        get("/docs") {
            call.respondText(SWAGGER_UI_HTML, ContentType.Text.Html)
        }
    }
}

// Loads Swagger UI from a CDN rather than vendoring the bundle - see DATA_MIGRATION.md-style
// known-limitations note in API_DOCS.md: this page needs internet access to render; /openapi.yaml
// itself never does.
private val SWAGGER_UI_HTML = """
    <!DOCTYPE html>
    <html>
    <head>
        <title>Posly API Docs</title>
        <meta charset="utf-8"/>
        <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"/>
    </head>
    <body>
        <div id="swagger-ui"></div>
        <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
        <script>
            window.onload = () => {
                window.ui = SwaggerUIBundle({
                    url: '/openapi.yaml',
                    dom_id: '#swagger-ui'
                });
            };
        </script>
    </body>
    </html>
""".trimIndent()
