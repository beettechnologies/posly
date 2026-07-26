package com.beettechnologies.posly.reporting

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

sealed class RealtimeSalesOutcome {
    data class Success(val sales: SalesAggregateResponse) : RealtimeSalesOutcome()
    data object Forbidden : RealtimeSalesOutcome()
    data class NetworkError(val message: String) : RealtimeSalesOutcome()
}

sealed class TopProductsOutcome {
    data class Success(val products: List<ProductSalesSummaryResponse>) : TopProductsOutcome()
    data object Forbidden : TopProductsOutcome()
    data class NetworkError(val message: String) : TopProductsOutcome()
}

sealed class CashOnHandOutcome {
    data class Success(val cashOnHand: CashOnHandResponse) : CashOnHandOutcome()
    data object Forbidden : CashOnHandOutcome()
    data class NetworkError(val message: String) : CashOnHandOutcome()
}

interface ReportingApi {
    suspend fun getRealtimeSales(storeId: String): RealtimeSalesOutcome
    suspend fun getTopProducts(storeId: String, limit: Int = 5): TopProductsOutcome
    suspend fun getCashOnHand(storeId: String): CashOnHandOutcome
}

class KtorReportingApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : ReportingApi {

    override suspend fun getRealtimeSales(storeId: String): RealtimeSalesOutcome = try {
        val response = httpClient.get("$baseUrl/reports/sales/realtime") {
            url { parameters.append("storeId", storeId) }
        }
        when (response.status) {
            HttpStatusCode.OK -> RealtimeSalesOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> RealtimeSalesOutcome.Forbidden
            else -> RealtimeSalesOutcome.NetworkError(errorMessage(response) ?: "Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RealtimeSalesOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getTopProducts(storeId: String, limit: Int): TopProductsOutcome = try {
        val response = httpClient.get("$baseUrl/reports/top-products") {
            url {
                parameters.append("storeId", storeId)
                parameters.append("limit", limit.toString())
            }
        }
        when (response.status) {
            HttpStatusCode.OK -> TopProductsOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> TopProductsOutcome.Forbidden
            else -> TopProductsOutcome.NetworkError(errorMessage(response) ?: "Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TopProductsOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getCashOnHand(storeId: String): CashOnHandOutcome = try {
        val response = httpClient.get("$baseUrl/reports/cash-on-hand") {
            url { parameters.append("storeId", storeId) }
        }
        when (response.status) {
            HttpStatusCode.OK -> CashOnHandOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> CashOnHandOutcome.Forbidden
            else -> CashOnHandOutcome.NetworkError(errorMessage(response) ?: "Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CashOnHandOutcome.NetworkError(e.message ?: "Network error")
    }

    private suspend fun errorMessage(response: io.ktor.client.statement.HttpResponse): String? =
        runCatching { response.body<ErrorResponse>().error }.getOrNull()
}
