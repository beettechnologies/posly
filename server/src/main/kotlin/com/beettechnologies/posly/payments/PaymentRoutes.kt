package com.beettechnologies.posly.payments

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

private const val WEBHOOK_SIGNATURE_HEADER = "X-Webhook-Signature"
private val webhookJson = Json { ignoreUnknownKeys = true }

fun Application.configurePaymentRoutes(paymentGatewayService: PaymentGatewayService) {
    routing {
        authenticate("jwt-auth") {
            route("/payments") {
                withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER) {
                    post {
                        val request = runCatching { call.receive<CreatePaymentRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.amount <= 0) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("amount must be positive"))
                            return@post
                        }

                        when (
                            val result = paymentGatewayService.createPayment(request.orderId, request.amount, request.currency)
                        ) {
                            is CreatePaymentResult.Success -> call.respond(HttpStatusCode.Created, result.payment.toResponse())
                            CreatePaymentResult.OrderNotFound -> call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("Order not found")
                            )
                            is CreatePaymentResult.GatewayError -> call.respond(
                                HttpStatusCode.BadGateway,
                                ErrorResponse(result.message)
                            )
                        }
                    }

                    get("/{id}") {
                        val payment = paymentGatewayService.getPayment(call.parameters["id"]!!)
                        if (payment == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Payment not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, payment.toResponse())
                        }
                    }
                }

                withRole(Role.ADMIN, Role.MANAGER) {
                    post("/{id}/refund") {
                        val id = call.parameters["id"]!!
                        val request = runCatching { call.receive<RefundPaymentRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.refundId.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("refundId is required"))
                            return@post
                        }

                        when (val result = paymentGatewayService.refund(id, request.refundId, request.amount)) {
                            is RefundPaymentResult.Success -> call.respond(HttpStatusCode.OK, result.payment.toResponse())
                            RefundPaymentResult.PaymentNotFound -> call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("Payment not found")
                            )
                            RefundPaymentResult.NotApproved -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Only an approved payment can be refunded")
                            )
                            is RefundPaymentResult.GatewayError -> call.respond(
                                HttpStatusCode.BadGateway,
                                ErrorResponse(result.message)
                            )
                        }
                    }
                }
            }
        }

        // Public: the gateway/terminal calls this directly and cannot attach our internal JWT.
        // Authenticity instead comes from the HMAC signature over the raw body.
        post("/payments/webhook") {
            val rawBody = call.receiveText()
            val signature = call.request.header(WEBHOOK_SIGNATURE_HEADER)
            if (!paymentGatewayService.verifySignature(rawBody, signature)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid webhook signature"))
                return@post
            }

            val payload = runCatching { webhookJson.decodeFromString<WebhookPayload>(rawBody) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid webhook payload"))
                return@post
            }
            val approved = when (payload.outcome.uppercase()) {
                "APPROVED" -> true
                "DECLINED" -> false
                else -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("outcome must be APPROVED or DECLINED"))
                    return@post
                }
            }

            when (
                val result = paymentGatewayService.handleWebhook(
                    eventId = payload.eventId,
                    terminalTransactionId = payload.terminalTransactionId,
                    approved = approved,
                    declineReason = payload.declineReason
                )
            ) {
                is WebhookResult.Success -> call.respond(HttpStatusCode.OK, result.payment.toResponse())
                WebhookResult.PaymentNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Payment not found"))
                WebhookResult.AlreadyProcessed -> call.respond(HttpStatusCode.OK, ErrorResponse("Event already processed"))
            }
        }
    }
}
