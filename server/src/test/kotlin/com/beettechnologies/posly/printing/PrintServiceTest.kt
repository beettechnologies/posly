package com.beettechnologies.posly.printing

import com.beettechnologies.posly.cart.Cart
import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.gateway.RetryPolicy
import com.beettechnologies.posly.products.TaxCategory
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val FAST_RETRY = RetryPolicy(maxAttempts = 3, initialDelayMillis = 1, backoffFactor = 1.0)

private fun seedCart(): Cart {
    val now = Instant.parse("2026-01-01T00:00:00Z")
    return Cart(
        id = "cart-1",
        storeId = "store-1",
        createdBy = "cashier-1",
        items = listOf(
            CartItem(productId = "product-1", productName = "Widget", quantity = 1, unitPrice = 10.0, taxCategory = TaxCategory.STANDARD)
        ),
        createdAt = now,
        updatedAt = now
    )
}

private fun seedTotals() = CartTotals(
    subtotal = 10.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
    taxableAmount = 10.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 10.0
)

class PrintServiceTest {

    @Test
    fun `printing to an online printer succeeds immediately`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val printers = PrinterRegistryService()
        val printer = printers.registerPrinter("store-1", "Front", PrinterConnectionType.USB)
        val service = PrintService(orderService, printers, SimulatorPrintGateway())

        val result = service.submitPrintJob(order.id, printer.id)

        val success = assertIs<PrintJobResult.Success>(result)
        assertEquals(PrintJobStatus.PRINTED, success.job.status)
    }

    @Test
    fun `an offline printer is never attempted - the job is immediately queued`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val printers = PrinterRegistryService()
        val printer = printers.registerPrinter("store-1", "Front", PrinterConnectionType.USB)
        printers.setStatus(printer.id, PrinterStatus.OFFLINE)
        val service = PrintService(orderService, printers, SimulatorPrintGateway())

        val result = service.submitPrintJob(order.id, printer.id)

        val queued = assertIs<PrintJobResult.Queued>(result)
        assertEquals(PrintJobStatus.QUEUED, queued.job.status)
        assertEquals("Printer is offline", queued.job.message)
    }

    @Test
    fun `a transient printer failure is retried and eventually succeeds`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val printers = PrinterRegistryService()
        val printer = printers.registerPrinter("store-1", "Front", PrinterConnectionType.USB)
        val service = PrintService(
            orderService, printers,
            SimulatorPrintGateway(transientFailuresBeforeSuccess = 2),
            retryPolicy = FAST_RETRY
        )

        val result = service.submitPrintJob(order.id, printer.id)

        assertIs<PrintJobResult.Success>(result)
        Unit
    }

    @Test
    fun `a transient printer failure that exhausts retries is queued, not a dead end`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val printers = PrinterRegistryService()
        val printer = printers.registerPrinter("store-1", "Front", PrinterConnectionType.USB)
        val service = PrintService(
            orderService, printers,
            SimulatorPrintGateway(transientFailuresBeforeSuccess = 10),
            retryPolicy = FAST_RETRY
        )

        val result = service.submitPrintJob(order.id, printer.id)

        assertIs<PrintJobResult.Queued>(result)
        Unit
    }

    @Test
    fun `printing for an unknown order or printer is rejected`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val printers = PrinterRegistryService()
        val printer = printers.registerPrinter("store-1", "Front", PrinterConnectionType.USB)
        val service = PrintService(orderService, printers, SimulatorPrintGateway())

        assertEquals(PrintJobResult.OrderNotFound, service.submitPrintJob("does-not-exist", printer.id))
        assertEquals(PrintJobResult.PrinterNotFound, service.submitPrintJob(order.id, "does-not-exist"))
    }
}
