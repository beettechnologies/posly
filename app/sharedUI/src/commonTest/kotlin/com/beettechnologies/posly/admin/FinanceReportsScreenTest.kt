package com.beettechnologies.posly.admin

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
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

private fun screenTestSchedule() = ScheduledReportResponse(
    id = "schedule-1",
    storeId = "store-1",
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

private class FakeScreenStoreApi : StoreApi {
    override suspend fun createStore(request: CreateStoreRequest) = error("not used in this test")
    override suspend fun listStores(): StoreListResult = StoreListResult.Success(listOf(screenTestStore()))
    override suspend fun getStore(id: String): StoreResult = error("not used in this test")
    override suspend fun updateStore(id: String, request: UpdateStoreRequest) = error("not used in this test")
    override suspend fun deleteStore(id: String): DeleteStoreResult = error("not used in this test")
    override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoOutcome = error("not used in this test")
}

private class FakeScreenFinanceReportApi(private var schedules: List<ScheduledReportResponse>) : FinanceReportApi {
    var lastCreateRequest: CreateScheduleRequest? = null

    override suspend fun listSchedules(storeId: String?): ListSchedulesOutcome = ListSchedulesOutcome.Success(schedules)

    override suspend fun createSchedule(request: CreateScheduleRequest): CreateScheduleOutcome {
        lastCreateRequest = request
        val created = screenTestSchedule().copy(id = "new-schedule", recipients = request.recipients)
        schedules = schedules + created
        return CreateScheduleOutcome.Success(created)
    }

    override suspend fun deleteSchedule(id: String): DeleteScheduleOutcome {
        schedules = schedules.filter { it.id != id }
        return DeleteScheduleOutcome.Success
    }

    override suspend fun runScheduleNow(id: String): RunScheduleOutcome = RunScheduleOutcome.Success(
        ScheduledReportRunResponse(
            id = "run-1", scheduleId = id, periodStart = "2026-01-01T00:00:00Z", periodEnd = "2026-01-02T00:00:00Z",
            runAt = "2026-01-02T00:00:01Z", status = "SUCCESS", deliveredTo = listOf("finance@example.com"), failedRecipients = emptyList()
        )
    )

    override suspend fun listRuns(scheduleId: String): ListRunsOutcome = ListRunsOutcome.Success(emptyList())
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class FinanceReportsScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `existing schedules render as cards with their recipients`() = runComposeUiTest {
        val viewModel = FinanceReportsViewModel(FakeScreenFinanceReportApi(listOf(screenTestSchedule())), FakeScreenStoreApi())

        setContent { FinanceReportsScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(FinanceReportsScreenTags.SCHEDULE_CARD_PREFIX + "schedule-1").assertIsDisplayed()
    }

    @Test
    fun `filling the form and creating a schedule adds it to the list`() = runComposeUiTest {
        val api = FakeScreenFinanceReportApi(emptyList())
        val viewModel = FinanceReportsViewModel(api, FakeScreenStoreApi())

        setContent { FinanceReportsScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(FinanceReportsScreenTags.TYPE_OPTION_PREFIX + "TAX").performClick()
        onNodeWithTag(FinanceReportsScreenTags.FORMAT_OPTION_PREFIX + "PDF").performClick()
        onNodeWithTag(FinanceReportsScreenTags.FREQUENCY_OPTION_PREFIX + "WEEKLY").performClick()
        onNodeWithTag(FinanceReportsScreenTags.RECIPIENTS_FIELD).performTextInput("finance@example.com")
        waitForIdle()

        onNodeWithTag(FinanceReportsScreenTags.CREATE_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(FinanceReportsScreenTags.SCHEDULE_CARD_PREFIX + "new-schedule").assertIsDisplayed()
        kotlin.test.assertEquals("TAX", api.lastCreateRequest?.type)
        kotlin.test.assertEquals("PDF", api.lastCreateRequest?.format)
        kotlin.test.assertEquals("WEEKLY", api.lastCreateRequest?.frequency)
    }

    @Test
    fun `clicking Run Now shows the delivery result inline`() = runComposeUiTest {
        val viewModel = FinanceReportsViewModel(FakeScreenFinanceReportApi(listOf(screenTestSchedule())), FakeScreenStoreApi())

        setContent { FinanceReportsScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(FinanceReportsScreenTags.RUN_NOW_BUTTON_PREFIX + "schedule-1").performClick()
        waitForIdle()

        onNodeWithTag(FinanceReportsScreenTags.LAST_RUN_TEXT_PREFIX + "schedule-1").assertIsDisplayed()
    }

    @Test
    fun `deleting a schedule removes its card`() = runComposeUiTest {
        val viewModel = FinanceReportsViewModel(FakeScreenFinanceReportApi(listOf(screenTestSchedule())), FakeScreenStoreApi())

        setContent { FinanceReportsScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(FinanceReportsScreenTags.DELETE_BUTTON_PREFIX + "schedule-1").performClick()
        waitForIdle()

        onNodeWithTag(FinanceReportsScreenTags.EMPTY_TEXT).assertIsDisplayed()
    }
}
