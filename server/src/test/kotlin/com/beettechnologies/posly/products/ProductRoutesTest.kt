package com.beettechnologies.posly.products

import com.beettechnologies.posly.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.test.*

class ProductRoutesTest {

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

    private suspend fun managerToken(client: HttpClient): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"manager","password":"manager123"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun adminToken(client: HttpClient): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin123"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun cashierToken(client: HttpClient): String {
        val resp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"cashier","password":"cashier123"}""")
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    // -------------------------------------------------------------------------
    // Create product
    // -------------------------------------------------------------------------

    @Test
    fun `create product with valid data returns 201 and id`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val resp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"sku":"PROD-001","name":"Coffee Mug","price":9.99,"taxCategory":"STANDARD",
                   "modifiers":[{"name":"Size","options":["S","M","L"],"additionalCost":0.5}]}"""
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertNotNull(body["id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `create product persists SKU and can be fetched by id`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val createResp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"PROD-002","name":"Tea Cup","price":5.49,"taxCategory":"REDUCED"}""")
        }
        assertEquals(HttpStatusCode.Created, createResp.status)
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val getResp = client.get("/products/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getResp.status)
        val body = Json.parseToJsonElement(getResp.bodyAsText()).jsonObject
        assertEquals("PROD-002", body["sku"]?.jsonPrimitive?.content)
        assertEquals("Tea Cup", body["name"]?.jsonPrimitive?.content)
        assertEquals(5.49, body["price"]?.jsonPrimitive?.double)
        assertEquals("REDUCED", body["taxCategory"]?.jsonPrimitive?.content)
        assertNotNull(body["modifiers"]?.jsonArray)
        assertNotNull(body["imageUrls"]?.jsonArray)
    }

    // -------------------------------------------------------------------------
    // Duplicate SKU
    // -------------------------------------------------------------------------

    @Test
    fun `duplicate SKU returns 409 with descriptive message`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"DUPE-SKU","name":"First","price":1.0,"taxCategory":"STANDARD"}""")
        }

        val dupeResp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"DUPE-SKU","name":"Second","price":2.0,"taxCategory":"STANDARD"}""")
        }
        assertEquals(HttpStatusCode.Conflict, dupeResp.status)
        val body = Json.parseToJsonElement(dupeResp.bodyAsText()).jsonObject
        val errorMsg = body["error"]?.jsonPrimitive?.content ?: ""
        assertTrue(errorMsg.contains("DUPE-SKU"), "Error message should mention the duplicate SKU")
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    fun `create product with blank SKU returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val resp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"  ","name":"Item","price":1.0,"taxCategory":"STANDARD"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `create product with negative price returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val resp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"NEG-001","name":"Item","price":-5.0,"taxCategory":"STANDARD"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `create product with invalid taxCategory returns 400`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val resp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"TAX-001","name":"Item","price":1.0,"taxCategory":"INVALID"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // -------------------------------------------------------------------------
    // Update product
    // -------------------------------------------------------------------------

    @Test
    fun `update price and modifier returns updated product`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val createResp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"UPD-001","name":"Widget","price":10.0,"taxCategory":"STANDARD"}""")
        }
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updateResp = client.put("/products/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"price":15.99,"modifiers":[{"name":"Color","options":["Red","Blue"],"additionalCost":1.0}]}"""
            )
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)
        val body = Json.parseToJsonElement(updateResp.bodyAsText()).jsonObject
        assertEquals(15.99, body["price"]?.jsonPrimitive?.double)
        val modifiers = body["modifiers"]?.jsonArray
        assertNotNull(modifiers)
        assertEquals(1, modifiers.size)
        assertEquals("Color", modifiers[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update non-existent product returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val resp = client.put("/products/non-existent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"price":5.0}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // -------------------------------------------------------------------------
    // Delete product
    // -------------------------------------------------------------------------

    @Test
    fun `admin can delete product`() = testApplication {
        configureApp()
        val client = jsonClient()
        val adminTok = adminToken(client)

        val createResp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"DEL-001","name":"Deletable","price":1.0,"taxCategory":"STANDARD"}""")
        }
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val delResp = client.delete("/products/$id") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(HttpStatusCode.NoContent, delResp.status)

        val getResp = client.get("/products/$id") {
            header(HttpHeaders.Authorization, "Bearer $adminTok")
        }
        assertEquals(HttpStatusCode.NotFound, getResp.status)
    }

    @Test
    fun `cashier cannot create product (403)`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = cashierToken(client)

        val resp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"CASHIER-001","name":"Item","price":1.0,"taxCategory":"STANDARD"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // -------------------------------------------------------------------------
    // Image upload
    // -------------------------------------------------------------------------

    @Test
    fun `upload image to product returns 201 and imageUrl`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val createResp = client.post("/products") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"sku":"IMG-001","name":"Picture Item","price":3.0,"taxCategory":"STANDARD"}""")
        }
        val id = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val imageBytes = "fake-image-data".toByteArray()
        val uploadResp = client.post("/products/$id/images") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(
                formData {
                    append("file", imageBytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"test.jpg\"")
                        append(HttpHeaders.ContentType, "image/jpeg")
                    })
                }
            ))
        }
        assertEquals(HttpStatusCode.Created, uploadResp.status)
        val body = Json.parseToJsonElement(uploadResp.bodyAsText()).jsonObject
        val imageUrl = body["imageUrl"]?.jsonPrimitive?.content
        assertNotNull(imageUrl)
        assertTrue(imageUrl!!.contains(id))

        // Verify the image URL appears on the product
        val getResp = client.get("/products/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val productBody = Json.parseToJsonElement(getResp.bodyAsText()).jsonObject
        val imageUrls = productBody["imageUrls"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(imageUrls)
        assertTrue(imageUrls!!.contains(imageUrl))
    }

    @Test
    fun `upload image to non-existent product returns 404`() = testApplication {
        configureApp()
        val client = jsonClient()
        val token = managerToken(client)

        val resp = client.post("/products/non-existent/images") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(
                formData {
                    append("file", "data".toByteArray(), Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"test.jpg\"")
                    })
                }
            ))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // -------------------------------------------------------------------------
    // Auth guard
    // -------------------------------------------------------------------------

    @Test
    fun `unauthenticated request to products returns 401`() = testApplication {
        configureApp()
        val client = jsonClient()
        val resp = client.get("/products/some-id")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
