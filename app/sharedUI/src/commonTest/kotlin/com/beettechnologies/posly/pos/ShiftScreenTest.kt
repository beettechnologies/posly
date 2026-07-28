package com.beettechnologies.posly.pos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.shifts.CloseShiftOutcome
import com.beettechnologies.posly.shifts.ExpectedCashOutcome
import com.beettechnologies.posly.shifts.OpenShiftOutcome
import com.beettechnologies.posly.shifts.ShiftApi
import com.beettechnologies.posly.shifts.ShiftResponse
import com.beettechnologies.posly.stores.AddressDto
import com.beettechnologies.posly.stores.CreateStoreRequest
import com.beettechnologies.posly.stores.DeleteStoreResult
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import com.beettechnologies.posly.stores.StoreResult
import com.beettechnologies.posly.stores.UpdateStoreRequest
import com.beettechnologies.posly.stores.UploadLogoOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

private fun screenTestStore() = StoreResponse(
    id = "store-1",
    name = "Downtown",
    address = AddressDto(line1 = "1 Main St", city = "NY", postalCode = "10001", country = "US"),
    timezone = "America/New_York",
    currency = "USD",
    createdAt = 0L,
    updatedAt = 0L
)

private fun screenTestShift(status: String = "OPEN") = ShiftResponse(
    id = "shift-1",
    storeId = "store-1",
    cashierId = "cashier-1",
    openingFloat = 100.0,
    openedAt = "2026-01-01T09:00:00Z",
    status = status
)

private class FakeScreenStoreApi : StoreApi {
    override suspend fun createStore(request: CreateStoreRequest) = error("not used in this test")
    override suspend fun listStores(): StoreListResult = StoreListResult.Success(listOf(screenTestStore()))
    override suspend fun getStore(id: String): StoreResult = error("not used in this test")
    override suspend fun updateStore(id: String, request: UpdateStoreRequest) = error("not used in this test")
    override suspend fun deleteStore(id: String): DeleteStoreResult = error("not used in this test")
    override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoOutcome = error("not used in this test")
}

private class FakeScreenShiftApi(private val closeOutcome: CloseShiftOutcome? = null) : ShiftApi {
    override suspend fun openShift(storeId: String, openingFloat: Double): OpenShiftOutcome =
        OpenShiftOutcome.Success(screenTestShift().copy(openingFloat = openingFloat))

    override suspend fun closeShift(shiftId: String, closingCount: Double, note: String?): CloseShiftOutcome =
        closeOutcome ?: CloseShiftOutcome.Success(
            screenTestShift(status = "CLOSED").copy(
                closingCount = closingCount,
                expectedCash = 100.0,
                variance = closingCount - 100.0,
                varianceCause = if (closingCount == 100.0) "NONE" else "OVER",
                note = note
            )
        )

    override suspend fun getShift(id: String) = error("not used in this test")
    override suspend fun listShifts(storeId: String?, cashierId: String?) = emptyList<ShiftResponse>()
    override suspend fun getExpectedCash(shiftId: String): ExpectedCashOutcome =
        ExpectedCashOutcome.Success(100.0, "2026-01-01T10:00:00Z")
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ShiftScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the open-shift form is shown first with the store button and float field`() = runComposeUiTest {
        val viewModel = ShiftViewModel(FakeScreenShiftApi(), FakeScreenStoreApi())

        setContent { ShiftScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ShiftScreenTags.STORE_BUTTON).assertIsDisplayed()
        onNodeWithTag(ShiftScreenTags.OPENING_FLOAT_FIELD).assertIsDisplayed()
        onNodeWithTag(ShiftScreenTags.OPEN_SHIFT_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun `selecting a store and entering a float enables opening the shift, then shows the close form`() = runComposeUiTest {
        val viewModel = ShiftViewModel(FakeScreenShiftApi(), FakeScreenStoreApi())

        setContent { ShiftScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(ShiftScreenTags.OPENING_FLOAT_FIELD).performTextInput("100")
        waitForIdle()

        onNodeWithTag(ShiftScreenTags.OPEN_SHIFT_BUTTON).assertIsEnabled()
        onNodeWithTag(ShiftScreenTags.OPEN_SHIFT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ShiftScreenTags.CLOSING_COUNT_FIELD).assertIsDisplayed()
        onNodeWithTag(ShiftScreenTags.EXPECTED_CASH_TEXT).assertIsDisplayed()
    }

    @Test
    fun `closing with a matching count shows the printable summary`() = runComposeUiTest {
        val viewModel = ShiftViewModel(FakeScreenShiftApi(), FakeScreenStoreApi())

        setContent { ShiftScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(ShiftScreenTags.OPENING_FLOAT_FIELD).performTextInput("100")
        onNodeWithTag(ShiftScreenTags.OPEN_SHIFT_BUTTON).performClick()
        waitForIdle()
        onNodeWithTag(ShiftScreenTags.CLOSING_COUNT_FIELD).performTextInput("100")
        waitForIdle()
        onNodeWithTag(ShiftScreenTags.CLOSE_SHIFT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ShiftScreenTags.SUMMARY_TEXT).assertIsDisplayed()
        onNodeWithTag(ShiftScreenTags.START_NEW_SHIFT_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `an over-threshold variance shows the override-or-note message instead of closing`() = runComposeUiTest {
        val viewModel = ShiftViewModel(
            FakeScreenShiftApi(closeOutcome = CloseShiftOutcome.RequiresOverrideOrNote(-20.0, 5.0)),
            FakeScreenStoreApi()
        )

        setContent { ShiftScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(ShiftScreenTags.OPENING_FLOAT_FIELD).performTextInput("100")
        onNodeWithTag(ShiftScreenTags.OPEN_SHIFT_BUTTON).performClick()
        waitForIdle()
        onNodeWithTag(ShiftScreenTags.CLOSING_COUNT_FIELD).performTextInput("80")
        waitForIdle()
        onNodeWithTag(ShiftScreenTags.CLOSE_SHIFT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ShiftScreenTags.OVERRIDE_REQUIRED_TEXT).assertIsDisplayed()
        onNodeWithTag(ShiftScreenTags.CLOSING_COUNT_FIELD).assertIsDisplayed()
    }
}
