package com.beettechnologies.posly.pos

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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun testStore(id: String = "store-1", name: String = "Downtown") = StoreResponse(
    id = id,
    name = name,
    address = AddressDto(line1 = "1 Main St", city = "NY", postalCode = "10001", country = "US"),
    timezone = "America/New_York",
    currency = "USD",
    createdAt = 0L,
    updatedAt = 0L
)

private fun testShift(
    id: String = "shift-1",
    storeId: String = "store-1",
    openingFloat: Double = 100.0,
    status: String = "OPEN"
) = ShiftResponse(
    id = id,
    storeId = storeId,
    cashierId = "cashier-1",
    openingFloat = openingFloat,
    openedAt = "2026-01-01T09:00:00Z",
    status = status
)

private class FakeStoreApi(private val stores: List<StoreResponse>) : StoreApi {
    override suspend fun createStore(request: CreateStoreRequest) = error("not used in this test")
    override suspend fun listStores(): StoreListResult = StoreListResult.Success(stores)
    override suspend fun getStore(id: String): StoreResult = error("not used in this test")
    override suspend fun updateStore(id: String, request: UpdateStoreRequest) = error("not used in this test")
    override suspend fun deleteStore(id: String): DeleteStoreResult = error("not used in this test")
    override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoOutcome = error("not used in this test")
}

