package com.beettechnologies.posly.receipts

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class ListPrintersOutcome {
    data class Success(val printers: List<PrinterResponse>) : ListPrintersOutcome()
    data class NetworkError(val message: String) : ListPrintersOutcome()
}

sealed class PrintReceiptOutcome {
    data class Printed(val job: PrintJobResponse) : PrintReceiptOutcome()
    data class Queued(val job: PrintJobResponse) : PrintReceiptOutcome()
    data object OrderNotFound : PrintReceiptOutcome()
    data object PrinterNotFound : PrintReceiptOutcome()
    data class NetworkError(val message: String) : PrintReceiptOutcome()
}

sealed class EmailReceiptOutcome {
    data class Sent(val email: EmailReceiptResponse) : EmailReceiptOutcome()
    data class Failed(val email: EmailReceiptResponse) : EmailReceiptOutcome()
    data object OrderNotFound : EmailReceiptOutcome()
    data class InvalidEmail(val message: String) : EmailReceiptOutcome()
    data class NetworkError(val message: String) : EmailReceiptOutcome()
}

interface ReceiptApi {
    suspend fun listPrinters(storeId: String): ListPrintersOutcome
    suspend fun printReceipt(orderId: String, printerId: String): PrintReceiptOutcome
    suspend fun emailReceipt(orderId: String, recipient: String): EmailReceiptOutcome
}

class KtorReceiptApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : ReceiptApi {

    override suspend fun listPrinters(storeId: String): ListPrintersOutcome = try {
        val response = httpClient.get("$baseUrl/printers") {
            parameter("storeId", storeId)
        }
        when (response.status) {
            HttpStatusCode.OK -> ListPrintersOutcome.Success(response.body())
            else -> ListPrintersOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ListPrintersOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun printReceipt(orderId: String, printerId: String): PrintReceiptOutcome = try {
        val response = httpClient.post("$baseUrl/orders/$orderId/print") {
            contentType(ContentType.Application.Json)
            setBody(PrintReceiptRequest(printerId = printerId))
        }
        when (response.status) {
            HttpStatusCode.OK -> PrintReceiptOutcome.Printed(response.body())
            HttpStatusCode.Accepted -> PrintReceiptOutcome.Queued(response.body())
            HttpStatusCode.NotFound -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                if (error?.error == "Printer not found") PrintReceiptOutcome.PrinterNotFound else PrintReceiptOutcome.OrderNotFound
            }
            else -> PrintReceiptOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PrintReceiptOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun emailReceipt(orderId: String, recipient: String): EmailReceiptOutcome = try {
        val response = httpClient.post("$baseUrl/orders/$orderId/email-receipt") {
            contentType(ContentType.Application.Json)
            setBody(EmailReceiptRequest(recipient = recipient))
        }
        when (response.status) {
            HttpStatusCode.OK -> EmailReceiptOutcome.Sent(response.body())
            HttpStatusCode.BadGateway -> EmailReceiptOutcome.Failed(response.body())
            HttpStatusCode.NotFound -> EmailReceiptOutcome.OrderNotFound
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                EmailReceiptOutcome.InvalidEmail(error?.error ?: "Invalid email address")
            }
            else -> EmailReceiptOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EmailReceiptOutcome.NetworkError(e.message ?: "Network error")
    }
}
