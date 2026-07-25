package com.beettechnologies.posly.printing

import com.beettechnologies.posly.gateway.GatewayTransientException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Abstracts printer/spooler differences behind one contract, the same way PaymentGateway
 * abstracts payment terminal vendors. A real adapter (USB/local network/cloud print API) is a
 * drop-in implementation of this same interface.
 */
interface PrintGateway {
    /** Sends [content] to the physical printer identified by [printerId]; returns its confirmation ticket id. */
    suspend fun print(printerId: String, content: String): String
}

/**
 * A working in-memory stand-in for a real printer driver/spooler. [transientFailuresBeforeSuccess]
 * lets tests exercise retry/backoff by failing the first N calls before succeeding - mirrors
 * SimulatorPaymentGateway exactly.
 */
class SimulatorPrintGateway(
    private val transientFailuresBeforeSuccess: Int = 0
) : PrintGateway {

    private val attempts = AtomicInteger(0)

    override suspend fun print(printerId: String, content: String): String {
        if (attempts.getAndIncrement() < transientFailuresBeforeSuccess) {
            throw GatewayTransientException("Simulated transient printer failure")
        }
        return "print_${UUID.randomUUID().toString().take(12).uppercase()}"
    }
}
