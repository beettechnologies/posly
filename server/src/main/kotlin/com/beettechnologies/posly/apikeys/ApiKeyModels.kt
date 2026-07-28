package com.beettechnologies.posly.apikeys

import java.time.Instant
import java.util.UUID

/**
 * The set of resource groups a 3rd-party integration can be granted read access to. Deliberately
 * narrower than the full API surface (see API_KEYS.md) - these gate the specific read endpoints a
 * typical external integration (accounting, analytics) needs, not the whole app. New scopes are
 * added here and to whichever route(s) they should gate, one at a time, as real integrations need
 * them - not speculatively ahead of an actual caller.
 */
enum class ApiKeyScope { ORDERS_READ, PRODUCTS_READ, REPORTS_READ }

enum class ApiKeyStatus { ACTIVE, REVOKED }

data class ApiKey(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** First 8 characters of [id], safe to display in an admin list to help identify a key - never enough to reconstruct it. */
    val keyPrefix: String,
    /** BCrypt hash of the secret half of the raw key - the raw value itself is never stored, mirroring [com.beettechnologies.posly.auth.UserService]'s password handling. */
    val secretHash: String,
    val scopes: Set<ApiKeyScope>,
    val status: ApiKeyStatus = ApiKeyStatus.ACTIVE,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val createdBy: String? = null,
    val lastUsedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val revokedBy: String? = null
)

data class ApiKeyUsageRecord(
    val id: String = UUID.randomUUID().toString(),
    val apiKeyId: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val timestamp: Instant = Instant.now()
)

/**
 * The principal a successful API-key authentication attaches to the call - checked by
 * [com.beettechnologies.posly.rbac.withRoleOrScope] wherever a route accepts both a user JWT and
 * an API key, in place of (never alongside) a [io.ktor.server.auth.jwt.JWTPrincipal].
 */
data class ApiKeyPrincipal(val apiKeyId: String, val scopes: Set<ApiKeyScope>)
