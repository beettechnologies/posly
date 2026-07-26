package com.beettechnologies.posly

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.beettechnologies.posly.auth.AuthService
import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.auth.JwtService
import com.beettechnologies.posly.auth.UserService
import com.beettechnologies.posly.auth.configureAuthRoutes
import com.beettechnologies.posly.cart.CartService
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.configureCartRoutes
import com.beettechnologies.posly.cart.configureOrderRoutes
import com.beettechnologies.posly.devices.DeviceRegistryService
import com.beettechnologies.posly.devices.configureDeviceRoutes
import com.beettechnologies.posly.email.EmailService
import com.beettechnologies.posly.email.SimulatorEmailGateway
import com.beettechnologies.posly.email.configureEmailRoutes
import com.beettechnologies.posly.inventory.InventoryService
import com.beettechnologies.posly.inventory.StockCountService
import com.beettechnologies.posly.inventory.configureInventoryRoutes
import com.beettechnologies.posly.inventory.configureStockCountRoutes
import com.beettechnologies.posly.observability.configureObservability
import com.beettechnologies.posly.payments.PaymentGatewayService
import com.beettechnologies.posly.payments.SimulatorPaymentGateway
import com.beettechnologies.posly.payments.configurePaymentRoutes
import com.beettechnologies.posly.printing.PrintService
import com.beettechnologies.posly.printing.PrinterRegistryService
import com.beettechnologies.posly.printing.SimulatorPrintGateway
import com.beettechnologies.posly.printing.configurePrintRoutes
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.products.configureProductRoutes
import com.beettechnologies.posly.products.search.configureSearchRoutes
import com.beettechnologies.posly.shifts.ShiftService
import com.beettechnologies.posly.shifts.configureShiftRoutes
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import com.beettechnologies.posly.stores.configureStoreRoutes
import com.beettechnologies.posly.stores.configureTaxProfileRoutes
import com.beettechnologies.posly.stores.configureTaxRoutes
import com.beettechnologies.posly.sync.OfflineSyncService
import com.beettechnologies.posly.sync.configureSyncRoutes
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

fun main(args: Array<String>): Unit = EngineMain.main(args)

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
    val taxProfileService = TaxProfileService()
    val storeService = StoreService(taxProfileService)
    val inventoryService = InventoryService(productService, storeService)
    val stockCountService = StockCountService(inventoryService, productService, storeService)
    val orderService = OrderService()
    val shiftService = ShiftService(storeService, orderService)
    val cartService = CartService(productService, storeService, taxProfileService, orderService)
    val webhookSecret = environment.config.config("payments").property("webhookSecret").getString()
    val paymentGatewayService = PaymentGatewayService(
        SimulatorPaymentGateway(),
        orderService,
        webhookSecret,
        autoResolveScope = this
    )
    val offlineSyncService = OfflineSyncService(deviceRegistryService, productService, cartService, orderService)
    val printerRegistryService = PrinterRegistryService()
    val printService = PrintService(orderService, printerRegistryService, SimulatorPrintGateway())
    val emailService = EmailService(orderService, SimulatorEmailGateway())

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
    configureStoreRoutes(storeService)
    configureTaxProfileRoutes(taxProfileService)
    configureTaxRoutes(taxProfileService)
    configureInventoryRoutes(inventoryService)
    configureStockCountRoutes(stockCountService)
    configureShiftRoutes(shiftService)
    configureCartRoutes(cartService)
    configureOrderRoutes(orderService, paymentGatewayService, inventoryService)
    configurePaymentRoutes(paymentGatewayService)
    configureSyncRoutes(offlineSyncService)
    configurePrintRoutes(printerRegistryService, printService)
    configureEmailRoutes(emailService)
}
