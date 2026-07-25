package com.beettechnologies.posly.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeAuthApi(
    private val loginResult: LoginOutcome = LoginOutcome.Success("access-1", "refresh-1"),
    private val mfaResult: MfaOutcome = MfaOutcome.Success("access-1", "refresh-1"),
    private val refreshResult: RefreshOutcome = RefreshOutcome.Success("access-2")
) : AuthApi {
    var lastMfaToken: String? = null
        private set
    var logoutCalled = false
        private set

    override suspend fun login(username: String, password: String): LoginOutcome = loginResult

    override suspend fun verifyMfa(mfaToken: String, code: String): MfaOutcome {
        lastMfaToken = mfaToken
        return mfaResult
    }

    override suspend fun refresh(refreshToken: String): RefreshOutcome = refreshResult

    override suspend fun logout(refreshToken: String) {
        logoutCalled = true
    }
}

private class InMemoryTokenStore : TokenStore {
    private var accessToken: String? = null
    private var refreshToken: String? = null

    override suspend fun saveTokens(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }

    override suspend fun getAccessToken(): String? = accessToken
    override suspend fun getRefreshToken(): String? = refreshToken

    override suspend fun clear() {
        accessToken = null
        refreshToken = null
    }
}

class AuthRepositoryTest {

    @Test
    fun `successful login persists tokens and transitions to LoggedIn`() = runTest {
        val tokenStore = InMemoryTokenStore()
        val repository = AuthRepository(FakeAuthApi(), tokenStore)

        val result = repository.login("cashier", "cashier123")

        assertTrue(result)
        assertEquals(AuthState.LoggedIn, repository.authState.value)
        assertEquals("access-1", tokenStore.getAccessToken())
        assertEquals("refresh-1", tokenStore.getRefreshToken())
        assertNull(repository.lastError.value)
    }

    @Test
    fun `wrong password surfaces inline error and stays LoggedOut`() = runTest {
        val api = FakeAuthApi(loginResult = LoginOutcome.InvalidCredentials("Invalid username or password"))
        val repository = AuthRepository(api, InMemoryTokenStore())

        val result = repository.login("cashier", "wrong-password")

        assertFalse(result)
        assertEquals(AuthState.LoggedOut, repository.authState.value)
        assertEquals("Invalid username or password", repository.lastError.value)
    }

    @Test
    fun `mfa required transitions to MfaRequired and verifying completes login`() = runTest {
        val api = FakeAuthApi(loginResult = LoginOutcome.MfaRequired("mfa-token-1"))
        val tokenStore = InMemoryTokenStore()
        val repository = AuthRepository(api, tokenStore)

        repository.login("manager", "manager123")
        val stateAfterLogin = repository.authState.value
        assertIs<AuthState.MfaRequired>(stateAfterLogin)
        assertEquals("mfa-token-1", stateAfterLogin.mfaToken)

        val verified = repository.verifyMfa("123456")

        assertTrue(verified)
        assertEquals("mfa-token-1", api.lastMfaToken)
        assertEquals(AuthState.LoggedIn, repository.authState.value)
        assertEquals("access-1", tokenStore.getAccessToken())
    }

    @Test
    fun `invalid mfa code surfaces inline error and stays in MfaRequired`() = runTest {
        val api = FakeAuthApi(
            loginResult = LoginOutcome.MfaRequired("mfa-token-1"),
            mfaResult = MfaOutcome.InvalidCode("Invalid or expired code")
        )
        val repository = AuthRepository(api, InMemoryTokenStore())
        repository.login("manager", "manager123")

        val verified = repository.verifyMfa("000000")

        assertFalse(verified)
        assertEquals("Invalid or expired code", repository.lastError.value)
        assertIs<AuthState.MfaRequired>(repository.authState.value)
    }

    @Test
    fun `refresh cycle updates access token and keeps refresh token`() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.saveTokens("stale-access", "refresh-1")
        val repository = AuthRepository(FakeAuthApi(), tokenStore)

        val refreshed = repository.refreshAccessToken()

        assertTrue(refreshed)
        assertEquals("access-2", tokenStore.getAccessToken())
        assertEquals("refresh-1", tokenStore.getRefreshToken())
    }

    @Test
    fun `unauthorized refresh logs the session out`() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.saveTokens("stale-access", "expired-refresh")
        val api = FakeAuthApi(refreshResult = RefreshOutcome.Unauthorized)
        val repository = AuthRepository(api, tokenStore)
        repository.bootstrap()
        assertEquals(AuthState.LoggedIn, repository.authState.value)

        val refreshed = repository.refreshAccessToken()

        assertFalse(refreshed)
        assertEquals(AuthState.LoggedOut, repository.authState.value)
        assertNull(tokenStore.getAccessToken())
        assertTrue(api.logoutCalled)
    }

    @Test
    fun `bootstrap restores LoggedIn state when a token is already persisted`() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.saveTokens("existing-access", "existing-refresh")
        val repository = AuthRepository(FakeAuthApi(), tokenStore)

        repository.bootstrap()

        assertEquals(AuthState.LoggedIn, repository.authState.value)
    }

    @Test
    fun `logout clears tokens and returns to LoggedOut`() = runTest {
        val tokenStore = InMemoryTokenStore()
        val api = FakeAuthApi()
        val repository = AuthRepository(api, tokenStore)
        repository.login("cashier", "cashier123")

        repository.logout()

        assertEquals(AuthState.LoggedOut, repository.authState.value)
        assertNull(tokenStore.getAccessToken())
        assertTrue(api.logoutCalled)
    }
}
