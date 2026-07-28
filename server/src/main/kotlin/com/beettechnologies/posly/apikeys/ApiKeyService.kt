package com.beettechnologies.posly.apikeys

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.db.ApiKeyUsageTable
import com.beettechnologies.posly.db.ApiKeysTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

private const val SECRET_LENGTH = 40
private const val SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private const val RAW_KEY_PREFIX = "posly_"
private val secureRandom = SecureRandom()

sealed class CreateApiKeyResult {
    data class Success(val apiKey: ApiKey, val rawKey: String) : CreateApiKeyResult()
    data object EmptyName : CreateApiKeyResult()
    data object NoScopes : CreateApiKeyResult()
}

sealed class RotateApiKeyResult {
    data class Success(val apiKey: ApiKey, val rawKey: String) : RotateApiKeyResult()
    data object NotFound : RotateApiKeyResult()
    data object Revoked : RotateApiKeyResult()
}

sealed class RevokeApiKeyResult {
    data class Success(val apiKey: ApiKey) : RevokeApiKeyResult()
    data object NotFound : RevokeApiKeyResult()
    data object AlreadyRevoked : RevokeApiKeyResult()
}

private fun randomSecret(): String = (1..SECRET_LENGTH)
    .map { SECRET_ALPHABET[secureRandom.nextInt(SECRET_ALPHABET.length)] }
    .joinToString("")

private fun rawKeyFor(id: String, secret: String) = "$RAW_KEY_PREFIX$id-$secret"

private fun rowToApiKey(row: ResultRow) = ApiKey(
    id = row[ApiKeysTable.id],
    name = row[ApiKeysTable.name],
    keyPrefix = row[ApiKeysTable.keyPrefix],
    secretHash = row[ApiKeysTable.secretHash],
    scopes = row[ApiKeysTable.scopes].mapNotNull { runCatching { ApiKeyScope.valueOf(it) }.getOrNull() }.toSet(),
    status = ApiKeyStatus.valueOf(row[ApiKeysTable.status]),
    createdAt = row[ApiKeysTable.createdAt],
    updatedAt = row[ApiKeysTable.updatedAt],
    createdBy = row[ApiKeysTable.createdBy],
    lastUsedAt = row[ApiKeysTable.lastUsedAt],
    revokedAt = row[ApiKeysTable.revokedAt],
    revokedBy = row[ApiKeysTable.revokedBy]
)

private fun rowToUsageRecord(row: ResultRow) = ApiKeyUsageRecord(
    id = row[ApiKeyUsageTable.id],
    apiKeyId = row[ApiKeyUsageTable.apiKeyId],
    method = row[ApiKeyUsageTable.method],
    path = row[ApiKeyUsageTable.path],
    statusCode = row[ApiKeyUsageTable.statusCode],
    timestamp = row[ApiKeyUsageTable.timestamp]
)

/**
 * Manages 3rd-party-integration API keys: BCrypt-hashed secrets (never stored in plaintext,
 * mirroring [com.beettechnologies.posly.auth.UserService]'s password handling), scope-based
 * authorization (checked by [com.beettechnologies.posly.rbac.withRoleOrScope]), and per-request
 * usage logging.
 *
 * The raw key returned by [createKey]/[rotateKey] has the shape `posly_<id>-<secret>` - the id
 * portion lets [authenticate] look up the row directly (an indexed primary-key read) rather than
 * BCrypt-comparing the presented secret against every stored hash in the table.
 *
 * Unlike [com.beettechnologies.posly.secrets.SecretsManager]'s JWT/webhook-secret rotation, there
 * is no grace period here: [rotateKey] replaces the hash immediately and the old raw key stops
 * working on the very next request. That's a deliberate difference, not an oversight - a JWT
 * signing key needs a grace period because *tokens already issued* must keep verifying until they
 * naturally expire; an API key has no such in-flight-artifact problem, so immediate invalidation
 * is simpler and safer (see API_KEYS.md's rotation section for the operational implication: the
 * integration's new key must be deployed before or atomically with revoking the old one).
 */
class ApiKeyService {

    fun createKey(name: String, scopes: Set<ApiKeyScope>, actorUserId: String?): CreateApiKeyResult {
        if (name.isBlank()) return CreateApiKeyResult.EmptyName
        if (scopes.isEmpty()) return CreateApiKeyResult.NoScopes

        val id = UUID.randomUUID().toString()
        val secret = randomSecret()
        val now = Instant.now()
        val apiKey = ApiKey(
            id = id,
            name = name,
            keyPrefix = id.take(8),
            secretHash = BCrypt.hashpw(secret, BCrypt.gensalt()),
            scopes = scopes,
            createdAt = now,
            updatedAt = now,
            createdBy = actorUserId
        )
        transaction {
            ApiKeysTable.insert {
                it[ApiKeysTable.id] = apiKey.id
                it[ApiKeysTable.name] = apiKey.name
                it[ApiKeysTable.keyPrefix] = apiKey.keyPrefix
                it[ApiKeysTable.secretHash] = apiKey.secretHash
                it[ApiKeysTable.scopes] = apiKey.scopes.map { scope -> scope.name }
                it[ApiKeysTable.status] = apiKey.status.name
                it[ApiKeysTable.createdAt] = apiKey.createdAt
                it[ApiKeysTable.updatedAt] = apiKey.updatedAt
                it[ApiKeysTable.createdBy] = apiKey.createdBy
            }
        }
        AuditService.record(AuditEvent.API_KEY_CREATED, userId = actorUserId, detail = "apiKeyId=$id name=$name scopes=$scopes")
        return CreateApiKeyResult.Success(apiKey, rawKeyFor(id, secret))
    }

