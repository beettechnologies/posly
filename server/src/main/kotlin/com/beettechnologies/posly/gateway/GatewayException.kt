package com.beettechnologies.posly.gateway

/** A failure from any outbound call to an external gateway/service. Non-transient by default - retrying would not help. */
open class GatewayException(message: String) : Exception(message)

/** A transient failure (e.g. a network blip) - safe and expected to retry. */
class GatewayTransientException(message: String) : GatewayException(message)
