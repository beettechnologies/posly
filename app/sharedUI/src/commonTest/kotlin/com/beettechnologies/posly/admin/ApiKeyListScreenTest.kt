package com.beettechnologies.posly.admin

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.apikeys.ApiKeyListResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ApiKeyListScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `displays seeded keys and reaches create via callback`() = runComposeUiTest {
        val api = FakeApiKeyApi(mutableListOf(testApiKey("key-a", "First"), testApiKey("key-b", "Second")))
        val viewModel = ApiKeyListViewModel(api)
        var createClicked = false

        setContent { ApiKeyListScreen(onCreateKey = { createClicked = true }, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ApiKeyListScreenTags.ITEM_PREFIX + "key-a").assertIsDisplayed()
        onNodeWithTag(ApiKeyListScreenTags.ITEM_PREFIX + "key-b").assertIsDisplayed()

        onNodeWithTag(ApiKeyListScreenTags.CREATE_BUTTON).performClick()
        waitForIdle()
        assertTrue(createClicked)
    }

    @Test
    fun `revoking a key calls the API and the row reflects the revoked status`() = runComposeUiTest {
        val api = FakeApiKeyApi(mutableListOf(testApiKey("key-a")))
        val viewModel = ApiKeyListViewModel(api)

        setContent { ApiKeyListScreen(onCreateKey = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ApiKeyListScreenTags.REVOKE_BUTTON_PREFIX + "key-a").performClick()
        waitForIdle()

        assertEquals("key-a", api.lastRevokedId)
        onNodeWithTag(ApiKeyListScreenTags.STATUS_TEXT_PREFIX + "key-a").assertTextContains("REVOKED", substring = true)
    }

    @Test
    fun `rotating a key shows the new raw secret once, and dismissing clears it`() = runComposeUiTest {
        val api = FakeApiKeyApi(mutableListOf(testApiKey("key-a")))
        val viewModel = ApiKeyListViewModel(api)

        setContent { ApiKeyListScreen(onCreateKey = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ApiKeyListScreenTags.ROTATE_BUTTON_PREFIX + "key-a").performClick()
        waitForIdle()

        assertEquals("key-a", api.lastRotatedId)
        onNodeWithTag(ApiKeyListScreenTags.ROTATED_KEY_DIALOG).assertIsDisplayed()
        onNodeWithTag(ApiKeyListScreenTags.ROTATED_KEY_TEXT).assertIsDisplayed()

        onNodeWithTag(ApiKeyListScreenTags.ROTATED_KEY_DISMISS_BUTTON).performClick()
        waitForIdle()
        onNodeWithTag(ApiKeyListScreenTags.ROTATED_KEY_DIALOG).assertDoesNotExist()
    }

    @Test
    fun `viewing usage fetches and displays entries, and toggling hides them again`() = runComposeUiTest {
        val api = FakeApiKeyApi(mutableListOf(testApiKey("key-a")))
        val viewModel = ApiKeyListViewModel(api)

        setContent { ApiKeyListScreen(onCreateKey = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ApiKeyListScreenTags.USAGE_TOGGLE_PREFIX + "key-a").performClick()
        waitForIdle()
        onNodeWithTag(ApiKeyListScreenTags.USAGE_ROW_PREFIX + "usage-1").assertIsDisplayed()

        onNodeWithTag(ApiKeyListScreenTags.USAGE_TOGGLE_PREFIX + "key-a").performClick()
        waitForIdle()
        onNodeWithTag(ApiKeyListScreenTags.USAGE_ROW_PREFIX + "usage-1").assertDoesNotExist()
    }

    @Test
    fun `a forbidden result shows the permission error`() = runComposeUiTest {
        val viewModel = ApiKeyListViewModel(FakeApiKeyApi(listResult = ApiKeyListResult.Forbidden))

        setContent { ApiKeyListScreen(onCreateKey = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ApiKeyListScreenTags.ERROR_TEXT).assertIsDisplayed()
    }
}