    fun listKeys(): List<ApiKey> = transaction {
        ApiKeysTable.selectAll().map { rowToApiKey(it) }.sortedByDescending { it.createdAt }
    }

    fun getKey(id: String): ApiKey? = transaction {
        ApiKeysTable.selectAll().where { ApiKeysTable.id eq id }.singleOrNull()?.let { rowToApiKey(it) }
    }

    fun revokeKey(id: String, actorUserId: String?): RevokeApiKeyResult {
        val existing = getKey(id) ?: return RevokeApiKeyResult.NotFound
        if (existing.status == ApiKeyStatus.REVOKED) return RevokeApiKeyResult.AlreadyRevoked

        val now = Instant.now()
        val updated = existing.copy(status = ApiKeyStatus.REVOKED, updatedAt = now, revokedAt = now, revokedBy = actorUserId)
        transaction {
            ApiKeysTable.update({ ApiKeysTable.id eq id }) {
                it[ApiKeysTable.status] = updated.status.name
                it[ApiKeysTable.updatedAt] = updated.updatedAt
                it[ApiKeysTable.revokedAt] = updated.revokedAt
                it[ApiKeysTable.revokedBy] = updated.revokedBy
            }
        }
        AuditService.record(AuditEvent.API_KEY_REVOKED, userId = actorUserId, detail = "apiKeyId=$id name=${existing.name}")
        return RevokeApiKeyResult.Success(updated)
    }

    /** Generates a brand-new secret for [id], immediately invalidating the previous one - see this class's doc comment. */
    fun rotateKey(id: String, actorUserId: String?): RotateApiKeyResult {
        val existing = getKey(id) ?: return RotateApiKeyResult.NotFound
        if (existing.status == ApiKeyStatus.REVOKED) return RotateApiKeyResult.Revoked

        val secret = randomSecret()
        val now = Instant.now()
        val updated = existing.copy(secretHash = BCrypt.hashpw(secret, BCrypt.gensalt()), updatedAt = now)
        transaction {
            ApiKeysTable.update({ ApiKeysTable.id eq id }) {
                it[ApiKeysTable.secretHash] = updated.secretHash
                it[ApiKeysTable.updatedAt] = updated.updatedAt
            }
        }
        AuditService.record(AuditEvent.API_KEY_ROTATED, userId = actorUserId, detail = "apiKeyId=$id name=${existing.name}")
        return RotateApiKeyResult.Success(updated, rawKeyFor(id, secret))
    }

    /**
     * Verifies a raw `posly_<id>-<secret>` key presented as a bearer token and, on success,
     * touches [ApiKey.lastUsedAt] - called from the `api-key-auth` bearer provider's
     * `authenticate { }` block in Application.kt. Returns null (not an exception) for every
     * failure mode - unknown id, revoked key, wrong secret - so the caller just sees a generic
     * authentication failure, never which part was wrong.
     */
    fun authenticate(rawKey: String): ApiKeyPrincipal? {
        if (!rawKey.startsWith(RAW_KEY_PREFIX)) return null
        val withoutPrefix = rawKey.removePrefix(RAW_KEY_PREFIX)
        val separatorIndex = withoutPrefix.indexOf('-', startIndex = 36) // past the UUID's own hyphens
        if (separatorIndex < 0) return null
        val id = withoutPrefix.substring(0, separatorIndex)
        val secret = withoutPrefix.substring(separatorIndex + 1)

        val key = getKey(id) ?: return null
        if (key.status != ApiKeyStatus.ACTIVE) return null
        if (!BCrypt.checkpw(secret, key.secretHash)) return null

        transaction {
            ApiKeysTable.update({ ApiKeysTable.id eq id }) {
                it[ApiKeysTable.lastUsedAt] = Instant.now()
            }
        }
        return ApiKeyPrincipal(key.id, key.scopes)
    }

    fun recordUsage(apiKeyId: String, method: String, path: String, statusCode: Int) {
        transaction {
            ApiKeyUsageTable.insert {
                it[ApiKeyUsageTable.id] = UUID.randomUUID().toString()
                it[ApiKeyUsageTable.apiKeyId] = apiKeyId
                it[ApiKeyUsageTable.method] = method
                it[ApiKeyUsageTable.path] = path
                it[ApiKeyUsageTable.statusCode] = statusCode
                it[ApiKeyUsageTable.timestamp] = Instant.now()
            }
        }
    }

    /** Most recent usage first. */
    fun listUsage(apiKeyId: String): List<ApiKeyUsageRecord> = transaction {
        ApiKeyUsageTable.selectAll().where { ApiKeyUsageTable.apiKeyId eq apiKeyId }
            .map { rowToUsageRecord(it) }
            .sortedByDescending { it.timestamp }
    }
}
