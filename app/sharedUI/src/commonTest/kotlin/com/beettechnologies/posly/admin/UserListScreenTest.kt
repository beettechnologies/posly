package com.beettechnologies.posly.admin

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.accessibility.hasOnClickLabel
import com.beettechnologies.posly.accessibility.hasRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class UserListScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `displays seeded users and reaches invite and edit via callbacks`() = runComposeUiTest {
        val users = mutableListOf(testUser("u1", "admin", listOf("ADMIN")), testUser("u2", "cashier", listOf("CASHIER")))
        val viewModel = UserListViewModel(FakeUserApi(users))
        var invited = false
        var editedId: String? = null
        var ssoConfigured = false

        setContent {
            UserListScreen(
                onInviteUser = { invited = true },
                onEditUser = { editedId = it },
                onConfigureSso = { ssoConfigured = true },
                onBack = {},
                viewModel = viewModel
            )
        }
        waitForIdle()

        onNodeWithTag(UserListScreenTags.ITEM_PREFIX + "u1").assertIsDisplayed()
        onNodeWithTag(UserListScreenTags.ITEM_PREFIX + "u2").assertIsDisplayed()

        onNodeWithTag(UserListScreenTags.ITEM_PREFIX + "u2").performClick()
        waitForIdle()
        kotlin.test.assertEquals("u2", editedId)

        onNodeWithTag(UserListScreenTags.INVITE_BUTTON).performClick()
        waitForIdle()
        kotlin.test.assertTrue(invited)

        onNodeWithTag(UserListScreenTags.SSO_CONFIG_BUTTON).performClick()
        waitForIdle()
        kotlin.test.assertTrue(ssoConfigured)
    }

    @Test
    fun `a user row is announced as a button with a descriptive edit action label`() = runComposeUiTest {
        val users = mutableListOf(testUser("u1", "admin", listOf("ADMIN")))
        val viewModel = UserListViewModel(FakeUserApi(users))

        setContent {
            UserListScreen(onInviteUser = {}, onEditUser = {}, onConfigureSso = {}, onBack = {}, viewModel = viewModel)
        }
        waitForIdle()

        onNodeWithTag(UserListScreenTags.ITEM_PREFIX + "u1")
            .assert(hasRole(Role.Button))
            .assert(hasOnClickLabel("Edit admin"))
    }

    @Test
    fun `a forbidden result shows the permission error`() = runComposeUiTest {
        val viewModel = UserListViewModel(FakeUserApi(listResult = com.beettechnologies.posly.users.UserListResult.Forbidden))

        setContent { UserListScreen(onInviteUser = {}, onEditUser = {}, onConfigureSso = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(UserListScreenTags.ERROR_TEXT).assertIsDisplayed()
    }
}
