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
import com.beettechnologies.posly.observability.configureObservability
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.products.configureProductRoutes
import com.beettechnologies.posly.products.search.configureSearchRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val jwtConfig = environment.config.config("jwt")
    val jwtSecret = jwtConfig.property("secret").getString()
    val jwtIssuer = jwtConfig.property("issuer").getString()
    val jwtAudience = jwtConfig.property("audience").getString()
    val accessExpMs = jwtConfig.property("accessTokenExpirationMs").getString().toLong()
    val refreshExpMs = jwtConfig.property("refreshTokenExpirationMs").getString().toLong()
    val mfaExpMs = jwtConfig.property("mfaTokenExpirationMs").getString().toLong()

    val jwtService = JwtService(jwtSecret, jwtIssuer, jwtAudience, accessExpMs, refreshExpMs, mfaExpMs)
    val userService = UserService()
    val authService = AuthService(userService, jwtService)
    val deviceRegistryService = DeviceRegistryService()
    val productService = ProductService()

    configureObservability()

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error: ${cause.message}"))
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
            call.respondText("Hello, Ktor!")
        }
        get("/health") {
            call.respondText("OK")
        }
    }

    configureAuthRoutes(authService)
    configureDeviceRoutes(deviceRegistryService)
    configureProductRoutes(productService)
    configureSearchRoutes(productService)
}
