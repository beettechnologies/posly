package com.beettechnologies.posly.admin

import com.beettechnologies.posly.apikeys.ApiKeyApi
import com.beettechnologies.posly.apikeys.ApiKeyCreatedResponse
import com.beettechnologies.posly.apikeys.ApiKeyListResult
import com.beettechnologies.posly.apikeys.ApiKeyResponse
import com.beettechnologies.posly.apikeys.ApiKeyUsageResponse
import com.beettechnologies.posly.apikeys.ApiKeyUsageResult
import com.beettechnologies.posly.apikeys.CreateApiKeyOutcome
import com.beettechnologies.posly.apikeys.RevokeApiKeyOutcome
import com.beettechnologies.posly.apikeys.RotateApiKeyOutcome

internal fun testApiKey(
    id: String = "key-1",
    name: String = "Accounting integration",
    scopes: List<String> = listOf("ORDERS_READ"),
    status: String = "ACTIVE"
) = ApiKeyResponse(
    id = id,
    name = name,
    keyPrefix = id.take(8),
    scopes = scopes,
    status = status,
    createdAt = "2026-01-01T00:00:00Z",
    updatedAt = "2026-01-01T00:00:00Z",
    createdBy = null,
    lastUsedAt = null,
    revokedAt = null,
    revokedBy = null
)

internal class FakeApiKeyApi(
    private val keys: MutableList<ApiKeyResponse> = mutableListOf(),
    private val listResult: ApiKeyListResult? = null,
    private val createOutcome: CreateApiKeyOutcome? = null,
    private val revokeOutcome: RevokeApiKeyOutcome? = null,
    private val rotateOutcome: RotateApiKeyOutcome? = null,
    private val usageResult: ApiKeyUsageResult? = null
) : ApiKeyApi {
    var lastCreate: Pair<String, List<String>>? = null
    var lastRevokedId: String? = null
    var lastRotatedId: String? = null

    // A defensive copy (.toList()), not the live `keys` reference: revoke/rotate mutate `keys` in
    // place, and handing out the same mutable instance would let that in-place mutation silently
    // "backdate" a UI state the ViewModel already emitted - since StateFlow only notifies
    // collectors when the new value is unequal to the old one, a stale-but-aliased list can make
    // the "before" and "after" states structurally equal and suppress the recomposition entirely.
    override suspend fun listKeys(): ApiKeyListResult = listResult ?: ApiKeyListResult.Success(keys.toList())

    override suspend fun createKey(name: String, scopes: List<String>): CreateApiKeyOutcome {
        lastCreate = name to scopes
        if (createOutcome != null) return createOutcome
        val created = testApiKey(id = "key-new", name = name, scopes = scopes)
        keys += created
        return CreateApiKeyOutcome.Success(ApiKeyCreatedResponse(created, "posly_key-new-rawsecret123"))
    }

    override suspend fun revokeKey(id: String): RevokeApiKeyOutcome {
        lastRevokedId = id
        if (revokeOutcome != null) return revokeOutcome
        val existing = keys.find { it.id == id } ?: return RevokeApiKeyOutcome.NotFound
        val revoked = existing.copy(status = "REVOKED", revokedAt = "2026-01-02T00:00:00Z")
        keys[keys.indexOf(existing)] = revoked
        return RevokeApiKeyOutcome.Success(revoked)
    }

    override suspend fun rotateKey(id: String): RotateApiKeyOutcome {
        lastRotatedId = id
        if (rotateOutcome != null) return rotateOutcome
        val existing = keys.find { it.id == id } ?: return RotateApiKeyOutcome.NotFound
        return RotateApiKeyOutcome.Success(ApiKeyCreatedResponse(existing, "posly_$id-newrawsecret456"))
    }

    override suspend fun getUsage(id: String): ApiKeyUsageResult = usageResult ?: ApiKeyUsageResult.Success(
        listOf(ApiKeyUsageResponse(id = "usage-1", method = "GET", path = "/orders", statusCode = 200, timestamp = "2026-01-01T00:00:00Z"))
    )
}
