package com.beettechnologies.posly.email

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureEmailRoutes(emailService: EmailService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER) {
                post("/orders/{id}/email-receipt") {
                    val orderId = call.parameters["id"]!!
                    val request = runCatching { call.receive<EmailReceiptRequest>() }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                        return@post
                    }

                    when (val result = emailService.sendReceipt(orderId, request.recipient)) {
                        is SendReceiptEmailResult.Success -> call.respond(HttpStatusCode.OK, result.email.toResponse())
                        is SendReceiptEmailResult.Failed -> call.respond(HttpStatusCode.BadGateway, result.email.toResponse())
                        SendReceiptEmailResult.OrderNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Order not found"))
                        is SendReceiptEmailResult.InvalidEmail -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                    }
                }
            }
        }
    }
}
