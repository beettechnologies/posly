package com.beettechnologies.posly.migration

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.TestDatabaseConfig
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

class SalesImportRoutesTest {

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

    private suspend fun HttpClient.loginAs(username: String, password: String): String {
        val resp = post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.seedStoreId(token: String): String {
        val resp = post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"Downtown","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},
                    |"timezone":"America/New_York","currency":"USD"}""".trimMargin()
            )
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.seedProductSku(token: String, sku: String, price: Double = 10.0): String {
        post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"$sku","name":"Widget","price":$price,"taxCategory":"STANDARD"}""")
        }
        return sku
    }

    private suspend fun HttpClient.uploadCsv(token: String, csv: String): JsonObject {
        val resp = post("/sales-import/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", csv.toByteArray(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"sales.csv\"")
                            append(HttpHeaders.ContentType, "text/csv")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    private val header = "orderRef,storeId,sku,qty,unitPrice,soldAt,paymentMethod,total,subtotal,tax,paymentRef,soldBy"
    private fun mappingBody() = """{"mapping":{
        "ORDER_REFERENCE":"orderRef","STORE_ID":"storeId","SKU":"sku","QUANTITY":"qty","UNIT_PRICE":"unitPrice",
        "SOLD_AT":"soldAt","PAYMENT_METHOD":"paymentMethod","TOTAL_AMOUNT":"total","SUBTOTAL":"subtotal",
        "TAX_AMOUNT":"tax","PAYMENT_REFERENCE":"paymentRef","SOLD_BY":"soldBy"
    }}""".trimIndent()

    @Test
    fun `uploading a CSV returns headers and a row preview`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val body = client.uploadCsv(token, "$header\nORD-1,store-1,SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1\n")
        assertEquals(12, body["headers"]!!.jsonArray.size)
        assertEquals(1, body["totalRows"]!!.jsonPrimitive.int)
        assertNotNull(body["fileId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `uploading without a file returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val resp = client.post("/sales-import/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData { }))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `cashier cannot upload a sales import file`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("cashier", "cashier123")

        val resp = client.post("/sales-import/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData { append("file", header.toByteArray()) }))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `dry-run reports matched and unmatched rows`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminToken = client.loginAs("admin", "admin123")
        val storeId = client.seedStoreId(adminToken)
        client.seedProductSku(adminToken, "SKU-1")
        val token = client.loginAs("manager", "manager123")

        val body = client.uploadCsv(
            token,
            "$header\n" +
                "ORD-1,$storeId,SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1\n" +
                "ORD-2,$storeId,SKU-MISSING,1,5.00,2024-01-01T00:00:00Z,CASH,5.00,5.00,0.00,,cashier-1\n"
        )
        val fileId = body["fileId"]!!.jsonPrimitive.content

        val resp = client.post("/sales-import/$fileId/dry-run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(mappingBody())
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val rowOutcomes = json["rowOutcomes"]!!.jsonArray
        assertEquals("MATCHED", rowOutcomes[0].jsonObject["result"]!!.jsonPrimitive.content)
        assertEquals("UNMATCHED", rowOutcomes[1].jsonObject["result"]!!.jsonPrimitive.content)
        val groups = json["groups"]!!.jsonArray
        assertEquals(2, groups.size)
    }

    @Test
    fun `dry-run against an unknown file returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")

        val resp = client.post("/sales-import/does-not-exist/dry-run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(mappingBody())
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `dry-run with an unknown mapping field returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("manager", "manager123")
        val body = client.uploadCsv(token, "$header\nORD-1,store-1,SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1\n")
        val fileId = body["fileId"]!!.jsonPrimitive.content

        val resp = client.post("/sales-import/$fileId/dry-run") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"mapping":{"NOT_A_FIELD":"sku"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `starting an import, polling it, fetching reconciliation, and rolling it back all work end to end`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val storeId = client.seedStoreId(token)
        client.seedProductSku(token, "SKU-1")

        val body = client.uploadCsv(token, "$header\nORD-1,$storeId,SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1\n")
        val fileId = body["fileId"]!!.jsonPrimitive.content

        val startResp = client.post("/sales-import/$fileId/start") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(mappingBody())
        }
        assertEquals(HttpStatusCode.Created, startResp.status)
        val jobId = Json.parseToJsonElement(startResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        var job: JsonObject? = null
        repeat(20) {
            val pollResp = client.get("/sales-import/jobs/$jobId") { header(HttpHeaders.Authorization, "Bearer $token") }
            val respBody = Json.parseToJsonElement(pollResp.bodyAsText()).jsonObject
            if (respBody["status"]!!.jsonPrimitive.content == "COMPLETED") {
                job = respBody
                return@repeat
            }
            kotlinx.coroutines.delay(50)
        }
        val completedJob = requireNotNull(job) { "sales import job did not complete in time" }
        assertEquals(1, completedJob["importedCount"]!!.jsonPrimitive.int)

        val reconResp = client.get("/sales-import/jobs/$jobId/reconciliation") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, reconResp.status)
        val recon = Json.parseToJsonElement(reconResp.bodyAsText()).jsonObject
        assertEquals(1, recon["importedCount"]!!.jsonPrimitive.int)
        assertEquals(1, recon["sampleMappings"]!!.jsonArray.size)
        assertEquals("ORD-1", recon["sampleMappings"]!!.jsonArray[0].jsonObject["orderReference"]!!.jsonPrimitive.content)

        val rollbackResp = client.post("/sales-import/jobs/$jobId/rollback") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, rollbackResp.status)
        assertTrue(Json.parseToJsonElement(rollbackResp.bodyAsText()).jsonObject["rolledBack"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `cashier cannot roll back a sales import`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")
        val storeId = client.seedStoreId(token)
        client.seedProductSku(token, "SKU-1")
        val body = client.uploadCsv(token, "$header\nORD-1,$storeId,SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1\n")
        val fileId = body["fileId"]!!.jsonPrimitive.content
        val startResp = client.post("/sales-import/$fileId/start") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(mappingBody())
        }
        val jobId = Json.parseToJsonElement(startResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val cashierToken = client.loginAs("cashier", "cashier123")
        val resp = client.post("/sales-import/jobs/$jobId/rollback") {
            header(HttpHeaders.Authorization, "Bearer $cashierToken")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `reconciliation for an unknown job returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = client.loginAs("admin", "admin123")

        val resp = client.get("/sales-import/jobs/does-not-exist/reconciliation") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
