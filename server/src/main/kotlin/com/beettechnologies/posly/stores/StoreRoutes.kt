package com.beettechnologies.posly.stores

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.tokenClaims
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.*
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureStoreRoutes(storeService: StoreService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN) {
                route("/stores") {
                    post {
                        val request = runCatching { call.receive<CreateStoreRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.name.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("name is required"))
                            return@post
                        }
                        if (request.address.line1.isBlank() || request.address.city.isBlank() ||
                            request.address.postalCode.isBlank() || request.address.country.isBlank()
                        ) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("address line1, city, postalCode, and country are required")
                            )
                            return@post
                        }

                        when (
                            val result = storeService.createStore(
                                name = request.name,
                                address = request.address.toModel(),
                                timezone = request.timezone,
                                currency = request.currency,
                                taxProfileId = request.taxProfileId,
                                locale = request.locale
                            )
                        ) {
                            is CreateStoreResult.Created -> call.respond(HttpStatusCode.Created, result.store.toResponse())
                            is CreateStoreResult.InvalidTimezone -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("'${result.timezone}' is not a valid timezone")
                            )
                            is CreateStoreResult.InvalidCurrency -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("'${result.currency}' is not a valid ISO 4217 currency code")
                            )
                            is CreateStoreResult.InvalidLocale -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("'${result.locale}' is not a valid locale tag")
                            )
                            CreateStoreResult.TaxProfileNotFound -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("taxProfileId does not reference an existing tax profile")
                            )
                        }
                    }

                    get {
                        call.respond(HttpStatusCode.OK, storeService.listStores().map { it.toResponse() })
                    }

                    get("/{id}") {
                        val id = call.parameters["id"]!!
                        val store = storeService.getStore(id)
                        if (store == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, store.toResponse())
                        }
                    }

                    put("/{id}") {
                        val id = call.parameters["id"]!!
                        val request = runCatching { call.receive<UpdateStoreRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@put
                        }

                        when (
                            val result = storeService.updateStore(
                                id = id,
                                name = request.name,
                                address = request.address?.toModel(),
                                timezone = request.timezone,
                                currency = request.currency,
                                taxProfileId = request.taxProfileId,
                                locale = request.locale
                            )
                        ) {
                            is UpdateStoreResult.Updated -> call.respond(HttpStatusCode.OK, result.store.toResponse())
                            UpdateStoreResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                            is UpdateStoreResult.InvalidTimezone -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("'${result.timezone}' is not a valid timezone")
                            )
                            is UpdateStoreResult.InvalidCurrency -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("'${result.currency}' is not a valid ISO 4217 currency code")
                            )
                            is UpdateStoreResult.InvalidLocale -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("'${result.locale}' is not a valid locale tag")
                            )
                            UpdateStoreResult.TaxProfileNotFound -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("taxProfileId does not reference an existing tax profile")
                            )
                        }
                    }

                    delete("/{id}") {
                        val id = call.parameters["id"]!!
                        if (storeService.deleteStore(id)) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                        }
                    }

                    post("/{id}/logo") {
                        val id = call.parameters["id"]!!
                        val multipart = call.receiveMultipart()
                        var fileName: String? = null
                        var bytes: ByteArray? = null
                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem && bytes == null) {
                                val partBytes = part.streamProvider().readBytes()
                                if (partBytes.isNotEmpty()) {
                                    fileName = part.originalFileName ?: "logo"
                                    bytes = partBytes
                                }
                            }
                            part.dispose()
                        }
                        val uploadedBytes = bytes
                        if (uploadedBytes == null) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("No image file provided in multipart request"))
                            return@post
                        }
                        when (val result = storeService.uploadLogo(id, fileName ?: "logo", uploadedBytes)) {
                            is UploadLogoResult.Success -> {
                                AuditService.record(
                                    AuditEvent.STORE_LOGO_UPDATED,
                                    userId = call.tokenClaims()?.userId,
                                    detail = "storeId=$id"
                                )
                                call.respond(HttpStatusCode.Created, LogoUploadResponse(logoUrl = result.logoUrl))
                            }
                            UploadLogoResult.StoreNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                            is UploadLogoResult.InvalidImage -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                        }
                    }

                    get("/{id}/logo") {
                        val id = call.parameters["id"]!!
                        val logo = storeService.getLogo(id)
                        if (logo == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("No logo uploaded for this store"))
                        } else {
                            call.respondBytes(logo.bytes, contentTypeForFileName(logo.fileName))
                        }
                    }
                }
            }
        }
    }
}

private fun contentTypeForFileName(fileName: String): ContentType = when (fileName.substringAfterLast('.', "").lowercase()) {
    "png" -> ContentType.Image.PNG
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "gif" -> ContentType.Image.GIF
    "svg" -> ContentType.Image.SVG
    else -> ContentType.Application.OctetStream
}

private fun AddressDto.toModel() = Address(
    line1 = line1,
    line2 = line2,
    city = city,
    state = state,
    postalCode = postalCode,
    country = country
)
