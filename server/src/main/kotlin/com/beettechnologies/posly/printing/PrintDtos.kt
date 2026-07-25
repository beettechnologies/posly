package com.beettechnologies.posly.printing

import kotlinx.serialization.Serializable

@Serializable
data class RegisterPrinterRequest(val storeId: String, val name: String, val connectionType: String)

@Serializable
data class SetPrinterStatusRequest(val status: String)

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

fun PrinterRecord.toResponse() = PrinterResponse(
    id = id,
    storeId = storeId,
    name = name,
    connectionType = connectionType.name,
    status = status.name,
    registeredAt = registeredAt.toString()
)

fun PrintJob.toResponse() = PrintJobResponse(
    id = id,
    orderId = orderId,
    printerId = printerId,
    status = status.name,
    message = message,
    createdAt = createdAt.toString()
)
