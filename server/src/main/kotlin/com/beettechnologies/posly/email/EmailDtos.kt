package com.beettechnologies.posly.email

import kotlinx.serialization.Serializable

@Serializable
data class EmailReceiptRequest(val recipient: String)

@Serializable
data class EmailReceiptResponse(
    val id: String,
    val orderId: String,
    val recipient: String,
    val status: String,
    val message: String?,
    val sentAt: String
)

fun EmailRecord.toResponse() = EmailReceiptResponse(
    id = id,
    orderId = orderId,
    recipient = recipient,
    status = status.name,
    message = message,
    sentAt = sentAt.toString()
)
