package com.beettechnologies.posly.security

import com.beettechnologies.posly.cart.ConfirmPaymentRequest
import com.beettechnologies.posly.cart.PaymentRecord
import com.beettechnologies.posly.cart.PaymentRecordResponse
import com.beettechnologies.posly.cart.RefundLineItem
import com.beettechnologies.posly.cart.RefundLineItemRequest
import com.beettechnologies.posly.cart.RefundLineItemResponse
import com.beettechnologies.posly.cart.RefundRecord
import com.beettechnologies.posly.cart.RefundRecordResponse
import com.beettechnologies.posly.cart.RefundRequest
import com.beettechnologies.posly.payments.CreatePaymentRequest
import com.beettechnologies.posly.payments.GatewayPayment
import com.beettechnologies.posly.payments.PaymentResponse
import com.beettechnologies.posly.payments.RefundAttempt
import com.beettechnologies.posly.payments.RefundAttemptResponse
import com.beettechnologies.posly.payments.RefundPaymentRequest
import com.beettechnologies.posly.payments.WebhookPayload
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ticket's literal "code scan for sensitive data storage" suggested test: a real, automated
 * guardrail (not just a doc claim) that the payment/refund request, domain, and response classes
 * never grow a raw PAN/CVV/track-data field - see SECURITY_COMPLIANCE.md's PCI boundary section,
 * which asserts this codebase never receives, stores, or transmits real card data. If this test
 * ever fails, someone added a field that would pull this system back into PCI PAN scope.
 */
class PciScopeGuardTest {

    /** Every class that carries a card payment/refund through a request, the domain, or a response. */
    private val classesInScope: List<KClass<*>> = listOf(
        // payments/PaymentModels.kt
        GatewayPayment::class,
        RefundAttempt::class,
        // payments/PaymentDtos.kt
        CreatePaymentRequest::class,
        PaymentResponse::class,
        RefundPaymentRequest::class,
        WebhookPayload::class,
        RefundAttemptResponse::class,
        // cart/CartModels.kt
        PaymentRecord::class,
        RefundLineItem::class,
        RefundRecord::class,
        // cart/CartDtos.kt
        PaymentRecordResponse::class,
        RefundLineItemResponse::class,
        RefundRecordResponse::class,
        ConfirmPaymentRequest::class,
        RefundLineItemRequest::class,
        RefundRequest::class
    )

    /** The one legitimate "card number"-ish field: a fabricated, already-masked display string - see GatewayPayment.maskedCardNumber's own doc comment. */
    private val allowedCardNumberLikeFields = setOf("maskedcardnumber")

    private val alwaysForbiddenSubstrings = listOf("cvv", "cvc", "track1", "track2", "expirymonth", "expiryyear", "primaryaccountnumber")

    @Test
    fun `no payment or refund class carries a raw card number, CVV, or track-data field`() {
        val violations = mutableListOf<String>()

        for (klass in classesInScope) {
            for (property in klass.memberProperties) {
                val name = property.name.lowercase()

                if (alwaysForbiddenSubstrings.any { name.contains(it) }) {
                    violations += "${klass.simpleName}.${property.name} looks like raw card/CVV/track data"
                }
                if (name == "pan" || (name.contains("cardnumber") && name !in allowedCardNumberLikeFields)) {
                    violations += "${klass.simpleName}.${property.name} looks like a raw PAN field, not the allowed masked display value"
                }
            }
        }

        assertTrue(violations.isEmpty(), "PCI scope violation(s) found:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `every class in scope was actually reflectable`() {
        // Guards against the scan silently checking zero properties for every class if a rename
        // ever breaks one of the imports above - each of these classes has at least one property.
        for (klass in classesInScope) {
            assertTrue(klass.memberProperties.isNotEmpty(), "${klass.simpleName} had no reflectable properties - is the import still correct?")
        }
    }
}
