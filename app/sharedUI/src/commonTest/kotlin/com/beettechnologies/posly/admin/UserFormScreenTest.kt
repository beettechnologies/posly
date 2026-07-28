package com.beettechnologies.posly.admin

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class UserFormScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `filling the invite form and submitting shows the invite token`() = runComposeUiTest {
        val api = FakeUserApi()
        val viewModel = UserFormViewModel(api, object : com.beettechnologies.posly.stores.StoreApi {
            override suspend fun createStore(request: com.beettechnologies.posly.stores.CreateStoreRequest) = error("unused")
            override suspend fun listStores() = com.beettechnologies.posly.stores.StoreListResult.Success(emptyList())
            override suspend fun getStore(id: String) = error("unused")
            override suspend fun updateStore(id: String, request: com.beettechnologies.posly.stores.UpdateStoreRequest) = error("unused")
            override suspend fun deleteStore(id: String) = error("unused")
            override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray) = error("unused")
        })

        setContent { UserFormScreen(userId = null, onDone = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(UserFormScreenTags.USERNAME_FIELD).performTextInput("newhire")
        onNodeWithTag(UserFormScreenTags.EMAIL_FIELD).performTextInput("newhire@example.com")
        onNodeWithTag(UserFormScreenTags.INVITE_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(UserFormScreenTags.INVITE_TOKEN_TEXT).assertIsDisplayed()
        assertEquals("newhire", api.lastInviteRequest?.first)
    }

    @Test
    fun `edit mode shows the roles and status controls for an existing user`() = runComposeUiTest {
        val user = testUser(id = "u1", username = "cashier", roles = listOf("CASHIER"))
        val api = FakeUserApi(mutableListOf(user))
        val viewModel = UserFormViewModel(api, object : com.beettechnologies.posly.stores.StoreApi {
            override suspend fun createStore(request: com.beettechnologies.posly.stores.CreateStoreRequest) = error("unused")
            override suspend fun listStores() = com.beettechnologies.posly.stores.StoreListResult.Success(emptyList())
            override suspend fun getStore(id: String) = error("unused")
            override suspend fun updateStore(id: String, request: com.beettechnologies.posly.stores.UpdateStoreRequest) = error("unused")
            override suspend fun deleteStore(id: String) = error("unused")
            override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray) = error("unused")
        })

        setContent { UserFormScreen(userId = "u1", onDone = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(UserFormScreenTags.ROLE_CHECKBOX_PREFIX + "MANAGER").performClick()
        onNodeWithTag(UserFormScreenTags.SAVE_ROLES_BUTTON).performClick()
        waitForIdle()

        assertEquals(setOf("CASHIER", "MANAGER"), api.lastRolesUpdate?.second?.toSet())

        onNodeWithTag(UserFormScreenTags.TOGGLE_STATUS_BUTTON).performClick()
        waitForIdle()
        assertEquals("u1" to "DISABLED", api.lastStatusUpdate)
    }
}
