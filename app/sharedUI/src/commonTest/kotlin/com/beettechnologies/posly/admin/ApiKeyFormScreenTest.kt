package com.beettechnologies.posly.admin

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.apikeys.CreateApiKeyOutcome
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
class ApiKeyFormScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `submitting a name and scopes creates a key and shows the raw secret exactly once`() = runComposeUiTest {
        val api = FakeApiKeyApi()
        val viewModel = ApiKeyFormViewModel(api)

        setContent { ApiKeyFormScreen(onDone = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ApiKeyFormScreenTags.NAME_FIELD).performTextInput("Accounting integration")
        onNodeWithTag(ApiKeyFormScreenTags.SCOPE_CHECKBOX_PREFIX + "ORDERS_READ").performClick()
        onNodeWithTag(ApiKeyFormScreenTags.CREATE_BUTTON).performClick()
        waitForIdle()

        assertEquals("Accounting integration" to listOf("ORDERS_READ"), api.lastCreate)
        onNodeWithTag(ApiKeyFormScreenTags.CREATED_KEY_TEXT).assertIsDisplayed()
        // The create form (not the list) never re-shows a raw key from any other source - this is
        // the one place it renders, straight from the create response.
        onNodeWithTag(ApiKeyFormScreenTags.NAME_FIELD).assertDoesNotExist()
    }

    @Test
    fun `submitting with no scopes selected shows a validation error instead of calling the API`() = runComposeUiTest {
        val api = FakeApiKeyApi()
        val viewModel = ApiKeyFormViewModel(api)

        setContent { ApiKeyFormScreen(onDone = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ApiKeyFormScreenTags.NAME_FIELD).performTextInput("No scopes")
        onNodeWithTag(ApiKeyFormScreenTags.CREATE_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ApiKeyFormScreenTags.ERROR_TEXT).assertIsDisplayed()
        assertEquals(null, api.lastCreate)
    }

    @Test
    fun `a forbidden result shows the permission error`() = runComposeUiTest {
        val viewModel = ApiKeyFormViewModel(FakeApiKeyApi(createOutcome = CreateApiKeyOutcome.Forbidden))

        setContent { ApiKeyFormScreen(onDone = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ApiKeyFormScreenTags.NAME_FIELD).performTextInput("x")
        onNodeWithTag(ApiKeyFormScreenTags.SCOPE_CHECKBOX_PREFIX + "REPORTS_READ").performClick()
        onNodeWithTag(ApiKeyFormScreenTags.CREATE_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ApiKeyFormScreenTags.ERROR_TEXT).assertIsDisplayed()
    }
}
