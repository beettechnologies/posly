package com.beettechnologies.posly

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.beettechnologies.posly.auth.AuthService
import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.auth.JwtService
import com.beettechnologies.posly.auth.UserService
import com.beettechnologies.posly.auth.configureAuthRoutes
import com.beettechnologies.posly.devices.DeviceRegistryService
import com.beettechnologies.posly.devices.configureDeviceRoutes
import com.beettechnologies.posly.observability.AppObservability
import com.beettechnologies.posly.observability.ObservabilityConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.extension.kotlin.asContextElement
import io.micrometer.core.instrument.binder.MeterBinder
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level
import java.util.UUID

private const val CorrelationIdHeader = "X-Correlation-Id"
private const val TraceIdHeader = "X-Trace-Id"
private const val PrometheusContentType = "text/plain; version=0.0.4; charset=utf-8"
private val applicationLogger = LoggerFactory.getLogger("Application")

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val observability = createObservability()
    val jwtConfig = environment.config.config("jwt")
    val jwtSecret = jwtConfig.property("secret").getString()
    val jwtIssuer = jwtConfig.property("issuer").getString()
    val jwtAudience = jwtConfig.property("audience").getString()
    val accessExpMs = jwtConfig.property("accessTokenExpirationMs").getString().toLong()
    val refreshExpMs = jwtConfig.property("refreshTokenExpirationMs").getString().toLong()
    val mfaExpMs = jwtConfig.property("mfaTokenExpirationMs").getString().toLong()

    val jwtService = JwtService(jwtSecret, jwtIssuer, jwtAudience, accessExpMs, refreshExpMs, mfaExpMs)
    val userService = UserService()
    val authService = AuthService(userService, jwtService, observability)
    val deviceRegistryService = DeviceRegistryService(observability = observability)

    monitor.subscribe(ApplicationStopped) {
        observability.close()
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(CallId) {
        retrieveFromHeader(CorrelationIdHeader)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() && it.length <= 128 }
        replyToHeader(CorrelationIdHeader)
    }

    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") { call -> call.callId }
        mdc("path") { call -> call.request.path() }
        mdc("method") { call -> call.request.httpMethod.value }
        format { call ->
            "Handled ${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value ?: 0}"
        }
    }

    install(MicrometerMetrics) {
        registry = observability.meterRegistry
        meterBinders = emptyList<MeterBinder>()
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        val correlationId = call.callId ?: UUID.randomUUID().toString()
        val parentContext = observability.extractContext(call.request.headers)
        val span = observability.tracer.spanBuilder("${call.request.httpMethod.value} ${call.request.path()}")
            .setParent(parentContext)
            .setSpanKind(SpanKind.SERVER)
            .startSpan()

        span.setAttribute("http.request.method", call.request.httpMethod.value)
        span.setAttribute("url.path", call.request.path())
        span.setAttribute("correlation.id", correlationId)
        span.setAttribute("server.address", call.request.host())

        val traceContext = parentContext.with(span)
        val mdcValues = buildMap {
            put("correlationId", correlationId)
            put("traceId", span.spanContext.traceId)
            put("spanId", span.spanContext.spanId)
            put("service", observability.config.serviceName)
            put("environment", observability.config.environment)
        }
        call.response.headers.append(TraceIdHeader, span.spanContext.traceId)

        try {
            withContext(traceContext.asContextElement() + MDCContext(mdcValues)) {
                proceed()
            }
        } catch (cause: Throwable) {
            span.recordException(cause)
            span.setStatus(StatusCode.ERROR)
            throw cause
        } finally {
            val statusCode = call.response.status()?.value ?: HttpStatusCode.OK.value
            span.setAttribute("http.response.status_code", statusCode.toLong())
            if (statusCode >= 500) {
                span.setStatus(StatusCode.ERROR)
            }
            span.end()
            MDC.clear()
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            applicationLogger.error("Unhandled server error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    install(Authentication) {
        jwt("jwt-auth") {
            realm = "posly"
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withIssuer(jwtIssuer)
                    .withAudience(jwtAudience)
                    .withClaim("type", "access")
                    .build()
            )
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid access token"))
            }
        }
    }

    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        get("/health") {
            call.respondText("OK")
        }
        get(observability.config.metricsPath) {
            call.respondText(observability.scrapeMetrics(), ContentType.parse(PrometheusContentType))
        }
    }

    configureAuthRoutes(authService)
    configureDeviceRoutes(deviceRegistryService)
}

private fun Application.createObservability(): AppObservability {
    val config = environment.config
    val environmentName = config.propertyOrNull("observability.environment")?.getString()
        ?: System.getenv("ENVIRONMENT")
        ?: "local"
    val serviceName = config.propertyOrNull("observability.serviceName")?.getString() ?: "posly-server"
    val metricsPath = config.propertyOrNull("observability.metricsPath")?.getString() ?: "/metrics"
    val otlpEndpoint = config.propertyOrNull("observability.otlpEndpoint")?.getString()
        ?: System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
    val otlpTimeoutSeconds = config.propertyOrNull("observability.otlpTimeoutSeconds")?.getString()?.toLongOrNull() ?: 10L
    return AppObservability.create(
        ObservabilityConfig(
            serviceName = serviceName,
            environment = environmentName,
            metricsPath = metricsPath,
            otlpEndpoint = otlpEndpoint,
            otlpTimeoutSeconds = otlpTimeoutSeconds
        )
    )
}
