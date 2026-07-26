package com.beettechnologies.posly.orders

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

sealed class GetOrderOutcome {
    data class Success(val order: OrderResponse) : GetOrderOutcome()
    data object NotFound : GetOrderOutcome()
    data class NetworkError(val message: String) : GetOrderOutcome()
}

sealed class ConfirmPaymentOutcome {
    data class Success(val order: OrderResponse) : ConfirmPaymentOutcome()
    data object OrderNotFound : ConfirmPaymentOutcome()
    data class Rejected(val message: String) : ConfirmPaymentOutcome()
    data class NetworkError(val message: String) : ConfirmPaymentOutcome()
}

sealed class RefundOutcome {
    data class Success(val order: OrderResponse) : RefundOutcome()
    data object OrderNotFound : RefundOutcome()
    /** Validation failure - no card payment to refund, order not refundable, or window expired. */
    data class Rejected(val message: String) : RefundOutcome()
    /** The card refund attempt itself failed at the gateway - the caller should offer a manual fallback. */
    data class GatewayError(val message: String) : RefundOutcome()
    data class NetworkError(val message: String) : RefundOutcome()
}

sealed class ListOrdersOutcome {
    data class Success(val orders: List<OrderResponse>) : ListOrdersOutcome()
    data object Forbidden : ListOrdersOutcome()
    data class Rejected(val message: String) : ListOrdersOutcome()
    data class NetworkError(val message: String) : ListOrdersOutcome()
}

interface OrderApi {
    suspend fun getOrder(id: String): GetOrderOutcome
    suspend fun confirmPayment(orderId: String, method: String, amount: Double, reference: String? = null): ConfirmPaymentOutcome
    suspend fun refund(
        orderId: String,
        refundId: String,
        method: String,
        lineItems: List<RefundLineItemRequest>,
        reason: String? = null
    ): RefundOutcome
    /** [from]/[to] are ISO-8601 instant strings (e.g. "2026-01-15T00:00:00Z") - the drill-down transaction list for a dashboard metric. */
    suspend fun listOrders(storeId: String, from: String, to: String): ListOrdersOutcome
}

class KtorOrderApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : OrderApi {

    override suspend fun getOrder(id: String): GetOrderOutcome = try {
        val response = httpClient.get("$baseUrl/orders/$id")
        when (response.status) {
            HttpStatusCode.OK -> GetOrderOutcome.Success(response.body())
            HttpStatusCode.NotFound -> GetOrderOutcome.NotFound
            else -> GetOrderOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GetOrderOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun confirmPayment(
        orderId: String,
        method: String,
        amount: Double,
        reference: String?
    ): ConfirmPaymentOutcome = try {
        val response = httpClient.post("$baseUrl/orders/$orderId/payments") {
            contentType(ContentType.Application.Json)
            setBody(ConfirmPaymentRequest(method = method, amount = amount, reference = reference))
        }
        when (response.status) {
            HttpStatusCode.OK -> ConfirmPaymentOutcome.Success(response.body())
            HttpStatusCode.NotFound -> ConfirmPaymentOutcome.OrderNotFound
            HttpStatusCode.Conflict, HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                ConfirmPaymentOutcome.Rejected(error?.error ?: "Unable to confirm payment")
            }
            else -> ConfirmPaymentOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ConfirmPaymentOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun refund(
        orderId: String,
        refundId: String,
        method: String,
        lineItems: List<RefundLineItemRequest>,
        reason: String?
    ): RefundOutcome = try {
        val response = httpClient.post("$baseUrl/orders/$orderId/refund") {
            contentType(ContentType.Application.Json)
            setBody(RefundRequest(refundId = refundId, method = method, lineItems = lineItems, reason = reason))
        }
        when (response.status) {
            HttpStatusCode.OK -> RefundOutcome.Success(response.body())
            HttpStatusCode.NotFound -> RefundOutcome.OrderNotFound
            HttpStatusCode.BadGateway -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                RefundOutcome.GatewayError(error?.error ?: "The card terminal is unavailable")
            }
            HttpStatusCode.Conflict, HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                RefundOutcome.Rejected(error?.error ?: "Unable to process refund")
            }
            else -> RefundOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RefundOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun listOrders(storeId: String, from: String, to: String): ListOrdersOutcome = try {
        val response = httpClient.get("$baseUrl/orders") {
            url {
                parameters.append("storeId", storeId)
                parameters.append("from", from)
                parameters.append("to", to)
            }
        }
        when (response.status) {
            HttpStatusCode.OK -> ListOrdersOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> ListOrdersOutcome.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                ListOrdersOutcome.Rejected(error?.error ?: "Unable to list transactions")
            }
            else -> ListOrdersOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ListOrdersOutcome.NetworkError(e.message ?: "Network error")
    }
}
