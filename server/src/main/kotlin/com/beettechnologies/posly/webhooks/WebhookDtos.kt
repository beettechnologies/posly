package com.beettechnologies.posly.webhooks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RegisterWebhookRequest(val url: String, val secret: String, val eventTypes: List<String>)

@Serializable
data class WebhookSubscriptionResponse(
    val id: String,
    val url: String,
    val eventTypes: List<String>,
    val createdAt: String
)

@Serializable
data class WebhookDeliveryResponse(
    val id: String,
    val subscriptionId: String,
    val eventType: String,
    val eventId: String,
    val status: String,
    val attempts: Int,
    val lastError: String? = null,
    val lastStatusCode: Int? = null,
    val createdAt: String,
    val deliveredAt: String? = null,
    val deadLetteredAt: String? = null
)

/** The signed body posted to a subscriber's URL - `data` is the affected resource's normal API representation. */
@Serializable
data class WebhookEventEnvelope(
    val id: String,
    val type: String,
    val occurredAt: String,
    val data: JsonElement
)

fun WebhookSubscription.toResponse() = WebhookSubscriptionResponse(
    id = id,
    url = url,
    eventTypes = eventTypes.map { it.name },
    createdAt = createdAt.toString()
)

fun WebhookDelivery.toResponse() = WebhookDeliveryResponse(
    id = id,
    subscriptionId = subscriptionId,
    eventType = eventType.name,
    eventId = eventId,
    status = status.name,
    attempts = attempts,
    lastError = lastError,
    lastStatusCode = lastStatusCode,
    createdAt = createdAt.toString(),
    deliveredAt = deliveredAt?.toString(),
    deadLetteredAt = deadLetteredAt?.toString()
)
