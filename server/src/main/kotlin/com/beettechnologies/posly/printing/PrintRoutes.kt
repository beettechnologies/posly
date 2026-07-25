package com.beettechnologies.posly.printing

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configurePrintRoutes(printerRegistryService: PrinterRegistryService, printService: PrintService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN, Role.MANAGER) {
                route("/printers") {
                    post {
                        val request = runCatching { call.receive<RegisterPrinterRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.name.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("name is required"))
                            return@post
                        }
                        val connectionType = runCatching { PrinterConnectionType.valueOf(request.connectionType) }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("connectionType must be one of LOCAL, USB, CLOUD"))
                            return@post
                        }
                        val printer = printerRegistryService.registerPrinter(request.storeId, request.name, connectionType)
                        call.respond(HttpStatusCode.Created, printer.toResponse())
                    }

                    patch("/{id}/status") {
                        val id = call.parameters["id"]!!
                        val request = runCatching { call.receive<SetPrinterStatusRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@patch
                        }
                        val status = runCatching { PrinterStatus.valueOf(request.status) }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("status must be ONLINE or OFFLINE"))
                            return@patch
                        }
                        when (val result = printerRegistryService.setStatus(id, status)) {
                            is SetPrinterStatusResult.Success -> call.respond(HttpStatusCode.OK, result.printer.toResponse())
                            SetPrinterStatusResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Printer not found"))
                        }
                    }
                }
            }

            withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER) {
                get("/printers") {
                    val storeId = call.request.queryParameters["storeId"]
                    call.respond(HttpStatusCode.OK, printerRegistryService.listPrinters(storeId).map { it.toResponse() })
                }

                post("/orders/{id}/print") {
                    val orderId = call.parameters["id"]!!
                    val request = runCatching { call.receive<PrintReceiptRequest>() }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                        return@post
                    }

                    when (val result = printService.submitPrintJob(orderId, request.printerId)) {
                        is PrintJobResult.Success -> call.respond(HttpStatusCode.OK, result.job.toResponse())
                        is PrintJobResult.Queued -> call.respond(HttpStatusCode.Accepted, result.job.toResponse())
                        PrintJobResult.OrderNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Order not found"))
                        PrintJobResult.PrinterNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Printer not found"))
                    }
                }
            }
        }
    }
}
