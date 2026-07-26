package com.beettechnologies.posly.admin

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.flags.FeatureFlagListResult
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
class FeatureFlagListScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `displays seeded flags and reaches create via callback`() = runComposeUiTest {
        val api = FakeFeatureFlagApi(mutableListOf(testFlag("flag-a"), testFlag("flag-b")))
        val viewModel = FeatureFlagListViewModel(api)
        var createClicked = false

        setContent {
            FeatureFlagListScreen(onCreateFlag = { createClicked = true }, onBack = {}, viewModel = viewModel)
        }
        waitForIdle()

        onNodeWithTag(FeatureFlagListScreenTags.ITEM_PREFIX + "flag-a").assertIsDisplayed()
        onNodeWithTag(FeatureFlagListScreenTags.ITEM_PREFIX + "flag-b").assertIsDisplayed()

        onNodeWithTag(FeatureFlagListScreenTags.CREATE_BUTTON).performClick()
        waitForIdle()
        assertTrue(createClicked)
    }

    @Test
    fun `toggling the switch calls the fake API with the flipped value`() = runComposeUiTest {
        val api = FakeFeatureFlagApi(mutableListOf(testFlag("flag-a", enabled = false)))
        val viewModel = FeatureFlagListViewModel(api)

        setContent { FeatureFlagListScreen(onCreateFlag = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(FeatureFlagListScreenTags.SWITCH_PREFIX + "flag-a").performClick()
        waitForIdle()

        assertEquals(Triple("flag-a", true, null), api.lastUpdate)
    }

    @Test
    fun `entering a percentage and tapping save calls the fake API`() = runComposeUiTest {
        val api = FakeFeatureFlagApi(mutableListOf(testFlag("flag-a")))
        val viewModel = FeatureFlagListViewModel(api)

        setContent { FeatureFlagListScreen(onCreateFlag = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(FeatureFlagListScreenTags.ROLLOUT_FIELD_PREFIX + "flag-a").performTextClearance()
        onNodeWithTag(FeatureFlagListScreenTags.ROLLOUT_FIELD_PREFIX + "flag-a").performTextInput("25")
        onNodeWithTag(FeatureFlagListScreenTags.SAVE_BUTTON_PREFIX + "flag-a").performClick()
        waitForIdle()

        assertEquals(Triple("flag-a", null, 25), api.lastUpdate)
    }

    @Test
    fun `a forbidden result shows the permission error`() = runComposeUiTest {
        val viewModel = FeatureFlagListViewModel(FakeFeatureFlagApi(listResult = FeatureFlagListResult.Forbidden))

        setContent { FeatureFlagListScreen(onCreateFlag = {}, onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(FeatureFlagListScreenTags.ERROR_TEXT).assertIsDisplayed()
    }
}
