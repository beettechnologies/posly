package com.beettechnologies.posly.printing

import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.gateway.GatewayException
import com.beettechnologies.posly.gateway.RetryPolicy
import com.beettechnologies.posly.receipts.ReceiptRenderer
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.Store
import com.beettechnologies.posly.stores.StoreService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Falls back to sensible defaults when [storeService] is unavailable or an order's storeId no longer resolves to a real store, so receipt rendering degrades gracefully rather than throwing. */
private fun resolveStore(storeService: StoreService?, storeId: String): Store =
    storeService?.getStore(storeId) ?: Store(
        id = storeId,
        name = "Store",
        address = Address(line1 = "", city = "", postalCode = "", country = ""),
        timezone = "UTC",
        currency = "USD"
    )

enum class PrintJobStatus { PRINTED, QUEUED }

data class PrintJob(
    val id: String = UUID.randomUUID().toString(),
    val orderId: String,
    val printerId: String,
    val status: PrintJobStatus,
    val message: String? = null,
    val createdAt: Instant
)

sealed class PrintJobResult {
    data class Success(val job: PrintJob) : PrintJobResult()
    data class Queued(val job: PrintJob) : PrintJobResult()
    data object OrderNotFound : PrintJobResult()
    data object PrinterNotFound : PrintJobResult()
}

/**
 * Submits a receipt print job for an order. A printer that's [PrinterStatus.OFFLINE] is never even
 * attempted - the job is immediately queued. An ONLINE printer is attempted through [PrintGateway]
 * with retry/backoff; if that still fails (transient retries exhausted, or a permanent gateway
 * error), the job is queued rather than surfaced as a dead end - printing always has a graceful
 * next step (retry later, or fall back to emailing the receipt), never an unrecoverable error for
 * the cashier.
 */
class PrintService(
    private val orderService: OrderService,
    private val printerRegistryService: PrinterRegistryService,
    private val gateway: PrintGateway,
    private val storeService: StoreService? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val nowProvider: () -> Instant = { Instant.now() }
) {
    private val jobs = ConcurrentHashMap<String, PrintJob>()

    suspend fun submitPrintJob(orderId: String, printerId: String): PrintJobResult {
        val order = orderService.getOrder(orderId) ?: return PrintJobResult.OrderNotFound
        val printer = printerRegistryService.getPrinter(printerId) ?: return PrintJobResult.PrinterNotFound
        val now = nowProvider()

        if (printer.status == PrinterStatus.OFFLINE) {
            return queue(orderId, printerId, now, "Printer is offline")
        }

        return try {
            val store = resolveStore(storeService, order.storeId)
            val content = ReceiptRenderer.renderThermalText(order, store)
            retryPolicy.withBackoff { gateway.print(printerId, content) }
            val job = PrintJob(orderId = orderId, printerId = printerId, status = PrintJobStatus.PRINTED, createdAt = now)
            jobs[job.id] = job
            PrintJobResult.Success(job)
        } catch (e: GatewayException) {
            queue(orderId, printerId, now, e.message)
        }
    }

    fun listJobs(orderId: String): List<PrintJob> = jobs.values.filter { it.orderId == orderId }

    private fun queue(orderId: String, printerId: String, now: Instant, message: String?): PrintJobResult.Queued {
        val job = PrintJob(orderId = orderId, printerId = printerId, status = PrintJobStatus.QUEUED, message = message, createdAt = now)
        jobs[job.id] = job
        return PrintJobResult.Queued(job)
    }
}
