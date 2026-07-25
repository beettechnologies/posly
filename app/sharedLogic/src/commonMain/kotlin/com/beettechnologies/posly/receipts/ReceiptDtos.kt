package com.beettechnologies.posly.receipts

import kotlinx.serialization.Serializable

@Serializable
data class PrinterResponse(
    val id: String,
    val storeId: String,
    val name: String,
    val connectionType: String,
    val status: String,
    val registeredAt: String
)

@Serializable
data class PrintReceiptRequest(val printerId: String)

@Serializable
data class PrintJobResponse(
    val id: String,
    val orderId: String,
    val printerId: String,
    val status: String,
    val message: String?,
    val createdAt: String
)

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
