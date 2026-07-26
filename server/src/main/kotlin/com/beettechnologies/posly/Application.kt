package com.beettechnologies.posly

import com.beettechnologies.posly.auth.AuthService
import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.auth.JwtService
import com.beettechnologies.posly.auth.SsoConfigService
import com.beettechnologies.posly.auth.UserService
import com.beettechnologies.posly.auth.configureAuthRoutes
import com.beettechnologies.posly.auth.configureUserRoutes
import com.beettechnologies.posly.audit.AuditRetentionService
import com.beettechnologies.posly.audit.configureAuditRoutes
import com.beettechnologies.posly.backup.BackupService
import com.beettechnologies.posly.backup.RestoreService
import com.beettechnologies.posly.backup.configureBackupRoutes
import com.beettechnologies.posly.cart.CartService
import com.beettechnologies.posly.cart.CompositeOrderEventListener
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.configureCartRoutes
import com.beettechnologies.posly.cart.configureOrderRoutes
import com.beettechnologies.posly.catalog.ProductImportService
import com.beettechnologies.posly.catalog.configureProductImportRoutes
import com.beettechnologies.posly.db.DatabaseFactory
import com.beettechnologies.posly.devices.DeviceRegistryService
import com.beettechnologies.posly.devices.configureDeviceRoutes
import com.beettechnologies.posly.email.EmailService
import com.beettechnologies.posly.email.SimulatorEmailGateway
import com.beettechnologies.posly.email.configureEmailRoutes
import com.beettechnologies.posly.finance.FinanceReportService
import com.beettechnologies.posly.finance.configureFinanceReportRoutes
import com.beettechnologies.posly.flags.FeatureFlagService
import com.beettechnologies.posly.flags.configureFeatureFlagRoutes
import com.beettechnologies.posly.inventory.InventoryService
import com.beettechnologies.posly.inventory.StockCountService
import com.beettechnologies.posly.inventory.configureInventoryRoutes
import com.beettechnologies.posly.inventory.configureStockCountRoutes
import com.beettechnologies.posly.model.UserStatus
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
import com.beettechnologies.posly.reporting.ReportingService
import com.beettechnologies.posly.reporting.configureReportingRoutes
import com.beettechnologies.posly.secrets.InMemorySecretsManager
import com.beettechnologies.posly.secrets.SecretName
import com.beettechnologies.posly.secrets.configureSecretsRoutes
import com.beettechnologies.posly.shifts.ShiftService
import com.beettechnologies.posly.shifts.configureShiftRoutes
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import com.beettechnologies.posly.stores.configureStoreRoutes
import com.beettechnologies.posly.stores.configureTaxProfileRoutes
import com.beettechnologies.posly.stores.configureTaxRoutes
import com.beettechnologies.posly.sync.OfflineSyncService
import com.beettechnologies.posly.sync.configureSyncRoutes
import com.beettechnologies.posly.webhooks.WebhookService
import com.beettechnologies.posly.webhooks.configureWebhookRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.*
import io.ktor.http.auth.HttpAuthHeader
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
    val databaseConfig = environment.config.config("database")
    DatabaseFactory.init(databaseConfig)
    val jdbcUrl = databaseConfig.property("jdbcUrl").getString()
    val meterRegistry = configureObservability()

    val jwtConfig = environment.config.config("jwt")
    val jwtSecret = jwtConfig.property("secret").getString()
    val jwtIssuer = jwtConfig.property("issuer").getString()
    val jwtAudience = jwtConfig.property("audience").getString()
    val accessExpMs = jwtConfig.property("accessTokenExpirationMs").getString().toLong()
    val refreshExpMs = jwtConfig.property("refreshTokenExpirationMs").getString().toLong()
    val mfaExpMs = jwtConfig.property("mfaTokenExpirationMs").getString().toLong()
    val webhookSecret = environment.config.config("payments").property("webhookSecret").getString()
    val rotationGracePeriodMs = environment.config.config("secrets").property("rotationGracePeriodMs").getString().toLong()

    // The only two secrets in this codebase with genuine "many verifiers must keep working
    // through rotation" semantics - see SECURITY_COMPLIANCE.md for why this is an in-process
    // abstraction rather than a real Vault/KMS integration.
    val secretsManager = InMemorySecretsManager(
        initialSecrets = mapOf(
            SecretName.JWT_SIGNING_KEY to jwtSecret,
            SecretName.PAYMENT_WEBHOOK_SECRET to webhookSecret
        ),
        gracePeriodMs = rotationGracePeriodMs
    )
    val jwtService = JwtService(secretsManager, jwtIssuer, jwtAudience, accessExpMs, refreshExpMs, mfaExpMs)
    val userService = UserService()
    val ssoConfigService = SsoConfigService()
    val authService = AuthService(userService, jwtService, ssoConfigService = ssoConfigService)
    val deviceRegistryService = DeviceRegistryService()
    val productService = ProductService()
    val taxProfileService = TaxProfileService()
    val storeService = StoreService(taxProfileService)
    val inventoryService = InventoryService(productService, storeService)
    val stockCountService = StockCountService(inventoryService, productService, storeService)
    val webhookHttpClient = HttpClient(CIO)
    val orderEventDispatcher = CompositeOrderEventListener()
    val orderService = OrderService(eventListener = orderEventDispatcher)
    val webhookService = WebhookService(webhookHttpClient, deliveryScope = this)
    orderEventDispatcher.register(webhookService)
    val shiftService = ShiftService(storeService, orderService)
    val reportingService = ReportingService(
        orderService, storeService, stockCountService, shiftService,
        pipelineScope = this, meterRegistry = meterRegistry
    )
    orderEventDispatcher.register(reportingService)
    val cartService = CartService(productService, storeService, taxProfileService, orderService)
    val paymentGatewayService = PaymentGatewayService(
        SimulatorPaymentGateway(),
        orderService,
        secretsManager,
        autoResolveScope = this
    )
    val offlineSyncService = OfflineSyncService(deviceRegistryService, productService, cartService, orderService)
    val productImportService = ProductImportService(productService, importScope = this)
    val printerRegistryService = PrinterRegistryService()
    val printService = PrintService(orderService, printerRegistryService, SimulatorPrintGateway())
    val emailGateway = SimulatorEmailGateway()
    val emailService = EmailService(orderService, emailGateway)
    val financeReportService = FinanceReportService(
        orderService, shiftService, storeService, emailGateway,
        scheduleScope = this
    )
    val backupDirectory = environment.config.config("backup").property("directory").getString()
    val backupService = BackupService(jdbcUrl, backupDirectory, scope = this)
    val restoreService = RestoreService(backupService, productionJdbcUrl = jdbcUrl)
    val featureFlagService = FeatureFlagService(meterRegistry)
    val auditConfig = environment.config.config("audit")
    val auditRetentionService = AuditRetentionService(
        archiveDirectory = auditConfig.property("archiveDirectory").getString(),
        scope = this,
        retentionDays = auditConfig.property("retentionDays").getString().toLong(),
        checkIntervalMillis = auditConfig.property("retentionCheckIntervalMs").getString().toLong()
    )

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
            // A dynamic per-request verifier, not a static one: reads the incoming token's `kid`
            // to resolve the right (current-or-previous-during-grace-period) signing key, so
            // rotating the JWT secret via POST /ops/secrets/jwt-signing-key/rotate doesn't
            // invalidate every access token already in a client's hands.
            verifier { authHeader ->
                val token = (authHeader as? HttpAuthHeader.Single)?.blob
                token?.let { jwtService.accessTokenVerifierFor(it) }
            }
            validate { credential ->
                // A live check, not just a signature/expiry check: re-reads the user's CURRENT
                // status and roleVersion on every request, so a role change or account disable
                // invalidates every access token already issued for that user immediately, rather
                // than waiting for it to expire or for the client to refresh.
                val userId = credential.payload.subject ?: return@validate null
                val user = userService.findById(userId) ?: return@validate null
                if (user.status != UserStatus.ACTIVE) return@validate null
                val tokenRoleVersion = credential.payload.getClaim("roleVersion").asInt() ?: 0
                if (tokenRoleVersion != user.roleVersion) return@validate null
                JWTPrincipal(credential.payload)
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
    configureUserRoutes(authService, userService, ssoConfigService)
    configureDeviceRoutes(deviceRegistryService)
    configureProductRoutes(productService)
    configureProductImportRoutes(productImportService)
    configureSearchRoutes(productService)
    configureStoreRoutes(storeService)
    configureTaxProfileRoutes(taxProfileService)
    configureTaxRoutes(taxProfileService)
    configureInventoryRoutes(inventoryService)
    configureStockCountRoutes(stockCountService)
    configureShiftRoutes(shiftService)
    configureWebhookRoutes(webhookService)
    configureCartRoutes(cartService)
    configureOrderRoutes(orderService, paymentGatewayService, inventoryService)
    configurePaymentRoutes(paymentGatewayService)
    configureSyncRoutes(offlineSyncService)
    configurePrintRoutes(printerRegistryService, printService)
    configureEmailRoutes(emailService)
    configureReportingRoutes(reportingService)
    configureFinanceReportRoutes(financeReportService)
    configureBackupRoutes(backupService, restoreService)
    configureSecretsRoutes(secretsManager)
    configureFeatureFlagRoutes(featureFlagService)
    configureAuditRoutes(auditRetentionService)
}
