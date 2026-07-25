package com.beettechnologies.posly.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeAuthApi(
    private val loginResult: LoginOutcome = LoginOutcome.Success("access-1", "refresh-1")
) : AuthApi {
    override suspend fun login(username: String, password: String): LoginOutcome = loginResult
    override suspend fun verifyMfa(mfaToken: String, code: String): MfaOutcome =
        MfaOutcome.Success("access-1", "refresh-1")
    override suspend fun refresh(refreshToken: String): RefreshOutcome = RefreshOutcome.Success("access-2")
    override suspend fun logout(refreshToken: String) = Unit
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

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LoginScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `wrong password shows inline error`() = runComposeUiTest {
        val api = FakeAuthApi(loginResult = LoginOutcome.InvalidCredentials("Invalid username or password"))
        val repository = AuthRepository(api, InMemoryTokenStore())
        val viewModel = LoginViewModel(repository)

        setContent { LoginScreen(viewModel = viewModel) }

        onNodeWithTag(LoginScreenTags.USERNAME_FIELD).performTextInput("cashier")
        onNodeWithTag(LoginScreenTags.PASSWORD_FIELD).performTextInput("wrong-password")
        onNodeWithTag(LoginScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(LoginScreenTags.ERROR_TEXT).assertIsDisplayed()
        onNodeWithTag(LoginScreenTags.ERROR_TEXT).assertTextContains("Invalid username or password")
        assertEquals(AuthState.LoggedOut, repository.authState.value)
    }

    @Test
    fun `valid credentials transition repository to LoggedIn`() = runComposeUiTest {
        val repository = AuthRepository(FakeAuthApi(), InMemoryTokenStore())
        val viewModel = LoginViewModel(repository)

        setContent { LoginScreen(viewModel = viewModel) }

        onNodeWithTag(LoginScreenTags.USERNAME_FIELD).performTextInput("cashier")
        onNodeWithTag(LoginScreenTags.PASSWORD_FIELD).performTextInput("cashier123")
        onNodeWithTag(LoginScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        assertEquals(AuthState.LoggedIn, repository.authState.value)
    }

    @Test
    fun `mfa required shows the MFA step`() = runComposeUiTest {
        val api = FakeAuthApi(loginResult = LoginOutcome.MfaRequired("mfa-token-1"))
        val repository = AuthRepository(api, InMemoryTokenStore())
        val viewModel = LoginViewModel(repository)

        setContent { LoginScreen(viewModel = viewModel) }

        onNodeWithTag(LoginScreenTags.USERNAME_FIELD).performTextInput("manager")
        onNodeWithTag(LoginScreenTags.PASSWORD_FIELD).performTextInput("manager123")
        onNodeWithTag(LoginScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        assertEquals(AuthState.MfaRequired("mfa-token-1"), repository.authState.value)
    }
}
