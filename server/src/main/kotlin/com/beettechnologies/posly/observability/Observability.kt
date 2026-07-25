package com.beettechnologies.posly.observability

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelinePhase
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import java.util.UUID

private const val CORRELATION_ID_HEADER = "X-Correlation-Id"

fun Application.configureObservability() {
    val serviceName = environment.config.propertyOrNull("observability.serviceName")?.getString() ?: "posly-server"
    val environmentName = environment.config.propertyOrNull("observability.environment")?.getString()
        ?: System.getenv("ENVIRONMENT")
        ?: "local"
    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val openTelemetry = AutoConfiguredOpenTelemetrySdk.builder()
        .addPropertiesSupplier {
            mapOf(
                "otel.service.name" to serviceName,
                "otel.resource.attributes" to "deployment.environment=$environmentName",
                "otel.traces.exporter" to "none",
                "otel.metrics.exporter" to "none",
                "otel.logs.exporter" to "none"
            )
        }
        .build()
        .openTelemetrySdk

    install(CallId) {
        retrieveFromHeader(CORRELATION_ID_HEADER)
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.length <= 128 }
        replyToHeader(CORRELATION_ID_HEADER)
    }

    install(CallLogging) {
        callIdMdc("correlationId")
        mdc("method") { it.request.httpMethod.value }
        mdc("path") { it.request.path() }
        mdc("status") { it.response.status()?.value?.toString() }
    }

    install(io.ktor.server.metrics.micrometer.MicrometerMetrics) {
        registry = prometheusRegistry
        meterBinders = listOf(
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            JvmThreadMetrics(),
            ClassLoaderMetrics(),
            ProcessorMetrics()
        )
    }

    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
    }

    val traceAttributePhase = PipelinePhase("CorrelationIdTraceAttribute")
    insertPhaseAfter(io.ktor.server.application.ApplicationCallPipeline.Plugins, traceAttributePhase)
    intercept(traceAttributePhase) {
        call.callId?.let { Span.current().setAttribute("correlation.id", it) }
        proceed()
    }

    routing {
        get("/metrics") {
            call.respondText(
                prometheusRegistry.scrape(),
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
            )
        }
    }
}
