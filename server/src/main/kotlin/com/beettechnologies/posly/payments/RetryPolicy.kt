package com.beettechnologies.posly.payments

import kotlinx.coroutines.delay

/**
 * Exponential-backoff retry for outbound gateway calls. Only [GatewayTransientException] is
 * retried - a plain [GatewayException] (or anything else) is assumed permanent and propagates
 * immediately, since retrying a declined-card-type failure would never help.
 */
class RetryPolicy(
    private val maxAttempts: Int = 3,
    private val initialDelayMillis: Long = 100,
    private val backoffFactor: Double = 2.0
) {
    suspend fun <T> withBackoff(block: suspend () -> T): T {
        var attempt = 0
        var delayMillis = initialDelayMillis
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: GatewayTransientException) {
                if (attempt >= maxAttempts) throw e
                delay(delayMillis)
                delayMillis = (delayMillis * backoffFactor).toLong()
            }
        }
    }
}
