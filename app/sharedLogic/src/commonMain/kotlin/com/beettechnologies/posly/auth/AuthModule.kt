package com.beettechnologies.posly.auth

import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module

/** http://10.0.2.2:8080 on the Android emulator, http://localhost:8080 on Desktop. */
expect val defaultBaseUrl: String

/** Platform-specific Settings (secure storage) + HttpClient (engine choice). */
expect fun platformAuthModule(): Module

val authModule = module {
    single<AuthApi> { KtorAuthApi(get(), defaultBaseUrl) }
    single<TokenStore> { SettingsTokenStore(get()) }
    single { AuthRepository(get(), get()) }
}

/**
 * Shared HttpClient configuration: JSON content negotiation plus bearer-token
 * auth with automatic refresh-on-401. Applied identically by each platform's
 * HttpClient(engine) { } in its platformAuthModule().
 */
fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureAuthClient(
    tokenStore: TokenStore,
    baseUrl: String
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(Auth) {
        bearer {
            loadTokens {
                val access = tokenStore.getAccessToken()
                val refresh = tokenStore.getRefreshToken()
                if (access != null && refresh != null) BearerTokens(access, refresh) else null
            }
            refreshTokens {
                val refreshToken = tokenStore.getRefreshToken() ?: return@refreshTokens null
                val response = client.post("$baseUrl/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken))
                }
                if (response.status == HttpStatusCode.OK) {
                    val body = response.body<RefreshResponse>()
                    tokenStore.saveTokens(body.accessToken, refreshToken)
                    BearerTokens(body.accessToken, refreshToken)
                } else {
                    null
                }
            }
        }
    }
}
