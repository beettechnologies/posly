package com.beettechnologies.posly.products

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

sealed class GetProductOutcome {
    data class Success(val product: ProductResponse) : GetProductOutcome()
    data object NotFound : GetProductOutcome()
    data class NetworkError(val message: String) : GetProductOutcome()
}

interface ProductApi {
    suspend fun getProduct(id: String): GetProductOutcome
}

class KtorProductApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : ProductApi {

    override suspend fun getProduct(id: String): GetProductOutcome = try {
        val response = httpClient.get("$baseUrl/products/$id")
        when (response.status) {
            HttpStatusCode.OK -> GetProductOutcome.Success(response.body())
            HttpStatusCode.NotFound -> GetProductOutcome.NotFound
            else -> GetProductOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GetProductOutcome.NetworkError(e.message ?: "Network error")
    }
}
