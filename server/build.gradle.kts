plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.testRetry)
}

group = "com.beettechnologies.posly"
version = "1.0.0"
application {
    mainClass = "com.beettechnologies.posly.ApplicationKt"
}

dependencies {
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverRateLimit)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverCallId)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverMetricsMicrometer)
    implementation(libs.java.jwt)
    implementation(libs.jbcrypt)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.opentelemetry.ktor30)
    implementation(libs.opentelemetry.sdk.autoconfigure)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    implementation(libs.pdfbox)
    implementation(libs.commons.csv)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientContentNegotiationMultiplatform)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.javaTime)
    implementation(libs.exposed.json)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    // Runtime (not just test-only): application.conf's zero-config local/dev default is H2 -
    // real deployments override DATABASE_URL to point at Postgres instead.
    implementation(libs.h2)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.testJunit)
    // Only for PciScopeGuardTest's reflective scan of payment/refund DTOs - kotlin.reflect.full
    // needs this on the classpath at runtime, not just kotlin-stdlib's built-in KClass.
    testImplementation(libs.kotlin.reflect)
}

// Flakiness mitigation (see E2E_TESTING.md): a genuinely flaky test (timing, coroutine
// scheduling, shared in-memory H2 state) gets up to 2 retries before failing the build - CI
// passes once a test passes, it doesn't have to pass on the very first try. failOnPassedAfterRetry
// = false is deliberate: the point is to stop flakiness from blocking a merge, not to merely
// document it. maxFailures caps how many *distinct* tests get this treatment per run - past that,
// something is more likely genuinely broken than flaky, and retrying every failure would just
// slow down (and mask) a real regression.
tasks.test {
    retry {
        maxRetries.set(2)
        maxFailures.set(10)
        failOnPassedAfterRetry.set(false)
    }
}