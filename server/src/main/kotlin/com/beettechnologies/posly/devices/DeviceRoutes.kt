package com.beettechnologies.posly.devices

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.tokenClaims
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Instant

private fun DeviceRecord.toResponse(now: Instant) = DeviceResponse(
    id = id,
    storeId = storeId,
    name = name,
    terminalType = terminalType,
    enrolledAt = enrolledAt.toString(),
    status = status.name,
    healthStatus = healthStatus(now).name,
    lastSeenAt = lastSeenAt?.toString(),
    deprovisionedAt = deprovisionedAt?.toString()
)

fun Application.configureDeviceRoutes(deviceRegistryService: DeviceRegistryService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN, Role.MANAGER) {
                route("/devices") {
                    post("/create-pair-code") {
                        val request = runCatching { call.receive<CreatePairCodeRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.storeId.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId is required"))
                            return@post
                        }
                        val claims = call.tokenClaims()
                        val pairingCode = deviceRegistryService.createPairCode(
                            storeId = request.storeId,
                            createdBy = claims?.userId ?: "unknown",
                            expiresInSeconds = request.expiresInSeconds,
                            terminalType = request.terminalType
                        )
                        call.respond(
                            HttpStatusCode.Created,
                            PairCodeResponse(
                                code = pairingCode.code,
                                storeId = pairingCode.storeId,
                                expiresAt = pairingCode.expiresAt.toString(),
                                terminalType = pairingCode.terminalType
                            )
                        )
                    }

                    post("/validate-pair-code") {
                        val request = runCatching { call.receive<ValidatePairCodeRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }

                        when (val result = deviceRegistryService.validatePairCode(request.code)) {
                            is PairingCodeValidationResult.Valid -> call.respond(
                                HttpStatusCode.OK,
                                ValidatePairCodeResponse(
                                    valid = true,
                                    storeId = result.pairingCode.storeId,
                                    expiresAt = result.pairingCode.expiresAt.toString()
                                )
                            )
                            PairingCodeValidationResult.NotFound -> call.respond(
                                HttpStatusCode.NotFound,
                                ValidatePairCodeResponse(valid = false, error = "Pairing code not found")
                            )
                            PairingCodeValidationResult.Expired -> call.respond(
                                HttpStatusCode.BadRequest,
                                ValidatePairCodeResponse(valid = false, error = "Pairing code expired")
                            )
                            PairingCodeValidationResult.Used -> call.respond(
                                HttpStatusCode.BadRequest,
                                ValidatePairCodeResponse(valid = false, error = "Pairing code already used")
                            )
                            PairingCodeValidationResult.Revoked -> call.respond(
                                HttpStatusCode.BadRequest,
                                ValidatePairCodeResponse(valid = false, error = "Pairing code revoked")
                            )
                        }
                    }

                    post("/revoke-pair-code") {
                        val request = runCatching { call.receive<RevokePairCodeRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val claims = call.tokenClaims()

                        when (deviceRegistryService.revokePairCode(request.code, claims?.userId ?: "unknown")) {
                            PairCodeRevokeResult.Revoked -> call.respond(HttpStatusCode.OK)
                            PairCodeRevokeResult.NotFound -> call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("Pairing code not found")
                            )
                            PairCodeRevokeResult.AlreadyUsed -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Pairing code already used")
                            )
                            PairCodeRevokeResult.AlreadyRevoked -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Pairing code already revoked")
                            )
                        }
                    }

                    get {
                        val storeId = call.request.queryParameters["storeId"]
                        val now = Instant.now()
                        call.respond(
                            HttpStatusCode.OK,
                            deviceRegistryService.listDevices(storeId).map { it.toResponse(now) }
                        )
                    }

                    get("/{id}") {
                        val id = call.parameters["id"]!!
                        val device = deviceRegistryService.getDevice(id)
                        if (device == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Device not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, device.toResponse(Instant.now()))
                        }
                    }

                    post("/{id}/deprovision") {
                        val id = call.parameters["id"]!!
                        val claims = call.tokenClaims()
                        when (val result = deviceRegistryService.deprovisionDevice(id, claims?.userId ?: "unknown")) {
                            is DeprovisionResult.Success -> call.respond(HttpStatusCode.OK, result.device.toResponse(Instant.now()))
                            DeprovisionResult.NotFound -> call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("Device not found")
                            )
                            DeprovisionResult.AlreadyDeprovisioned -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Device is already deprovisioned")
                            )
                        }
                    }
                }
            }
        }

        post("/devices/heartbeat") {
            val request = runCatching { call.receive<HeartbeatRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                return@post
            }

            when (val result = deviceRegistryService.recordHeartbeat(request.clientId, request.clientSecret)) {
                is HeartbeatResult.Success -> call.respond(
                    HttpStatusCode.OK,
                    HeartbeatResponse(status = result.device.status.name, lastSeenAt = result.device.lastSeenAt.toString())
                )
                HeartbeatResult.InvalidCredentials -> call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Invalid device credentials")
                )
                HeartbeatResult.Deprovisioned -> call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Device has been deprovisioned")
                )
            }
        }

        post("/devices/enroll") {
            val request = runCatching { call.receive<EnrollDeviceRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                return@post
            }

            when (val result = deviceRegistryService.enrollDevice(request.code, request.storeId, request.name)) {
                is EnrollDeviceResult.Success -> call.respond(
                    HttpStatusCode.OK,
                    EnrollDeviceResponse(
                        deviceId = result.device.id,
                        storeId = result.device.storeId,
                        clientId = result.device.clientId,
                        clientSecret = result.device.clientSecret
                    )
                )
                EnrollDeviceResult.PairCodeNotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Pairing code not found")
                )
                EnrollDeviceResult.PairCodeExpired -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Pairing code expired")
                )
                EnrollDeviceResult.PairCodeUsed -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Pairing code already used")
                )
                EnrollDeviceResult.PairCodeRevoked -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Pairing code revoked")
                )
                EnrollDeviceResult.StoreMismatch -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Pairing code is not valid for the provided store")
                )
            }
        }
    }
}
