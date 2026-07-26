package com.beettechnologies.posly.webhooks

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
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureWebhookRoutes(webhookService: WebhookService) {
    routing {
        authenticate("jwt-auth") {
            // Integrations management: no dedicated integrations role exists yet, so this reuses
            // ADMIN/MANAGER, the same set already trusted for other privileged/finance-adjacent surfaces.
            withRole(Role.ADMIN, Role.MANAGER) {
                route("/webhooks/subscriptions") {
                    post {
                        val request = runCatching { call.receive<RegisterWebhookRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val eventTypes = request.eventTypes.map {
                            runCatching { WebhookEventType.valueOf(it) }.getOrElse {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown eventType '$it'"))
                                return@post
                            }
                        }.toSet()

                        when (val result = webhookService.register(request.url, request.secret, eventTypes)) {
                            is RegisterWebhookResult.Success -> call.respond(HttpStatusCode.Created, result.subscription.toResponse())
                            is RegisterWebhookResult.InvalidRequest -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                        }
                    }

                    get {
                        call.respond(HttpStatusCode.OK, webhookService.listSubscriptions().map { it.toResponse() })
                    }

                    get("/{id}") {
                        val subscription = webhookService.getSubscription(call.parameters["id"]!!)
                        if (subscription == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Subscription not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, subscription.toResponse())
                        }
                    }
                }

                route("/webhooks/deliveries") {
                    get {
                        val subscriptionId = call.request.queryParameters["subscriptionId"]
                        val status = call.request.queryParameters["status"]?.let {
                            runCatching { WebhookDeliveryStatus.valueOf(it) }.getOrElse {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown status '$it'"))
                                return@get
                            }
                        }
                        call.respond(HttpStatusCode.OK, webhookService.listDeliveries(subscriptionId, status).map { it.toResponse() })
                    }

                    // Dedicated surface for deliveries that exhausted retries - mirrors
                    // /payments/refunds/unresolved as a named "needs attention" view.
                    get("/dead-letter") {
                        call.respond(HttpStatusCode.OK, webhookService.listDeadLettered().map { it.toResponse() })
                    }
                }
            }
        }
    }
}
