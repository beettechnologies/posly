package com.beettechnologies.posly.admin

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.stores.CreateStoreRequest
import com.beettechnologies.posly.stores.CreateTaxProfileRequest
import com.beettechnologies.posly.stores.DeleteStoreResult
import com.beettechnologies.posly.stores.DeleteTaxProfileResult
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import com.beettechnologies.posly.stores.StoreResult
import com.beettechnologies.posly.stores.TaxProfileApi
import com.beettechnologies.posly.stores.TaxProfileListResult
import com.beettechnologies.posly.stores.TaxProfileResult
import com.beettechnologies.posly.stores.UpdateStoreRequest
import com.beettechnologies.posly.stores.UpdateTaxProfileRequest
import com.beettechnologies.posly.stores.UploadLogoOutcome
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

private class FakeStoreApi(
    private val createResult: StoreResult = StoreResult.Success(
        StoreResponse(
            id = "store-1",
            name = "Downtown",
            address = com.beettechnologies.posly.stores.AddressDto(
                line1 = "1 Main St",
                city = "New York",
                postalCode = "10001",
                country = "US"
            ),
            timezone = "America/New_York",
            currency = "USD",
            taxProfileId = null,
            createdAt = 0,
            updatedAt = 0
        )
    )
) : StoreApi {
    var lastCreateRequest: CreateStoreRequest? = null

    override suspend fun createStore(request: CreateStoreRequest): StoreResult {
        lastCreateRequest = request
        return createResult
    }

    override suspend fun listStores(): StoreListResult = StoreListResult.Success(emptyList())
    override suspend fun getStore(id: String): StoreResult = createResult
    override suspend fun updateStore(id: String, request: UpdateStoreRequest): StoreResult = createResult
    override suspend fun deleteStore(id: String): DeleteStoreResult = DeleteStoreResult.Success
    override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoOutcome = error("not used in this test")
}

private class FakeTaxProfileApi : TaxProfileApi {
    override suspend fun createProfile(request: CreateTaxProfileRequest) =
        error("not used in these tests")

    override suspend fun listProfiles() = TaxProfileListResult.Success(emptyList())
    override suspend fun getProfile(id: String): TaxProfileResult = error("not used in these tests")
    override suspend fun updateProfile(id: String, request: UpdateTaxProfileRequest): TaxProfileResult =
        error("not used in these tests")

    override suspend fun deleteProfile(id: String) = DeleteTaxProfileResult.Success
    override suspend fun calculateTax(id: String, amount: Double) = error("not used in these tests")
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class StoreFormScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `blank required fields show an inline error and do not submit`() = runComposeUiTest {
        val api = FakeStoreApi()
        val viewModel = StoreFormViewModel(api, FakeTaxProfileApi())
        var saved = false

        setContent {
            StoreFormScreen(storeId = null, onSaved = { saved = true }, onBack = {}, viewModel = viewModel)
        }
        waitForIdle()

        onNodeWithTag(StoreFormScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(StoreFormScreenTags.ERROR_TEXT).assertIsDisplayed()
        assertEquals(null, api.lastCreateRequest)
        assertTrue(!saved)
    }

    @Test
    fun `filling all fields and submitting creates the store and navigates back`() = runComposeUiTest {
        val api = FakeStoreApi()
        val viewModel = StoreFormViewModel(api, FakeTaxProfileApi())
        var saved = false

        setContent {
            StoreFormScreen(storeId = null, onSaved = { saved = true }, onBack = {}, viewModel = viewModel)
        }
        waitForIdle()

        onNodeWithTag(StoreFormScreenTags.NAME_FIELD).performTextInput("Downtown")
        onNodeWithTag(StoreFormScreenTags.LINE1_FIELD).performTextInput("1 Main St")
        onNodeWithTag(StoreFormScreenTags.CITY_FIELD).performTextInput("New York")
        onNodeWithTag(StoreFormScreenTags.POSTAL_CODE_FIELD).performTextInput("10001")
        onNodeWithTag(StoreFormScreenTags.COUNTRY_FIELD).performTextInput("US")
        onNodeWithTag(StoreFormScreenTags.TIMEZONE_FIELD).performTextInput("America/New_York")
        onNodeWithTag(StoreFormScreenTags.CURRENCY_FIELD).performTextInput("USD")
        onNodeWithTag(StoreFormScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        assertEquals("Downtown", api.lastCreateRequest?.name)
        assertEquals("America/New_York", api.lastCreateRequest?.timezone)
        assertTrue(saved)
    }

    @Test
    fun `server validation error is surfaced inline`() = runComposeUiTest {
        val api = FakeStoreApi(createResult = StoreResult.ValidationError("'Bad/Zone' is not a valid timezone"))
        val viewModel = StoreFormViewModel(api, FakeTaxProfileApi())

        setContent {
            StoreFormScreen(storeId = null, onSaved = {}, onBack = {}, viewModel = viewModel)
        }
        waitForIdle()

        onNodeWithTag(StoreFormScreenTags.NAME_FIELD).performTextInput("Downtown")
        onNodeWithTag(StoreFormScreenTags.LINE1_FIELD).performTextInput("1 Main St")
        onNodeWithTag(StoreFormScreenTags.CITY_FIELD).performTextInput("New York")
        onNodeWithTag(StoreFormScreenTags.POSTAL_CODE_FIELD).performTextInput("10001")
        onNodeWithTag(StoreFormScreenTags.COUNTRY_FIELD).performTextInput("US")
        onNodeWithTag(StoreFormScreenTags.TIMEZONE_FIELD).performTextInput("Bad/Zone")
        onNodeWithTag(StoreFormScreenTags.CURRENCY_FIELD).performTextInput("USD")
        onNodeWithTag(StoreFormScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(StoreFormScreenTags.ERROR_TEXT).assertTextContains("not a valid timezone", substring = true)
    }
}
