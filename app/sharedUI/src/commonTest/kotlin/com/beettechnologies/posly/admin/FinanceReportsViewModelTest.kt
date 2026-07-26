package com.beettechnologies.posly.admin

import com.beettechnologies.posly.finance.CreateScheduleOutcome
import com.beettechnologies.posly.finance.CreateScheduleRequest
import com.beettechnologies.posly.finance.DeleteScheduleOutcome
import com.beettechnologies.posly.finance.FinanceReportApi
import com.beettechnologies.posly.finance.ListRunsOutcome
import com.beettechnologies.posly.finance.ListSchedulesOutcome
import com.beettechnologies.posly.finance.RunScheduleOutcome
import com.beettechnologies.posly.finance.ScheduledReportResponse
import com.beettechnologies.posly.finance.ScheduledReportRunResponse
import com.beettechnologies.posly.stores.AddressDto
import com.beettechnologies.posly.stores.CreateStoreRequest
import com.beettechnologies.posly.stores.DeleteStoreResult
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import com.beettechnologies.posly.stores.StoreResult
import com.beettechnologies.posly.stores.UpdateStoreRequest
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

private fun testSchedule(id: String = "schedule-1", storeId: String = "store-1") = ScheduledReportResponse(
    id = id,
    storeId = storeId,
    type = "SALES",
    format = "CSV",
    timezone = "UTC",
    frequency = "DAILY",
    recipients = listOf("finance@example.com"),
    createdBy = "admin-1",
    createdAt = "2026-01-01T00:00:00Z",
    nextRunAt = "2026-01-02T00:00:00Z",
    lastRunAt = null,
    lastRunStatus = null
)

private fun testRun(scheduleId: String = "schedule-1") = ScheduledReportRunResponse(
    id = "run-1",
    scheduleId = scheduleId,
    periodStart = "2026-01-01T00:00:00Z",
    periodEnd = "2026-01-02T00:00:00Z",
    runAt = "2026-01-02T00:00:01Z",
    status = "SUCCESS",
    deliveredTo = listOf("finance@example.com"),
    failedRecipients = emptyList()
)

private class FakeFinanceStoreApi(private val stores: List<StoreResponse>) : StoreApi {
    override suspend fun createStore(request: CreateStoreRequest) = error("not used in this test")
    override suspend fun listStores(): StoreListResult = StoreListResult.Success(stores)
    override suspend fun getStore(id: String): StoreResult = error("not used in this test")
    override suspend fun updateStore(id: String, request: UpdateStoreRequest) = error("not used in this test")
    override suspend fun deleteStore(id: String): DeleteStoreResult = error("not used in this test")
}

private class FakeFinanceReportApi(
    private var schedules: List<ScheduledReportResponse> = emptyList(),
    private val createOutcome: CreateScheduleOutcome? = null,
    private val runOutcome: RunScheduleOutcome? = null
) : FinanceReportApi {
    var lastCreateRequest: CreateScheduleRequest? = null
    var lastDeletedId: String? = null
    var lastRunId: String? = null

    override suspend fun listSchedules(storeId: String?): ListSchedulesOutcome =
        ListSchedulesOutcome.Success(schedules.filter { storeId == null || it.storeId == storeId })

    override suspend fun createSchedule(request: CreateScheduleRequest): CreateScheduleOutcome {
        lastCreateRequest = request
        createOutcome?.let { return it }
        val created = testSchedule(id = "new-schedule", storeId = request.storeId)
        schedules = schedules + created
        return CreateScheduleOutcome.Success(created)
    }

    override suspend fun deleteSchedule(id: String): DeleteScheduleOutcome {
        lastDeletedId = id
        schedules = schedules.filter { it.id != id }
        return DeleteScheduleOutcome.Success
    }

    override suspend fun runScheduleNow(id: String): RunScheduleOutcome {
        lastRunId = id
        return runOutcome ?: RunScheduleOutcome.Success(testRun(id))
    }

    override suspend fun listRuns(scheduleId: String): ListRunsOutcome = ListRunsOutcome.Success(emptyList())
}

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceReportsViewModelTest {

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
    fun `loading stores selects the first store and loads its schedules`() = runTest(dispatcher) {
        val viewModel = FinanceReportsViewModel(
            FakeFinanceReportApi(schedules = listOf(testSchedule(storeId = "store-1"))),
            FakeFinanceStoreApi(listOf(testStore("store-1", "Downtown"), testStore("store-2", "Uptown")))
        )

        viewModel.loadStores()
        advanceUntilIdle()

        assertEquals("store-1", viewModel.uiState.value.selectedStoreId)
        assertEquals(1, viewModel.uiState.value.schedules.size)
    }

    @Test
    fun `canCreateSchedule requires a selected store and non-blank recipients`() = runTest(dispatcher) {
        val viewModel = FinanceReportsViewModel(FakeFinanceReportApi(), FakeFinanceStoreApi(emptyList()))

        assertFalse(viewModel.uiState.value.canCreateSchedule)

        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateRecipientsInput("finance@example.com")

        // Still no store selected since the store list was empty.
        assertFalse(viewModel.uiState.value.canCreateSchedule)
    }

    @Test
    fun `createSchedule splits comma-separated recipients and refreshes the list on success`() = runTest(dispatcher) {
        val api = FakeFinanceReportApi()
        val viewModel = FinanceReportsViewModel(api, FakeFinanceStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateRecipientsInput("a@example.com, b@example.com")
        viewModel.updateTypeInput("TAX")
        viewModel.updateFrequencyInput("MONTHLY")

        viewModel.createSchedule()
        advanceUntilIdle()

        assertEquals(listOf("a@example.com", "b@example.com"), api.lastCreateRequest?.recipients)
        assertEquals("TAX", api.lastCreateRequest?.type)
        assertEquals("MONTHLY", api.lastCreateRequest?.frequency)
        assertEquals(1, viewModel.uiState.value.schedules.size)
        assertEquals("", viewModel.uiState.value.recipientsInput)
    }

    @Test
    fun `a rejected schedule creation surfaces the server's message`() = runTest(dispatcher) {
        val api = FakeFinanceReportApi(createOutcome = CreateScheduleOutcome.Rejected("'nope' is not a valid email address"))
        val viewModel = FinanceReportsViewModel(api, FakeFinanceStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        viewModel.updateRecipientsInput("nope")

        viewModel.createSchedule()
        advanceUntilIdle()

        assertEquals("'nope' is not a valid email address", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `deleteSchedule removes it from the list on success`() = runTest(dispatcher) {
        val api = FakeFinanceReportApi(schedules = listOf(testSchedule()))
        val viewModel = FinanceReportsViewModel(api, FakeFinanceStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.schedules.size)

        viewModel.deleteSchedule("schedule-1")
        advanceUntilIdle()

        assertEquals("schedule-1", api.lastDeletedId)
        assertTrue(viewModel.uiState.value.schedules.isEmpty())
    }

    @Test
    fun `runScheduleNow records the run result keyed by schedule id`() = runTest(dispatcher) {
        val api = FakeFinanceReportApi(schedules = listOf(testSchedule()))
        val viewModel = FinanceReportsViewModel(api, FakeFinanceStoreApi(listOf(testStore())))
        viewModel.loadStores()
        advanceUntilIdle()

        viewModel.runScheduleNow("schedule-1")
        advanceUntilIdle()

        assertEquals("schedule-1", api.lastRunId)
        assertEquals("SUCCESS", viewModel.uiState.value.lastRun["schedule-1"]?.status)
    }
}
