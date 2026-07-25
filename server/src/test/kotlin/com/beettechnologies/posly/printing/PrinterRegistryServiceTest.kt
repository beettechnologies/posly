package com.beettechnologies.posly.printing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PrinterRegistryServiceTest {

    @Test
    fun `a registered printer starts online`() {
        val service = PrinterRegistryService()

        val printer = service.registerPrinter("store-1", "Front Counter", PrinterConnectionType.USB)

        assertEquals(PrinterStatus.ONLINE, printer.status)
        assertEquals(PrinterConnectionType.USB, printer.connectionType)
        assertEquals(printer.id, service.getPrinter(printer.id)?.id)
    }

    @Test
    fun `listPrinters filters by store`() {
        val service = PrinterRegistryService()
        val a = service.registerPrinter("store-a", "Printer A", PrinterConnectionType.LOCAL)
        val b = service.registerPrinter("store-b", "Printer B", PrinterConnectionType.CLOUD)

        assertEquals(listOf(a.id), service.listPrinters(storeId = "store-a").map { it.id })
        assertEquals(setOf(a.id, b.id), service.listPrinters().map { it.id }.toSet())
    }

    @Test
    fun `setStatus toggles a printer offline and back online`() {
        val service = PrinterRegistryService()
        val printer = service.registerPrinter("store-1", "Front Counter", PrinterConnectionType.LOCAL)

        val offline = assertIs<SetPrinterStatusResult.Success>(service.setStatus(printer.id, PrinterStatus.OFFLINE))
        assertEquals(PrinterStatus.OFFLINE, offline.printer.status)

        val online = assertIs<SetPrinterStatusResult.Success>(service.setStatus(printer.id, PrinterStatus.ONLINE))
        assertEquals(PrinterStatus.ONLINE, online.printer.status)
    }

    @Test
    fun `setStatus for an unknown printer is rejected`() {
        val service = PrinterRegistryService()

        assertEquals(SetPrinterStatusResult.NotFound, service.setStatus("does-not-exist", PrinterStatus.OFFLINE))
        assertNull(service.getPrinter("does-not-exist"))
    }
}
