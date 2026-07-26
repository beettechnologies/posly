plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
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
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.testJunit)
}