package com.beettechnologies.posly.cart

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

sealed class CreateCartOutcome {
    data class Success(val cart: CartResponse) : CreateCartOutcome()
    data class Rejected(val message: String) : CreateCartOutcome()
    data class NetworkError(val message: String) : CreateCartOutcome()
}

sealed class GetCartOutcome {
    data class Success(val cart: CartResponse) : GetCartOutcome()
    data object NotFound : GetCartOutcome()
    data class NetworkError(val message: String) : GetCartOutcome()
}

sealed class AddCartItemOutcome {
    data class Success(val cart: CartResponse) : AddCartItemOutcome()
    data object CartNotFound : AddCartItemOutcome()
    data class Rejected(val message: String) : AddCartItemOutcome()
    data class NetworkError(val message: String) : AddCartItemOutcome()
}

interface CartApi {
    suspend fun createCart(storeId: String): CreateCartOutcome
    suspend fun getCart(id: String): GetCartOutcome
    suspend fun addItem(cartId: String, productId: String, quantity: Int): AddCartItemOutcome
}

class KtorCartApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : CartApi {

    override suspend fun createCart(storeId: String): CreateCartOutcome = try {
        val response = httpClient.post("$baseUrl/carts") {
            contentType(ContentType.Application.Json)
            setBody(CreateCartRequest(storeId))
        }
        when (response.status) {
            HttpStatusCode.Created -> CreateCartOutcome.Success(response.body())
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                CreateCartOutcome.Rejected(error?.error ?: "Unable to create cart")
            }
            else -> CreateCartOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CreateCartOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getCart(id: String): GetCartOutcome = try {
        val response = httpClient.get("$baseUrl/carts/$id")
        when (response.status) {
            HttpStatusCode.OK -> GetCartOutcome.Success(response.body())
            HttpStatusCode.NotFound -> GetCartOutcome.NotFound
            else -> GetCartOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GetCartOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun addItem(cartId: String, productId: String, quantity: Int): AddCartItemOutcome = try {
        val response = httpClient.post("$baseUrl/carts/$cartId/items") {
            contentType(ContentType.Application.Json)
            setBody(AddCartItemRequest(productId = productId, quantity = quantity))
        }
        when (response.status) {
            HttpStatusCode.OK -> AddCartItemOutcome.Success(response.body())
            HttpStatusCode.NotFound -> AddCartItemOutcome.CartNotFound
            HttpStatusCode.Conflict, HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                AddCartItemOutcome.Rejected(error?.error ?: "Unable to add item")
            }
            else -> AddCartItemOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AddCartItemOutcome.NetworkError(e.message ?: "Network error")
    }
}
