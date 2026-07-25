package com.beettechnologies.posly.payments

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class CreatePaymentOutcome {
    data class Success(val payment: PaymentResponse) : CreatePaymentOutcome()
    data object OrderNotFound : CreatePaymentOutcome()
    data class GatewayError(val message: String) : CreatePaymentOutcome()
    data class NetworkError(val message: String) : CreatePaymentOutcome()
}

sealed class GetPaymentOutcome {
    data class Success(val payment: PaymentResponse) : GetPaymentOutcome()
    data object NotFound : GetPaymentOutcome()
    data class NetworkError(val message: String) : GetPaymentOutcome()
}

interface PaymentApi {
    suspend fun createPayment(orderId: String, amount: Double, currency: String = "USD"): CreatePaymentOutcome
    suspend fun getPayment(id: String): GetPaymentOutcome
}

class KtorPaymentApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : PaymentApi {

    override suspend fun createPayment(orderId: String, amount: Double, currency: String): CreatePaymentOutcome = try {
        val response = httpClient.post("$baseUrl/payments") {
            contentType(ContentType.Application.Json)
            setBody(CreatePaymentRequest(orderId = orderId, amount = amount, currency = currency))
        }
        when (response.status) {
            HttpStatusCode.Created -> CreatePaymentOutcome.Success(response.body())
            HttpStatusCode.NotFound -> CreatePaymentOutcome.OrderNotFound
            HttpStatusCode.BadGateway -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                CreatePaymentOutcome.GatewayError(error?.error ?: "The card terminal is unavailable")
            }
            else -> CreatePaymentOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CreatePaymentOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getPayment(id: String): GetPaymentOutcome = try {
        val response = httpClient.get("$baseUrl/payments/$id")
        when (response.status) {
            HttpStatusCode.OK -> GetPaymentOutcome.Success(response.body())
            HttpStatusCode.NotFound -> GetPaymentOutcome.NotFound
            else -> GetPaymentOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GetPaymentOutcome.NetworkError(e.message ?: "Network error")
    }
}
