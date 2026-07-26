package com.beettechnologies.posly.webhooks

import java.time.Instant
import java.util.UUID

enum class WebhookEventType { ORDER_CREATED, PAYMENT_SUCCEEDED }

data class WebhookSubscription(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val secret: String,
    val eventTypes: Set<WebhookEventType>,
    val createdAt: Instant
)

enum class WebhookDeliveryStatus { PENDING, DELIVERED, DEAD_LETTERED }

/**
 * One event's delivery to one subscription. [attempts] and [lastError]/[lastStatusCode] reflect
 * the most recent HTTP attempt; there's no per-attempt history kept (mirrors how
 * `RefundAttempt` tracks a running `attempts` counter rather than every individual try). The whole
 * retry sequence for one delivery happens within a single delivery run (exponential backoff via
 * [com.beettechnologies.posly.gateway.RetryPolicy]) and ends in either [WebhookDeliveryStatus.DELIVERED]
 * or [WebhookDeliveryStatus.DEAD_LETTERED] - there is no separate scheduled-retry-later state.
 */
data class WebhookDelivery(
    val id: String = UUID.randomUUID().toString(),
    val subscriptionId: String,
    val eventType: WebhookEventType,
    val eventId: String,
    val payload: String,
    val status: WebhookDeliveryStatus,
    val attempts: Int,
    val lastError: String? = null,
    val lastStatusCode: Int? = null,
    val createdAt: Instant,
    val deliveredAt: Instant? = null,
    val deadLetteredAt: Instant? = null
)
