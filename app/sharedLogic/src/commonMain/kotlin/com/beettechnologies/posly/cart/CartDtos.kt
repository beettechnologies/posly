package com.beettechnologies.posly.cart

import kotlinx.serialization.Serializable

@Serializable
data class CreateCartRequest(val storeId: String)

@Serializable
data class SelectedModifierRequest(val modifierId: String, val option: String)

@Serializable
data class DiscountDto(val type: String, val value: Double)

@Serializable
data class AddCartItemRequest(
    val productId: String,
    val quantity: Int,
    val selectedModifiers: List<SelectedModifierRequest> = emptyList(),
    val discount: DiscountDto? = null
)

@Serializable
data class UpdateCartItemQuantityRequest(val quantity: Int)

@Serializable
data class VoidCartItemRequest(val reason: String? = null)

@Serializable
data class SetCartDiscountRequest(val discount: DiscountDto?)

@Serializable
data class CheckoutRequest(val idempotencyKey: String)

@Serializable
data class SelectedModifierResponse(val modifierId: String, val option: String, val additionalCost: Double)

@Serializable
data class CartItemResponse(
    val id: String,
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPrice: Double,
    val taxCategory: String,
    val selectedModifiers: List<SelectedModifierResponse>,
    val discount: DiscountDto?,
    val lineSubtotal: Double,
    val lineDiscountAmount: Double,
    val lineTotal: Double
)

@Serializable
data class TaxBreakdownLineResponse(val name: String, val ratePercent: Double, val amount: Double)

@Serializable
data class CartTotalsResponse(
    val subtotal: Double,
    val itemDiscountTotal: Double,
    val cartDiscountAmount: Double,
    val taxableAmount: Double,
    val taxBreakdown: List<TaxBreakdownLineResponse>,
    val totalTax: Double,
    val total: Double
)

@Serializable
data class CartResponse(
    val id: String,
    val storeId: String,
    val status: String,
    val items: List<CartItemResponse>,
    val discount: DiscountDto?,
    val totals: CartTotalsResponse,
    val orderId: String? = null,
    val createdAt: String,
    val updatedAt: String
)