private class FakeShiftApi(
    private val openOutcome: OpenShiftOutcome? = null,
    private val closeOutcome: CloseShiftOutcome? = null,
    private val expectedCash: Double = 0.0
) : ShiftApi {
    var lastOpenRequest: Pair<String, Double>? = null
    var lastCloseRequest: Triple<String, Double, String?>? = null

    override suspend fun openShift(storeId: String, openingFloat: Double): OpenShiftOutcome {
        lastOpenRequest = storeId to openingFloat
        return openOutcome ?: OpenShiftOutcome.Success(testShift(storeId = storeId, openingFloat = openingFloat))
    }

    override suspend fun closeShift(shiftId: String, closingCount: Double, note: String?): CloseShiftOutcome {
        lastCloseRequest = Triple(shiftId, closingCount, note)
        return closeOutcome ?: CloseShiftOutcome.Success(
            testShift(id = shiftId, status = "CLOSED").copy(
                closingCount = closingCount,
                expectedCash = expectedCash,
                variance = closingCount - expectedCash,
                varianceCause = if (closingCount == expectedCash) "NONE" else if (closingCount > expectedCash) "OVER" else "SHORT",
                note = note
            )
        )
    }

    override suspend fun getShift(id: String) = error("not used in this test")

    override suspend fun listShifts(storeId: String?, cashierId: String?) = emptyList<ShiftResponse>()

    override suspend fun getExpectedCash(shiftId: String): ExpectedCashOutcome =
        ExpectedCashOutcome.Success(expectedCash, "2026-01-01T10:00:00Z")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShiftViewModelTest {

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
    fun `loading stores populates the list and selects the first store`() = runTest(dispatcher) {
        val viewModel = ShiftViewModel(FakeShiftApi(), FakeStoreApi(listOf(testStore("store-1", "Downtown"), testStore("store-2", "Uptown"))))

        viewModel.loadStores()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.stores.size)
        assertEquals("store-1", viewModel.uiState.value.selectedStoreId)
        assertEquals("Downtown", viewModel.uiState.value.selectedStoreName)
    }

    @Test
    fun `opening a shift is disabled until a store and a valid float are set`() = runTest(dispatcher) {
        val viewModel = ShiftViewModel(FakeShiftApi(), FakeStoreApi(emptyList()))
        viewModel.loadStores()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canOpenShift)

        viewModel.selectStore("store-1")
        assertFalse(viewModel.uiState.value.canOpenShift, "still missing an opening float")

        viewModel.updateOpeningFloatInput("100")
        assertTrue(viewModel.uiState.value.canOpenShift)

        viewModel.updateOpeningFloatInput("-5")
        assertFalse(viewModel.uiState.value.canOpenShift, "a negative float must not be acceptable")
    }

    @Test
    fun `opening a shift persists the float and triggers an expected-cash refresh`() = runTest(dispatcher) {
        val shiftApi = FakeShiftApi(expectedCash = 100.0)
        val viewModel = ShiftViewModel(shiftApi, FakeStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateOpeningFloatInput("100")

        viewModel.openShift()
        advanceUntilIdle()

        assertEquals("store-1" to 100.0, shiftApi.lastOpenRequest)
        assertEquals("OPEN", viewModel.uiState.value.shift?.status)
        assertTrue(viewModel.uiState.value.isShiftOpen)
        assertEquals(100.0, viewModel.uiState.value.expectedCashPreview)
    }

    @Test
    fun `opening a shift when one is already open surfaces an error`() = runTest(dispatcher) {
        val viewModel = ShiftViewModel(FakeShiftApi(openOutcome = OpenShiftOutcome.ShiftAlreadyOpen), FakeStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateOpeningFloatInput("100")

        viewModel.openShift()
        advanceUntilIdle()

        assertEquals("You already have an open shift at this store", viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.shift)
    }

    @Test
    fun `closing a shift with a matching count reports zero variance and no override requirement`() = runTest(dispatcher) {
        val shiftApi = FakeShiftApi(expectedCash = 100.0)
        val viewModel = ShiftViewModel(shiftApi, FakeStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateOpeningFloatInput("100")
        viewModel.openShift()
        advanceUntilIdle()
        viewModel.updateClosingCountInput("100")

        viewModel.closeShift()
        advanceUntilIdle()

        assertEquals(Triple("shift-1", 100.0, null as String?), shiftApi.lastCloseRequest)
        assertTrue(viewModel.uiState.value.isShiftClosed)
        assertEquals(0.0, viewModel.uiState.value.shift?.variance)
        assertNull(viewModel.uiState.value.requiresOverrideOrNote)
    }

    @Test
    fun `a blank note is sent as null so the server enforces the override rule`() = runTest(dispatcher) {
        val shiftApi = FakeShiftApi(expectedCash = 100.0)
        val viewModel = ShiftViewModel(shiftApi, FakeStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateOpeningFloatInput("100")
        viewModel.openShift()
        advanceUntilIdle()
        viewModel.updateClosingCountInput("80")
        viewModel.updateNoteInput("   ")

        viewModel.closeShift()
        advanceUntilIdle()

        assertEquals(Triple("shift-1", 80.0, null as String?), shiftApi.lastCloseRequest)
    }

    @Test
    fun `an over-threshold variance surfaces a requirement instead of closing`() = runTest(dispatcher) {
        val shiftApi = FakeShiftApi(closeOutcome = CloseShiftOutcome.RequiresOverrideOrNote(-20.0, 5.0))
        val viewModel = ShiftViewModel(shiftApi, FakeStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateOpeningFloatInput("100")
        viewModel.openShift()
        advanceUntilIdle()
        viewModel.updateClosingCountInput("80")

        viewModel.closeShift()
        advanceUntilIdle()

        val requirement = viewModel.uiState.value.requiresOverrideOrNote
        assertEquals(-20.0, requirement?.variance)
        assertEquals(5.0, requirement?.threshold)
        assertTrue(viewModel.uiState.value.isShiftOpen, "the shift must remain open until the requirement is satisfied")
    }

    @Test
    fun `retrying a close after entering a note resends the note text`() = runTest(dispatcher) {
        val shiftApi = FakeShiftApi(closeOutcome = CloseShiftOutcome.RequiresOverrideOrNote(-20.0, 5.0))
        val viewModel = ShiftViewModel(shiftApi, FakeStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateOpeningFloatInput("100")
        viewModel.openShift()
        advanceUntilIdle()
        viewModel.updateClosingCountInput("80")
        viewModel.closeShift()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.requiresOverrideOrNote != null)

        viewModel.updateNoteInput("Till was short, reported to manager")
        viewModel.closeShift()
        advanceUntilIdle()

        assertEquals("Till was short, reported to manager", shiftApi.lastCloseRequest?.third)
    }

    @Test
    fun `starting a new shift clears the closed summary but keeps the store list`() = runTest(dispatcher) {
        val viewModel = ShiftViewModel(FakeShiftApi(expectedCash = 100.0), FakeStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateOpeningFloatInput("100")
        viewModel.openShift()
        advanceUntilIdle()
        viewModel.updateClosingCountInput("100")
        viewModel.closeShift()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isShiftClosed)

        viewModel.startNewShift()

        assertNull(viewModel.uiState.value.shift)
        assertEquals(1, viewModel.uiState.value.stores.size, "the store list should not need reloading")
        assertEquals("", viewModel.uiState.value.openingFloatInput)
    }

    @Test
    fun `shiftSummaryText includes the store name, figures, and note`() {
        val shift = testShift(status = "CLOSED").copy(
            closingCount = 90.0,
            expectedCash = 110.0,
            variance = -20.0,
            varianceCause = "SHORT",
            possibleReasons = listOf("Till shortage - miscount or missing cash during the shift"),
            note = "Reported to manager",
            closedAt = "2026-01-01T17:00:00Z"
        )

        val text = shiftSummaryText(shift, "Downtown")

        assertTrue(text.contains("Downtown"))
        assertTrue(text.contains("90.0"))
        assertTrue(text.contains("110.0"))
        assertTrue(text.contains("-20.0"))
        assertTrue(text.contains("Reported to manager"))
        assertTrue(text.contains("Till shortage"))
    }
}
