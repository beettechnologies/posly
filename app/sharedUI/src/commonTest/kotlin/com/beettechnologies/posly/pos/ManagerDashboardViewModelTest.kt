package com.beettechnologies.posly.pos

import com.beettechnologies.posly.reporting.CashOnHandOutcome
import com.beettechnologies.posly.reporting.CashOnHandResponse
import com.beettechnologies.posly.reporting.ProductSalesSummaryResponse
import com.beettechnologies.posly.reporting.RealtimeSalesOutcome
import com.beettechnologies.posly.reporting.ReportingApi
import com.beettechnologies.posly.reporting.SalesAggregateResponse
import com.beettechnologies.posly.reporting.TopProductsOutcome
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
import kotlin.test.assertNull

private fun testStore(id: String = "store-1", name: String = "Downtown") = StoreResponse(
    id = id,
    name = name,
    address = AddressDto(line1 = "1 Main St", city = "NY", postalCode = "10001", country = "US"),
    timezone = "America/New_York",
    currency = "USD",
    createdAt = 0L,
    updatedAt = 0L
)

private fun testSales(storeId: String = "store-1") = SalesAggregateResponse(
    storeId = storeId,
    period = "DAILY",
    periodStart = "2026-01-01T00:00:00Z",
    periodEnd = "2026-01-02T00:00:00Z",
    orderCount = 4,
    itemsSold = 9,
    grossSales = 120.0,
    discountTotal = 5.0,
    taxCollected = 10.0,
    refundsTotal = 0.0,
    netSales = 125.0,
    generatedAt = "2026-01-01T12:00:00Z"
)

private fun testTopProduct(productId: String = "product-1") =
    ProductSalesSummaryResponse(productId = productId, productName = "Widget", quantitySold = 5, revenue = 50.0)

private fun testCashOnHand(storeId: String = "store-1") =
    CashOnHandResponse(storeId = storeId, openShiftCount = 2, totalExpectedCash = 175.0, asOf = "2026-01-01T12:00:00Z")

private class FakeDashboardStoreApi(private val stores: List<StoreResponse>) : StoreApi {
    override suspend fun createStore(request: CreateStoreRequest) = error("not used in this test")
    override suspend fun listStores(): StoreListResult = StoreListResult.Success(stores)
    override suspend fun getStore(id: String): StoreResult = error("not used in this test")
    override suspend fun updateStore(id: String, request: UpdateStoreRequest) = error("not used in this test")
    override suspend fun deleteStore(id: String): DeleteStoreResult = error("not used in this test")
    override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoOutcome = error("not used in this test")
}

private class FakeReportingApi(
    private val salesOutcome: RealtimeSalesOutcome? = null,
    private val topProductsOutcome: TopProductsOutcome? = null,
    private val cashOnHandOutcome: CashOnHandOutcome? = null
) : ReportingApi {
    var lastStoreId: String? = null

    override suspend fun getRealtimeSales(storeId: String): RealtimeSalesOutcome {
        lastStoreId = storeId
        return salesOutcome ?: RealtimeSalesOutcome.Success(testSales(storeId))
    }

    override suspend fun getTopProducts(storeId: String, limit: Int): TopProductsOutcome =
        topProductsOutcome ?: TopProductsOutcome.Success(listOf(testTopProduct()))

    override suspend fun getCashOnHand(storeId: String): CashOnHandOutcome =
        cashOnHandOutcome ?: CashOnHandOutcome.Success(testCashOnHand(storeId))
}

@OptIn(ExperimentalCoroutinesApi::class)
class ManagerDashboardViewModelTest {

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
    fun `loading stores selects the first store and refreshes all three metrics`() = runTest(dispatcher) {
        val viewModel = ManagerDashboardViewModel(
            FakeReportingApi(),
            FakeDashboardStoreApi(listOf(testStore("store-1", "Downtown"), testStore("store-2", "Uptown")))
        )

        viewModel.loadStores()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.stores.size)
        assertEquals("store-1", viewModel.uiState.value.selectedStoreId)
        assertEquals(125.0, viewModel.uiState.value.sales?.netSales)
        assertEquals(1, viewModel.uiState.value.topProducts.size)
        assertEquals(175.0, viewModel.uiState.value.cashOnHand?.totalExpectedCash)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `selecting a different store refreshes the metrics for that store`() = runTest(dispatcher) {
        val reportingApi = FakeReportingApi()
        val viewModel = ManagerDashboardViewModel(
            reportingApi,
            FakeDashboardStoreApi(listOf(testStore("store-1", "Downtown"), testStore("store-2", "Uptown")))
        )
        viewModel.loadStores()
        advanceUntilIdle()

        viewModel.selectStore("store-2")
        advanceUntilIdle()

        assertEquals("store-2", viewModel.uiState.value.selectedStoreId)
        assertEquals("store-2", reportingApi.lastStoreId)
    }

    @Test
    fun `a forbidden realtime-sales response surfaces a permission error`() = runTest(dispatcher) {
        val viewModel = ManagerDashboardViewModel(
            FakeReportingApi(salesOutcome = RealtimeSalesOutcome.Forbidden),
            FakeDashboardStoreApi(listOf(testStore()))
        )

        viewModel.loadStores()
        advanceUntilIdle()

        assertEquals("You don't have permission to view this dashboard", viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.sales)
    }

    @Test
    fun `refresh is a no-op until a store is selected`() = runTest(dispatcher) {
        val viewModel = ManagerDashboardViewModel(FakeReportingApi(), FakeDashboardStoreApi(emptyList()))

        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.sales)
    }
}
