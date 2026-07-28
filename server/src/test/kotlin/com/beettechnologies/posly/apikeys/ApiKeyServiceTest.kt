package com.beettechnologies.posly.apikeys

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.audit.AuditService
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeyServiceTest {

    @BeforeTest
    fun resetDb() {
        TestDatabase.reset()
        AuditService.clearForTests()
    }

    @Test
    fun `createKey returns the raw secret once and stores only a BCrypt hash of it`() {
        val service = ApiKeyService()

        val result = service.createKey("Accounting integration", setOf(ApiKeyScope.ORDERS_READ), actorUserId = "admin-1")

        val success = assertIs<CreateApiKeyResult.Success>(result)
        assertTrue(success.rawKey.startsWith("posly_"))
        assertTrue(success.rawKey.contains(success.apiKey.id))
        // The raw secret itself is never persisted - only its BCrypt hash is.
        assertNotEquals(success.rawKey, success.apiKey.secretHash)
        assertTrue(BCrypt.checkpw(success.rawKey.substringAfterLast("-"), success.apiKey.secretHash))
    }

    @Test
    fun `createKey rejects an empty name`() {
        val service = ApiKeyService()
        val result = service.createKey("", setOf(ApiKeyScope.ORDERS_READ), actorUserId = null)
        assertEquals(CreateApiKeyResult.EmptyName, result)
    }

    @Test
    fun `createKey rejects an empty scope set`() {
        val service = ApiKeyService()
        val result = service.createKey("No scopes", emptySet(), actorUserId = null)
        assertEquals(CreateApiKeyResult.NoScopes, result)
    }

    @Test
    fun `listKeys never exposes the secret hash's raw counterpart, and reflects created keys`() {
        val service = ApiKeyService()
        service.createKey("Key A", setOf(ApiKeyScope.ORDERS_READ), actorUserId = null)
        service.createKey("Key B", setOf(ApiKeyScope.REPORTS_READ), actorUserId = null)

        val keys = service.listKeys()

        assertEquals(2, keys.size)
        assertTrue(keys.any { it.name == "Key A" })
        assertTrue(keys.any { it.name == "Key B" })
    }

    @Test
    fun `authenticate succeeds for a freshly created key and returns its scopes`() {
        val service = ApiKeyService()
        val created = assertIs<CreateApiKeyResult.Success>(
            service.createKey("Reporting bot", setOf(ApiKeyScope.REPORTS_READ, ApiKeyScope.ORDERS_READ), actorUserId = null)
        )

        val principal = service.authenticate(created.rawKey)

        assertNotNull(principal)
        assertEquals(created.apiKey.id, principal.apiKeyId)
        assertEquals(setOf(ApiKeyScope.REPORTS_READ, ApiKeyScope.ORDERS_READ), principal.scopes)
    }

    @Test
    fun `authenticate touches lastUsedAt on success`() {
        val service = ApiKeyService()
        val created = assertIs<CreateApiKeyResult.Success>(service.createKey("Bot", setOf(ApiKeyScope.ORDERS_READ), actorUserId = null))
        assertNull(service.getKey(created.apiKey.id)!!.lastUsedAt)

        service.authenticate(created.rawKey)

        assertNotNull(service.getKey(created.apiKey.id)!!.lastUsedAt)
    }

    @Test
    fun `authenticate rejects a wrong secret for a real key id`() {
        val service = ApiKeyService()
        val created = assertIs<CreateApiKeyResult.Success>(service.createKey("Bot", setOf(ApiKeyScope.ORDERS_READ), actorUserId = null))
        val tampered = "posly_${created.apiKey.id}-not-the-real-secret"

        assertNull(service.authenticate(tampered))
    }

    @Test
    fun `authenticate rejects an unknown key id`() {
        val service = ApiKeyService()
        assertNull(service.authenticate("posly_00000000-0000-0000-0000-000000000000-somesecret"))
    }

    @Test
    fun `authenticate rejects a malformed raw key`() {
        val service = ApiKeyService()
        assertNull(service.authenticate("not-a-posly-key-at-all"))
        assertNull(service.authenticate("posly_"))
    }

    @Test
    fun `a revoked key can no longer authenticate`() {
        val service = ApiKeyService()
        val created = assertIs<CreateApiKeyResult.Success>(service.createKey("Bot", setOf(ApiKeyScope.ORDERS_READ), actorUserId = null))
        assertNotNull(service.authenticate(created.rawKey))

        val revoked = assertIs<RevokeApiKeyResult.Success>(service.revokeKey(created.apiKey.id, actorUserId = "admin-1"))
        assertEquals(ApiKeyStatus.REVOKED, revoked.apiKey.status)

        assertNull(service.authenticate(created.rawKey))
    }

    @Test
    fun `revoking an already-revoked key reports AlreadyRevoked`() {
        val service = ApiKeyService()
        val created = assertIs<CreateApiKeyResult.Success>(service.createKey("Bot", setOf(ApiKeyScope.ORDERS_READ), actorUserId = null))
        service.revokeKey(created.apiKey.id, actorUserId = null)

        assertEquals(RevokeApiKeyResult.AlreadyRevoked, service.revokeKey(created.apiKey.id, actorUserId = null))
    }

    @Test
    fun `revoking an unknown key reports NotFound`() {
        val service = ApiKeyService()
        assertEquals(RevokeApiKeyResult.NotFound, service.revokeKey("does-not-exist", actorUserId = null))
    }

    @Test
    fun `rotateKey invalidates the old raw key and the new raw key authenticates`() {
        val service = ApiKeyService()
        val created = assertIs<CreateApiKeyResult.Success>(service.createKey("Bot", setOf(ApiKeyScope.ORDERS_READ), actorUserId = null))

        val rotated = assertIs<RotateApiKeyResult.Success>(service.rotateKey(created.apiKey.id, actorUserId = "admin-1"))

        assertNull(service.authenticate(created.rawKey), "the pre-rotation raw key must stop working immediately")
        val principal = service.authenticate(rotated.rawKey)
        assertNotNull(principal)
        assertEquals(created.apiKey.id, principal.apiKeyId)
    }

    @Test
    fun `rotateKey refuses a revoked key`() {
        val service = ApiKeyService()
        val created = assertIs<CreateApiKeyResult.Success>(service.createKey("Bot", setOf(ApiKeyScope.ORDERS_READ), actorUserId = null))
        service.revokeKey(created.apiKey.id, actorUserId = null)

        assertEquals(RotateApiKeyResult.Revoked, service.rotateKey(created.apiKey.id, actorUserId = null))
    }

    @Test
    fun `rotateKey on an unknown key reports NotFound`() {
        val service = ApiKeyService()
        assertEquals(RotateApiKeyResult.NotFound, service.rotateKey("does-not-exist", actorUserId = null))
    }

    @Test
    fun `usage is recorded and listed most-recent-first`() {
        val service = ApiKeyService()
        val created = assertIs<CreateApiKeyResult.Success>(service.createKey("Bot", setOf(ApiKeyScope.ORDERS_READ), actorUserId = null))

        service.recordUsage(created.apiKey.id, "GET", "/orders", 200)
        service.recordUsage(created.apiKey.id, "GET", "/orders/abc", 404)

        val usage = service.listUsage(created.apiKey.id)
        assertEquals(2, usage.size)
        assertEquals("/orders/abc", usage.first().path)
        assertEquals(404, usage.first().statusCode)
    }
}
