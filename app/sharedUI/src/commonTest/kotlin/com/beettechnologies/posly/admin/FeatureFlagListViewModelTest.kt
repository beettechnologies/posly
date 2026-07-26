package com.beettechnologies.posly.admin

import com.beettechnologies.posly.flags.CreateFeatureFlagOutcome
import com.beettechnologies.posly.flags.EvaluateFlagResult
import com.beettechnologies.posly.flags.FeatureFlagApi
import com.beettechnologies.posly.flags.FeatureFlagAuditLogEntryResponse
import com.beettechnologies.posly.flags.FeatureFlagListResult
import com.beettechnologies.posly.flags.FeatureFlagResponse
import com.beettechnologies.posly.flags.FlagEvaluationResponse
import com.beettechnologies.posly.flags.UpdateFeatureFlagOutcome
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun testFlag(
    key: String = "new-checkout",
    description: String = "New checkout flow",
    enabled: Boolean = false,
    rolloutPercentage: Int = 0
) = FeatureFlagResponse(
    id = "flag-$key",
    key = key,
    description = description,
    enabled = enabled,
    rolloutPercentage = rolloutPercentage,
    enabledStoreIds = emptyList(),
    createdAt = 0L,
    updatedAt = 0L
)

internal class FakeFeatureFlagApi(
    private val flags: MutableList<FeatureFlagResponse> = mutableListOf(),
    private val listResult: FeatureFlagListResult? = null,
    private val updateOutcome: UpdateFeatureFlagOutcome? = null,
    private val createOutcome: CreateFeatureFlagOutcome? = null
) : FeatureFlagApi {
    var lastUpdate: Triple<String, Boolean?, Int?>? = null
    var lastCreate: Pair<String, String>? = null

    override suspend fun listFlags(): FeatureFlagListResult = listResult ?: FeatureFlagListResult.Success(flags)

    override suspend fun createFlag(key: String, description: String, enabled: Boolean, rolloutPercentage: Int): CreateFeatureFlagOutcome {
        lastCreate = key to description
        return createOutcome ?: CreateFeatureFlagOutcome.Success(testFlag(key, description, enabled, rolloutPercentage))
    }

    override suspend fun updateFlag(key: String, enabled: Boolean?, rolloutPercentage: Int?, enabledStoreIds: List<String>?): UpdateFeatureFlagOutcome {
        lastUpdate = Triple(key, enabled, rolloutPercentage)
        if (updateOutcome != null) return updateOutcome
        val existing = flags.find { it.key == key } ?: return UpdateFeatureFlagOutcome.NotFound
        val updated = existing.copy(
            enabled = enabled ?: existing.enabled,
            rolloutPercentage = rolloutPercentage ?: existing.rolloutPercentage
        )
        val index = flags.indexOfFirst { it.key == key }
        flags[index] = updated
        return UpdateFeatureFlagOutcome.Success(updated)
    }

    override suspend fun evaluate(key: String, storeId: String): EvaluateFlagResult =
        EvaluateFlagResult.Success(FlagEvaluationResponse(key, storeId, enabled = false, reason = "FLAG_NOT_FOUND"))

    override suspend fun listAuditLog(event: String?): List<FeatureFlagAuditLogEntryResponse> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureFlagListViewModelTest {

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
    fun `loads and displays the seeded flags on init`() = runTest(dispatcher) {
        val api = FakeFeatureFlagApi(mutableListOf(testFlag("flag-a"), testFlag("flag-b", enabled = true, rolloutPercentage = 50)))
        val viewModel = FeatureFlagListViewModel(api)

        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.flags.size)
        assertEquals("50", viewModel.uiState.value.rolloutInputs["flag-b"])
    }

    @Test
    fun `forbidden surfaces a permission error`() = runTest(dispatcher) {
        val api = FakeFeatureFlagApi(listResult = FeatureFlagListResult.Forbidden)
        val viewModel = FeatureFlagListViewModel(api)

        advanceUntilIdle()

        assertEquals("You don't have permission to view feature flags", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `network error surfaces its message`() = runTest(dispatcher) {
        val api = FakeFeatureFlagApi(listResult = FeatureFlagListResult.NetworkError("connection refused"))
        val viewModel = FeatureFlagListViewModel(api)

        advanceUntilIdle()

        assertEquals("connection refused", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `toggling enabled saves immediately with the flipped value`() = runTest(dispatcher) {
        val api = FakeFeatureFlagApi(mutableListOf(testFlag("flag-a", enabled = false)))
        val viewModel = FeatureFlagListViewModel(api)
        advanceUntilIdle()

        viewModel.toggleEnabled("flag-a")
        advanceUntilIdle()

        assertEquals(Triple("flag-a", true, null), api.lastUpdate)
        assertTrue(viewModel.uiState.value.flags.single { it.key == "flag-a" }.enabled)
    }

    @Test
    fun `saving a valid rollout percentage updates state`() = runTest(dispatcher) {
        val api = FakeFeatureFlagApi(mutableListOf(testFlag("flag-a")))
        val viewModel = FeatureFlagListViewModel(api)
        advanceUntilIdle()

        viewModel.onRolloutInputChange("flag-a", "42")
        viewModel.saveRolloutPercentage("flag-a")
        advanceUntilIdle()

        assertEquals(Triple("flag-a", null, 42), api.lastUpdate)
        assertEquals(42, viewModel.uiState.value.flags.single { it.key == "flag-a" }.rolloutPercentage)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `saving a non-numeric rollout percentage is rejected client-side without calling the API`() = runTest(dispatcher) {
        val api = FakeFeatureFlagApi(mutableListOf(testFlag("flag-a")))
        val viewModel = FeatureFlagListViewModel(api)
        advanceUntilIdle()

        viewModel.onRolloutInputChange("flag-a", "not-a-number")
        viewModel.saveRolloutPercentage("flag-a")
        advanceUntilIdle()

        assertNull(api.lastUpdate)
        assertEquals("Rollout percentage must be a whole number between 0 and 100", viewModel.uiState.value.errorMessage)
    }
}
