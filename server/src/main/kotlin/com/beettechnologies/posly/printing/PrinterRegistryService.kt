package com.beettechnologies.posly.printing

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class PrinterConnectionType { LOCAL, USB, CLOUD }

enum class PrinterStatus { ONLINE, OFFLINE }

data class PrinterRecord(
    val id: String = UUID.randomUUID().toString(),
    val storeId: String,
    val name: String,
    val connectionType: PrinterConnectionType,
    val status: PrinterStatus = PrinterStatus.ONLINE,
    val registeredAt: Instant
)

sealed class SetPrinterStatusResult {
    data class Success(val printer: PrinterRecord) : SetPrinterStatusResult()
    data object NotFound : SetPrinterStatusResult()
}

/**
 * Registry of receipt printers a store has configured - mirrors DeviceRegistryService's shape
 * (no storeId existence validation, matching that same existing precedent) but far simpler: no
 * pairing/enrollment flow, since a printer isn't a security-sensitive client-credential holder the
 * way a POS terminal device is. [PrinterStatus] is directly settable (rather than derived from a
 * heartbeat) so tests and demos can deterministically drive the offline-print-fallback flow.
 */
class PrinterRegistryService(private val nowProvider: () -> Instant = { Instant.now() }) {

    private val printers = ConcurrentHashMap<String, PrinterRecord>()

    fun registerPrinter(storeId: String, name: String, connectionType: PrinterConnectionType): PrinterRecord {
        val printer = PrinterRecord(storeId = storeId, name = name, connectionType = connectionType, registeredAt = nowProvider())
        printers[printer.id] = printer
        return printer
    }

    fun getPrinter(id: String): PrinterRecord? = printers[id]

    fun listPrinters(storeId: String? = null): List<PrinterRecord> =
        printers.values.filter { storeId == null || it.storeId == storeId }.sortedBy { it.registeredAt }

    fun setStatus(id: String, status: PrinterStatus): SetPrinterStatusResult {
        val existing = printers[id] ?: return SetPrinterStatusResult.NotFound
        val updated = existing.copy(status = status)
        printers[id] = updated
        return SetPrinterStatusResult.Success(updated)
    }
}
