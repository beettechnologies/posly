package com.beettechnologies.posly.admin

import com.beettechnologies.posly.flags.CreateFeatureFlagOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureFlagFormViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a blank key is rejected without calling the API`() = runTest(dispatcher) {
        val api = FakeFeatureFlagApi()
        val viewModel = FeatureFlagFormViewModel(api)

        viewModel.onDescriptionChange("desc")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals("Key is required", viewModel.uiState.value.errorMessage)
        assertEquals(null, api.lastCreate)
    }

    @Test
    fun `a successful create marks the form as created`() = runTest(dispatcher) {
        val api = FakeFeatureFlagApi()
        val viewModel = FeatureFlagFormViewModel(api)

        viewModel.onKeyChange("new-checkout")
        viewModel.onDescriptionChange("New checkout flow")
        viewModel.submit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.created)
        assertEquals("new-checkout" to "New checkout flow", api.lastCreate)
    }

    @Test
    fun `a duplicate key surfaces an error and does not mark the form as created`() = runTest(dispatcher) {
        val api = FakeFeatureFlagApi(createOutcome = CreateFeatureFlagOutcome.DuplicateKey)
        val viewModel = FeatureFlagFormViewModel(api)

        viewModel.onKeyChange("dup")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals("A flag with this key already exists", viewModel.uiState.value.errorMessage)
        assertEquals(false, viewModel.uiState.value.created)
    }
}
