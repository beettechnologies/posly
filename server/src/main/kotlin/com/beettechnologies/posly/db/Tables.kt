package com.beettechnologies.posly.db

import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.Discount
import com.beettechnologies.posly.cart.PaymentRecord
import com.beettechnologies.posly.cart.RefundRecord
import com.beettechnologies.posly.products.ProductModifier
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.TaxRate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

private val json = Json { ignoreUnknownKeys = true }

object StoresTable : Table("stores") {
    val id = varchar("id", 36)
    val name = varchar("name", 255)
    val address = jsonb<Address>("address", json, Address.serializer(), true)
    val timezone = varchar("timezone", 100)
    val currency = varchar("currency", 10)
    val taxProfileId = varchar("tax_profile_id", 36).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object TaxProfilesTable : Table("tax_profiles") {
    val id = varchar("id", 36)
    val name = varchar("name", 255)
    val rates = jsonb<List<TaxRate>>("rates", json, ListSerializer(TaxRate.serializer()), true)
    val pricingMode = varchar("pricing_mode", 20)
    val roundingMode = varchar("rounding_mode", 20)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ProductsTable : Table("products") {
    val id = varchar("id", 36)
    val sku = varchar("sku", 100).uniqueIndex()
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val price = double("price")
    val taxCategory = varchar("tax_category", 20)
    val modifiers = jsonb<List<ProductModifier>>("modifiers", json, ListSerializer(ProductModifier.serializer()), true)
    val imageUrls = jsonb<List<String>>("image_urls", json, ListSerializer(String.serializer()), true)
    val barcode = varchar("barcode", 100).nullable()
    val category = varchar("category", 100).nullable()
    val inStock = bool("in_stock")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Replaces the previous in-memory `ConcurrentHashMap<String, ByteArray>` product-image store. */
object ProductImagesTable : Table("product_images") {
    val id = varchar("id", 36)
    val productId = varchar("product_id", 36)
    val fileName = varchar("file_name", 255)
    val bytes = blob("bytes")
    override val primaryKey = PrimaryKey(id)
}

object UsersTable : Table("users") {
    val id = varchar("id", 36)
    val username = varchar("username", 100).uniqueIndex()
    val passwordHash = text("password_hash").nullable()
    val email = varchar("email", 255).nullable()
    val roles = jsonb<List<String>>("roles", json, ListSerializer(String.serializer()), true)
    val storeIds = jsonb<List<String>>("store_ids", json, ListSerializer(String.serializer()), true)
    val status = varchar("status", 20)
    val mfaEnabled = bool("mfa_enabled")
    val mfaSecret = varchar("mfa_secret", 255).nullable()
    val roleVersion = integer("role_version")
    val externalId = varchar("external_id", 255).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** [items]/[discount]/[totals]/[payments]/[refunds] are stored as JSON - the Order aggregate stays a single row, matching how the in-memory model already treats it as one value object. */
object OrdersTable : Table("orders") {
    val id = varchar("id", 36)
    val cartId = varchar("cart_id", 36)
    val storeId = varchar("store_id", 36)
    val createdBy = varchar("created_by", 36).nullable()
    val items = jsonb<List<CartItem>>("items", json, ListSerializer(CartItem.serializer()), true)
    val discount = jsonb<Discount>("discount", json, Discount.serializer(), true).nullable()
    val totals = jsonb<CartTotals>("totals", json, CartTotals.serializer(), true)
    val idempotencyKey = varchar("idempotency_key", 255)
    val checkedOutAt = timestamp("checked_out_at")
    val status = varchar("status", 30)
    val payments = jsonb<List<PaymentRecord>>("payments", json, ListSerializer(PaymentRecord.serializer()), true)
    val refunds = jsonb<List<RefundRecord>>("refunds", json, ListSerializer(RefundRecord.serializer()), true)
    override val primaryKey = PrimaryKey(id)
}

/** A genuine append-only child table - [com.beettechnologies.posly.cart.OrderEvent] was already an event log, not a mutable aggregate. */
object OrderEventsTable : Table("order_events") {
    val id = integer("id").autoIncrement()
    val orderId = varchar("order_id", 36)
    val timestamp = timestamp("event_timestamp")
    val type = varchar("type", 30)
    val actorId = varchar("actor_id", 36).nullable()
    val detail = text("detail").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ShiftsTable : Table("shifts") {
    val id = varchar("id", 36)
    val storeId = varchar("store_id", 36)
    val cashierId = varchar("cashier_id", 36).nullable()
    val openingFloat = double("opening_float")
    val openedAt = timestamp("opened_at")
    val status = varchar("status", 20)
    val closingCount = double("closing_count").nullable()
    val expectedCash = double("expected_cash").nullable()
    val variance = double("variance").nullable()
    val note = text("note").nullable()
    val closedBy = varchar("closed_by", 36).nullable()
    val closedAt = timestamp("closed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** A genuine append-only child table - already an event log in-memory. */
object ShiftAuditEventsTable : Table("shift_audit_events") {
    val id = varchar("id", 36)
    val shiftId = varchar("shift_id", 36)
    val type = varchar("type", 30)
    val actorId = varchar("actor_id", 36).nullable()
    val detail = text("detail").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object FeatureFlagsTable : Table("feature_flags") {
    val id = varchar("id", 36)
    val key = varchar("key", 100).uniqueIndex()
    val description = varchar("description", 500)
    val enabled = bool("enabled")
    val rolloutPercentage = integer("rollout_percentage")
    val enabledStoreIds = jsonb<List<String>>("enabled_store_ids", json, ListSerializer(String.serializer()), true)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AuditTable : Table("audit_log") {
    val id = varchar("id", 36)
    val timestamp = timestamp("timestamp")
    val event = varchar("event", 50)
    val username = varchar("username", 100).nullable()
    val userId = varchar("user_id", 36).nullable()
    val deviceId = varchar("device_id", 36).nullable()
    val correlationId = varchar("correlation_id", 128).nullable()
    val remoteIp = varchar("remote_ip", 100).nullable()
    val detail = text("detail").nullable()
    override val primaryKey = PrimaryKey(id)
}
