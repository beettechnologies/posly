package com.beettechnologies.posly.products

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

sealed class SearchOutcome {
    data class Success(val response: SearchResponse) : SearchOutcome()
    data class NetworkError(val message: String) : SearchOutcome()
}

interface ProductSearchApi {
    suspend fun search(
        query: String? = null,
        barcode: String? = null,
        category: String? = null,
        inStock: Boolean? = null,
        page: Int = 0,
        size: Int = 20
    ): SearchOutcome
}

class KtorProductSearchApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : ProductSearchApi {

    override suspend fun search(
        query: String?,
        barcode: String?,
        category: String?,
        inStock: Boolean?,
        page: Int,
        size: Int
    ): SearchOutcome = try {
        val response = httpClient.get("$baseUrl/search") {
            url {
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
                query?.let { parameters.append("q", it) }
                barcode?.let { parameters.append("barcode", it) }
                category?.let { parameters.append("category", it) }
                inStock?.let { parameters.append("in_stock", it.toString()) }
            }
        }
        when (response.status) {
            HttpStatusCode.OK -> SearchOutcome.Success(response.body())
            else -> SearchOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SearchOutcome.NetworkError(e.message ?: "Network error")
    }
}
