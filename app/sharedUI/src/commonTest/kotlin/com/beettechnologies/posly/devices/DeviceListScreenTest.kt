package com.beettechnologies.posly.devices

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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

private val TEST_STORE = StoreResponse(
    id = "store-1",
    name = "Downtown",
    address = AddressDto(line1 = "1 Main St", city = "New York", postalCode = "10001", country = "US"),
    timezone = "America/New_York",
    currency = "USD",
    taxProfileId = null,
    createdAt = 0,
    updatedAt = 0
)

private class FakeStoreApi : StoreApi {
    override suspend fun createStore(request: CreateStoreRequest): StoreResult = error("not used in these tests")
    override suspend fun listStores(): StoreListResult = StoreListResult.Success(listOf(TEST_STORE))
    override suspend fun getStore(id: String): StoreResult = error("not used in these tests")
    override suspend fun updateStore(id: String, request: UpdateStoreRequest): StoreResult = error("not used in these tests")
    override suspend fun deleteStore(id: String): DeleteStoreResult = error("not used in these tests")
    override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoOutcome = error("not used in these tests")
}

private class FakeDeviceListApi(
    initialDevices: List<DeviceResponse>,
    private val deprovisionOutcome: (DeviceResponse) -> DeprovisionDeviceOutcome = {
        DeprovisionDeviceOutcome.Success(it.copy(status = "DEPROVISIONED"))
    }
) : DeviceApi {
    private var devices = initialDevices

    override suspend fun createPairCode(request: CreatePairCodeRequest): CreatePairCodeOutcome =
        error("not used in these tests")

    override suspend fun enrollDevice(request: EnrollDeviceRequest): EnrollDeviceOutcome =
        error("not used in these tests")

    override suspend fun listDevices(storeId: String?): ListDevicesOutcome = ListDevicesOutcome.Success(devices)

    override suspend fun deprovisionDevice(id: String): DeprovisionDeviceOutcome {
        val device = devices.first { it.id == id }
        val outcome = deprovisionOutcome(device)
        if (outcome is DeprovisionDeviceOutcome.Success) {
            devices = devices.map { if (it.id == id) outcome.device else it }
        }
        return outcome
    }
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DeviceListScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `assigned device appears in the store device list with its last-seen timestamp`() = runComposeUiTest {
        val device = DeviceResponse(
            id = "device-1",
            storeId = "store-1",
            name = "Front Register",
            terminalType = "Verifone P400",
            enrolledAt = "2026-01-01T00:00:00Z",
            status = "ACTIVE",
            healthStatus = "ONLINE",
            lastSeenAt = "2026-01-01T00:05:00Z"
        )
        val viewModel = DeviceListViewModel(FakeStoreApi(), FakeDeviceListApi(listOf(device)))

        setContent { DeviceListScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(DeviceListScreenTags.ITEM_PREFIX + "device-1").assertIsDisplayed()
        onNodeWithText("Front Register", substring = true).assertIsDisplayed()
        onNodeWithText("2026-01-01T00:05:00Z", substring = true).assertIsDisplayed()
    }

    @Test
    fun `offline device shows offline status with last-seen and a deprovision option`() = runComposeUiTest {
        val device = DeviceResponse(
            id = "device-2",
            storeId = "store-1",
            name = "Back Counter",
            terminalType = "Android POS",
            enrolledAt = "2026-01-01T00:00:00Z",
            status = "ACTIVE",
            healthStatus = "OFFLINE",
            lastSeenAt = "2025-12-01T00:00:00Z"
        )
        val viewModel = DeviceListViewModel(FakeStoreApi(), FakeDeviceListApi(listOf(device)))

        setContent { DeviceListScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithText("OFFLINE", substring = true).assertIsDisplayed()
        onNodeWithTag(DeviceListScreenTags.DEPROVISION_BUTTON_PREFIX + "device-2").assertIsDisplayed()
    }

    @Test
    fun `confirming deprovision marks the device deprovisioned and removes the button`() = runComposeUiTest {
        val device = DeviceResponse(
            id = "device-3",
            storeId = "store-1",
            name = "Kiosk 1",
            terminalType = "Android POS",
            enrolledAt = "2026-01-01T00:00:00Z",
            status = "ACTIVE",
            healthStatus = "NEVER_SEEN",
            lastSeenAt = null
        )
        val viewModel = DeviceListViewModel(FakeStoreApi(), FakeDeviceListApi(listOf(device)))

        setContent { DeviceListScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(DeviceListScreenTags.DEPROVISION_BUTTON_PREFIX + "device-3").performClick()
        waitForIdle()
        onNodeWithTag(DeviceListScreenTags.CONFIRM_DEPROVISION_BUTTON).performClick()
        waitForIdle()

        onNodeWithText("Deprovisioned", substring = true).assertIsDisplayed()
    }

    @Test
    fun `forbidden device list shows an inline error`() = runComposeUiTest {
        val viewModel = DeviceListViewModel(
            FakeStoreApi(),
            object : DeviceApi {
                override suspend fun createPairCode(request: CreatePairCodeRequest) = error("not used")
                override suspend fun enrollDevice(request: EnrollDeviceRequest) = error("not used")
                override suspend fun listDevices(storeId: String?) = ListDevicesOutcome.Forbidden
                override suspend fun deprovisionDevice(id: String) = error("not used")
            }
        )

        setContent { DeviceListScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(DeviceListScreenTags.ERROR_TEXT)
            .assertTextContains("don't have permission", substring = true)
    }
}
