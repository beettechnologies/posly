package com.beettechnologies.posly.cart

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
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

sealed class UpdateCartItemQuantityOutcome {
    data class Success(val cart: CartResponse) : UpdateCartItemQuantityOutcome()
    data object CartNotFound : UpdateCartItemQuantityOutcome()
    data object ItemNotFound : UpdateCartItemQuantityOutcome()
    data class Rejected(val message: String) : UpdateCartItemQuantityOutcome()
    data class NetworkError(val message: String) : UpdateCartItemQuantityOutcome()
}

sealed class RemoveCartItemOutcome {
    data class Success(val cart: CartResponse) : RemoveCartItemOutcome()
    data object CartNotFound : RemoveCartItemOutcome()
    data object ItemNotFound : RemoveCartItemOutcome()
    data class Rejected(val message: String) : RemoveCartItemOutcome()
    data class NetworkError(val message: String) : RemoveCartItemOutcome()
}

sealed class SetCartDiscountOutcome {
    data class Success(val cart: CartResponse) : SetCartDiscountOutcome()
    data object CartNotFound : SetCartDiscountOutcome()
    data class Rejected(val message: String) : SetCartDiscountOutcome()
    data class NetworkError(val message: String) : SetCartDiscountOutcome()
}

interface CartApi {
    suspend fun createCart(storeId: String): CreateCartOutcome
    suspend fun getCart(id: String): GetCartOutcome
    suspend fun addItem(
        cartId: String,
        productId: String,
        quantity: Int,
        selectedModifiers: List<SelectedModifierRequest> = emptyList(),
        discount: DiscountDto? = null
    ): AddCartItemOutcome
    suspend fun updateItemQuantity(cartId: String, itemId: String, quantity: Int): UpdateCartItemQuantityOutcome
    suspend fun removeItem(cartId: String, itemId: String, reason: String? = null): RemoveCartItemOutcome
    suspend fun setCartDiscount(cartId: String, discount: DiscountDto?): SetCartDiscountOutcome
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

    override suspend fun addItem(
        cartId: String,
        productId: String,
        quantity: Int,
        selectedModifiers: List<SelectedModifierRequest>,
        discount: DiscountDto?
    ): AddCartItemOutcome = try {
        val response = httpClient.post("$baseUrl/carts/$cartId/items") {
            contentType(ContentType.Application.Json)
            setBody(
                AddCartItemRequest(
                    productId = productId,
                    quantity = quantity,
                    selectedModifiers = selectedModifiers,
                    discount = discount
                )
            )
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

    override suspend fun updateItemQuantity(cartId: String, itemId: String, quantity: Int): UpdateCartItemQuantityOutcome = try {
        val response = httpClient.patch("$baseUrl/carts/$cartId/items/$itemId") {
            contentType(ContentType.Application.Json)
            setBody(UpdateCartItemQuantityRequest(quantity))
        }
        when (response.status) {
            HttpStatusCode.OK -> UpdateCartItemQuantityOutcome.Success(response.body())
            HttpStatusCode.NotFound -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                if (error?.error == "Item not found") {
                    UpdateCartItemQuantityOutcome.ItemNotFound
                } else {
                    UpdateCartItemQuantityOutcome.CartNotFound
                }
            }
            HttpStatusCode.Conflict, HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                UpdateCartItemQuantityOutcome.Rejected(error?.error ?: "Unable to update quantity")
            }
            else -> UpdateCartItemQuantityOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UpdateCartItemQuantityOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun removeItem(cartId: String, itemId: String, reason: String?): RemoveCartItemOutcome = try {
        val response = httpClient.delete("$baseUrl/carts/$cartId/items/$itemId") {
            contentType(ContentType.Application.Json)
            setBody(VoidCartItemRequest(reason))
        }
        when (response.status) {
            HttpStatusCode.OK -> RemoveCartItemOutcome.Success(response.body())
            HttpStatusCode.NotFound -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                if (error?.error == "Item not found") {
                    RemoveCartItemOutcome.ItemNotFound
                } else {
                    RemoveCartItemOutcome.CartNotFound
                }
            }
            HttpStatusCode.Conflict, HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                RemoveCartItemOutcome.Rejected(error?.error ?: "Unable to void item")
            }
            else -> RemoveCartItemOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RemoveCartItemOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun setCartDiscount(cartId: String, discount: DiscountDto?): SetCartDiscountOutcome = try {
        val response = httpClient.put("$baseUrl/carts/$cartId/discount") {
            contentType(ContentType.Application.Json)
            setBody(SetCartDiscountRequest(discount))
        }
        when (response.status) {
            HttpStatusCode.OK -> SetCartDiscountOutcome.Success(response.body())
            HttpStatusCode.NotFound -> SetCartDiscountOutcome.CartNotFound
            HttpStatusCode.Conflict, HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                SetCartDiscountOutcome.Rejected(error?.error ?: "Unable to apply discount")
            }
            else -> SetCartDiscountOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SetCartDiscountOutcome.NetworkError(e.message ?: "Network error")
    }
}
