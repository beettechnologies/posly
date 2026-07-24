package com.beettechnologies.posly.observability

import io.ktor.http.Headers
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import org.slf4j.MDC
import java.time.Duration

data class ObservabilityConfig(
    val serviceName: String,
    val environment: String,
    val metricsPath: String,
    val otlpEndpoint: String? = null,
    val otlpTimeoutSeconds: Long = 10
)

class AppObservability private constructor(
    val config: ObservabilityConfig,
    val openTelemetry: OpenTelemetry,
    val meterRegistry: PrometheusMeterRegistry,
    private val tracerProvider: SdkTracerProvider
) : AutoCloseable {
    val tracer: Tracer = openTelemetry.getTracer(config.serviceName)

    companion object {
        private val headersGetter = object : TextMapGetter<Headers> {
            override fun keys(carrier: Headers): Iterable<String> = carrier.names()
            override fun get(carrier: Headers?, key: String): String? = carrier?.get(key)
        }

        fun create(config: ObservabilityConfig): AppObservability {
            val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            bindJvmMetrics(meterRegistry)

            val resource = Resource.getDefault().merge(
                Resource.create(
                    Attributes.of(
                        AttributeKey.stringKey("service.name"), config.serviceName,
                        AttributeKey.stringKey("deployment.environment.name"), config.environment
                    )
                )
            )

            val tracerProviderBuilder = SdkTracerProvider.builder().setResource(resource)
            if (!config.otlpEndpoint.isNullOrBlank()) {
                tracerProviderBuilder.addSpanProcessor(
                    BatchSpanProcessor.builder(
                        OtlpGrpcSpanExporter.builder()
                            .setEndpoint(config.otlpEndpoint)
                            .setTimeout(Duration.ofSeconds(config.otlpTimeoutSeconds))
                            .build()
                    ).build()
                )
            }
            val tracerProvider = tracerProviderBuilder.build()
            val openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()))
                .build()
            return AppObservability(config, openTelemetry, meterRegistry, tracerProvider)
        }

        private fun bindJvmMetrics(registry: MeterRegistry) {
            ClassLoaderMetrics().bindTo(registry)
            JvmMemoryMetrics().bindTo(registry)
            JvmGcMetrics().bindTo(registry)
            JvmThreadMetrics().bindTo(registry)
            ProcessorMetrics().bindTo(registry)
            UptimeMetrics().bindTo(registry)
        }
    }

    fun extractContext(headers: Headers): Context =
        openTelemetry.propagators.textMapPropagator.extract(Context.root(), headers, headersGetter)

    fun scrapeMetrics(): String = meterRegistry.scrape()

    fun recordAuthLogin(result: String) {
        meterRegistry.counter("posly_auth_login_total", "environment", config.environment, "result", result).increment()
    }

    fun recordAuthRefresh(result: String) {
        meterRegistry.counter("posly_auth_refresh_total", "environment", config.environment, "result", result).increment()
    }

    fun recordMfaVerification(result: String) {
        meterRegistry.counter("posly_auth_mfa_verify_total", "environment", config.environment, "result", result).increment()
    }

    fun recordDevicePairCode(result: String) {
        meterRegistry.counter("posly_device_pair_code_total", "environment", config.environment, "result", result).increment()
    }

    fun recordDeviceEnrollment(result: String) {
        meterRegistry.counter("posly_device_enrollment_total", "environment", config.environment, "result", result).increment()
    }

    inline fun <T> inSpan(
        name: String,
        kind: SpanKind = SpanKind.INTERNAL,
        attributes: Map<String, String?> = emptyMap(),
        block: (Span) -> T
    ): T {
        val span = tracer.spanBuilder(name).setSpanKind(kind).startSpan()
        attributes.forEach { (key, value) ->
            if (value != null) {
                span.setAttribute(key, value)
            }
        }

        val previousMdc = MDC.getCopyOfContextMap()
        val updatedMdc = (previousMdc?.toMutableMap() ?: mutableMapOf()).apply {
            put("traceId", span.spanContext.traceId)
            put("spanId", span.spanContext.spanId)
            put("service", config.serviceName)
            put("environment", config.environment)
        }

        val scope = span.makeCurrent()
        return try {
            MDC.setContextMap(updatedMdc)
            block(span)
        } catch (cause: Throwable) {
            span.recordException(cause)
            span.setStatus(StatusCode.ERROR)
            throw cause
        } finally {
            scope.close()
            if (previousMdc == null) {
                MDC.clear()
            } else {
                MDC.setContextMap(previousMdc)
            }
            span.end()
        }
    }

    override fun close() {
        tracerProvider.shutdown()
    }
}
