package com.beettechnologies.posly.devices

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeDeviceApi(
    private val enrollResult: EnrollDeviceOutcome = EnrollDeviceOutcome.Success(
        EnrollDeviceResponse(
            deviceId = "device-1",
            storeId = "store-1",
            clientId = "client-1",
            clientSecret = "secret-1"
        )
    )
) : DeviceApi {
    var lastEnrollRequest: EnrollDeviceRequest? = null

    override suspend fun createPairCode(request: CreatePairCodeRequest): CreatePairCodeOutcome =
        error("not used in these tests")

    override suspend fun enrollDevice(request: EnrollDeviceRequest): EnrollDeviceOutcome {
        lastEnrollRequest = request
        return enrollResult
    }

    override suspend fun listDevices(storeId: String?): ListDevicesOutcome = error("not used in these tests")
    override suspend fun deprovisionDevice(id: String): DeprovisionDeviceOutcome = error("not used in these tests")
}

private class InMemoryDeviceCredentialsStore : DeviceCredentialsStore {
    private var credentials: DeviceCredentials? = null

    override suspend fun isPaired(): Boolean = credentials != null
    override suspend fun getCredentials(): DeviceCredentials? = credentials
    override suspend fun saveCredentials(credentials: DeviceCredentials) {
        this.credentials = credentials
    }

    override suspend fun clear() {
        credentials = null
    }
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PairingScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `valid code shows device credentials and persists them`() = runComposeUiTest {
        val api = FakeDeviceApi()
        val store = InMemoryDeviceCredentialsStore()
        val viewModel = PairingViewModel(api, store)
        var paired = false

        setContent { PairingScreen(onPaired = { paired = true }, viewModel = viewModel) }

        onNodeWithTag(PairingScreenTags.CODE_FIELD).performTextInput("ABC123")
        onNodeWithTag(PairingScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(PairingScreenTags.SUCCESS_TEXT).assertIsDisplayed()
        assertEquals("ABC123", api.lastEnrollRequest?.code)
        assertEquals("client-1", store.getCredentials()?.clientId)
        assertEquals(false, paired)
    }

    @Test
    fun `expired code shows inline error with retry guidance`() = runComposeUiTest {
        val api = FakeDeviceApi(enrollResult = EnrollDeviceOutcome.Rejected("Pairing code expired"))
        val store = InMemoryDeviceCredentialsStore()
        val viewModel = PairingViewModel(api, store)

        setContent { PairingScreen(onPaired = {}, viewModel = viewModel) }

        onNodeWithTag(PairingScreenTags.CODE_FIELD).performTextInput("EXPIRED1")
        onNodeWithTag(PairingScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(PairingScreenTags.ERROR_TEXT).assertTextContains("Pairing code expired", substring = true)
        assertNull(store.getCredentials())

        onNodeWithTag(PairingScreenTags.RETRY_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(PairingScreenTags.CODE_FIELD).assertTextContains("EXPIRED1", substring = true)
    }

    @Test
    fun `invalid code shows inline error`() = runComposeUiTest {
        val api = FakeDeviceApi(enrollResult = EnrollDeviceOutcome.Rejected("Pairing code not found"))
        val viewModel = PairingViewModel(api, InMemoryDeviceCredentialsStore())

        setContent { PairingScreen(onPaired = {}, viewModel = viewModel) }

        onNodeWithTag(PairingScreenTags.CODE_FIELD).performTextInput("BADCODE1")
        onNodeWithTag(PairingScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(PairingScreenTags.ERROR_TEXT).assertTextContains("Pairing code not found", substring = true)
    }
}
