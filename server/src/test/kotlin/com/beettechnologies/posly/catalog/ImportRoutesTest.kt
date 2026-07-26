package com.beettechnologies.posly.catalog

import com.beettechnologies.posly.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.test.*

class ImportRoutesTest {

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

    private suspend fun HttpClient.loginAs(username: String, password: String): String {
        val resp = post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.uploadCsv(token: String, csv: String): JsonObject {
        val resp = post("/products/import/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", csv.toByteArray(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"products.csv\"")
                            append(HttpHeaders.ContentType, "text/csv")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    private val standardMappingBody = """{"mapping":{"SKU":"sku","NAME":"name","PRICE":"price"}}"""

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    @Test
    fun `uploading a CSV returns headers and a row preview`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val body = client.uploadCsv(token, "sku,name,price\nSKU-1,Widget,9.99\n")
        assertEquals(listOf("sku", "name", "price"), body["headers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, body["totalRows"]!!.jsonPrimitive.int)
        assertNotNull(body["fileId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `uploading without a file returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val resp = client.post("/products/import/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData { }))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `cashier cannot upload an import file`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("cashier", "cashier123")

        val resp = client.post("/products/import/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData { append("file", "sku,name,price".toByteArray()) }))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // -------------------------------------------------------------------------
    // Dry run
    // -------------------------------------------------------------------------

    @Test
    fun `dry-run reports a duplicate SKU error with its row number`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val uploadBody = client.uploadCsv(token, "sku,name,price\nSKU-1,Widget,9.99\nSKU-1,Widget Dup,10.99\n")
        val fileId = uploadBody["fileId"]!!.jsonPrimitive.content

        val resp = client.post("/products/import/$fileId/dry-run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(standardMappingBody)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val outcomes = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["outcomes"]!!.jsonArray
        assertEquals("CREATED", outcomes[0].jsonObject["action"]!!.jsonPrimitive.content)
        val second = outcomes[1].jsonObject
        assertEquals("ERROR", second["action"]!!.jsonPrimitive.content)
        assertEquals(2, second["rowNumber"]!!.jsonPrimitive.int)
    }

    @Test
    fun `dry-run against an unknown file returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val resp = client.post("/products/import/does-not-exist/dry-run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(standardMappingBody)
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `dry-run with an unknown mapping field returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val uploadBody = client.uploadCsv(token, "sku,name,price\nSKU-1,Widget,9.99\n")
        val fileId = uploadBody["fileId"]!!.jsonPrimitive.content

        val resp = client.post("/products/import/$fileId/dry-run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"mapping":{"NOT_A_FIELD":"sku"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // -------------------------------------------------------------------------
    // Start + poll + rollback
    // -------------------------------------------------------------------------

    @Test
    fun `starting an import, polling it, and rolling it back all work end to end`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val uploadBody = client.uploadCsv(token, "sku,name,price\nSKU-1,Widget,9.99\n")
        val fileId = uploadBody["fileId"]!!.jsonPrimitive.content

        val startResp = client.post("/products/import/$fileId/start") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(standardMappingBody)
        }
        assertEquals(HttpStatusCode.Created, startResp.status)
        val jobId = Json.parseToJsonElement(startResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // Application.module() wires a real CoroutineScope, so by the time the response body is
        // available the import (a handful of synchronous ProductService calls) has had time to run;
        // poll a couple of times to avoid a hard timing dependency.
        var job: JsonObject? = null
        repeat(20) {
            val pollResp = client.get("/products/import/jobs/$jobId") { header(HttpHeaders.Authorization, "Bearer $token") }
            val body = Json.parseToJsonElement(pollResp.bodyAsText()).jsonObject
            if (body["status"]!!.jsonPrimitive.content == "COMPLETED") {
                job = body
                return@repeat
            }
            kotlinx.coroutines.delay(50)
        }
        val completedJob = requireNotNull(job) { "import job did not complete in time" }
        assertEquals(1, completedJob["createdCount"]!!.jsonPrimitive.int)
        assertNotNull(productExistsBySku(client, token, "SKU-1"))

        val rollbackResp = client.post("/products/import/jobs/$jobId/rollback") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, rollbackResp.status)
        assertTrue(Json.parseToJsonElement(rollbackResp.bodyAsText()).jsonObject["rolledBack"]!!.jsonPrimitive.boolean)
        assertNull(productExistsBySku(client, token, "SKU-1"))
    }

    private suspend fun productExistsBySku(client: HttpClient, token: String, sku: String): String? {
        val resp = client.get("/products") { header(HttpHeaders.Authorization, "Bearer $token") }
        val products = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        return products.map { it.jsonObject }.find { it["sku"]!!.jsonPrimitive.content == sku }?.get("id")?.jsonPrimitive?.content
    }

    @Test
    fun `merchandiser cannot roll back an import`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val uploadBody = client.uploadCsv(token, "sku,name,price\nSKU-1,Widget,9.99\n")
        val fileId = uploadBody["fileId"]!!.jsonPrimitive.content
        val startResp = client.post("/products/import/$fileId/start") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(standardMappingBody)
        }
        val jobId = Json.parseToJsonElement(startResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val merchToken = registerAndLoginMerchandiser(client)
        val resp = client.post("/products/import/jobs/$jobId/rollback") {
            header(HttpHeaders.Authorization, "Bearer $merchToken")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    private suspend fun registerAndLoginMerchandiser(client: HttpClient): String {
        val adminToken = client.loginAs("admin", "admin123")
        client.post("/users/invite") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"username":"merch1","email":"merch1@example.com","roles":["MERCHANDISER"]}""")
        }.let { inviteResp ->
            val inviteToken = Json.parseToJsonElement(inviteResp.bodyAsText()).jsonObject["inviteToken"]!!.jsonPrimitive.content
            client.post("/users/accept-invite") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$inviteToken","newPassword":"merchpass123"}""")
            }
        }
        return client.loginAs("merch1", "merchpass123")
    }

    @Test
    fun `rolling back an unknown job returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")

        val resp = client.post("/products/import/jobs/does-not-exist/rollback") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
