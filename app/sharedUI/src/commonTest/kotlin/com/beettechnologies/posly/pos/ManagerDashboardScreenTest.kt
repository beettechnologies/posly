package com.beettechnologies.posly.pos

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.accessibility.hasOnClickLabel
import com.beettechnologies.posly.accessibility.hasRole
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun screenTestStore() = StoreResponse(
    id = "store-1",
    name = "Downtown",
    address = AddressDto(line1 = "1 Main St", city = "NY", postalCode = "10001", country = "US"),
    timezone = "America/New_York",
    currency = "USD",
    createdAt = 0L,
    updatedAt = 0L
)

private fun screenTestSales() = SalesAggregateResponse(
    storeId = "store-1",
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

private class FakeDashboardScreenStoreApi : StoreApi {
    override suspend fun createStore(request: CreateStoreRequest) = error("not used in this test")
    override suspend fun listStores(): StoreListResult = StoreListResult.Success(listOf(screenTestStore()))
    override suspend fun getStore(id: String): StoreResult = error("not used in this test")
    override suspend fun updateStore(id: String, request: UpdateStoreRequest) = error("not used in this test")
    override suspend fun deleteStore(id: String): DeleteStoreResult = error("not used in this test")
    override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoOutcome = error("not used in this test")
}

private class FakeDashboardScreenReportingApi : ReportingApi {
    override suspend fun getRealtimeSales(storeId: String): RealtimeSalesOutcome = RealtimeSalesOutcome.Success(screenTestSales())

    override suspend fun getTopProducts(storeId: String, limit: Int): TopProductsOutcome = TopProductsOutcome.Success(
        listOf(ProductSalesSummaryResponse(productId = "product-1", productName = "Widget", quantitySold = 5, revenue = 50.0))
    )

    override suspend fun getCashOnHand(storeId: String): CashOnHandOutcome =
        CashOnHandOutcome.Success(CashOnHandResponse(storeId = storeId, openShiftCount = 2, totalExpectedCash = 175.0, asOf = "2026-01-01T12:00:00Z"))
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ManagerDashboardScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `dashboard renders sales, transactions, top sellers, and cash on hand`() = runComposeUiTest {
        val viewModel = ManagerDashboardViewModel(FakeDashboardScreenReportingApi(), FakeDashboardScreenStoreApi())

        setContent { ManagerDashboardScreen(onBack = {}, onDrillDown = { _, _, _, _, _ -> }, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ManagerDashboardScreenTags.SALES_CARD).assertIsDisplayed()
        onNodeWithTag(ManagerDashboardScreenTags.TRANSACTIONS_CARD).assertIsDisplayed()
        onNodeWithTag(ManagerDashboardScreenTags.CASH_ON_HAND_CARD).assertIsDisplayed()
        onNodeWithTag(ManagerDashboardScreenTags.TOP_PRODUCT_ROW_PREFIX + "product-1").assertIsDisplayed()
    }

    @Test
    fun `clicking the sales card drills down into the whole day's window`() = runComposeUiTest {
        val viewModel = ManagerDashboardViewModel(FakeDashboardScreenReportingApi(), FakeDashboardScreenStoreApi())
        var drillDownArgs: List<Any?>? = null

        setContent {
            ManagerDashboardScreen(
                onBack = {},
                onDrillDown = { storeId, from, to, productId, productName -> drillDownArgs = listOf(storeId, from, to, productId, productName) },
                viewModel = viewModel
            )
        }
        waitForIdle()

        onNodeWithTag(ManagerDashboardScreenTags.SALES_CARD).performClick()
        waitForIdle()

        assertEquals(listOf("store-1", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", null, null), drillDownArgs)
    }

    @Test
    fun `clicking a top-product row drills down scoped to that product`() = runComposeUiTest {
        val viewModel = ManagerDashboardViewModel(FakeDashboardScreenReportingApi(), FakeDashboardScreenStoreApi())
        var drillDownArgs: List<Any?>? = null

        setContent {
            ManagerDashboardScreen(
                onBack = {},
                onDrillDown = { storeId, from, to, productId, productName -> drillDownArgs = listOf(storeId, from, to, productId, productName) },
                viewModel = viewModel
            )
        }
        waitForIdle()

        onNodeWithTag(ManagerDashboardScreenTags.TOP_PRODUCT_ROW_PREFIX + "product-1").performClick()
        waitForIdle()

        assertEquals(listOf("store-1", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "product-1", "Widget"), drillDownArgs)
    }

    // -------------------------------------------------------------------------
    // Accessibility
    // -------------------------------------------------------------------------

    @Test
    fun `drill-down cards are announced as buttons with a descriptive action label`() = runComposeUiTest {
        val viewModel = ManagerDashboardViewModel(FakeDashboardScreenReportingApi(), FakeDashboardScreenStoreApi())

        setContent { ManagerDashboardScreen(onBack = {}, onDrillDown = { _, _, _, _, _ -> }, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ManagerDashboardScreenTags.SALES_CARD)
            .assert(hasRole(Role.Button))
            .assert(hasOnClickLabel("View today's sales transactions"))
        onNodeWithTag(ManagerDashboardScreenTags.TRANSACTIONS_CARD)
            .assert(hasRole(Role.Button))
            .assert(hasOnClickLabel("View today's transactions"))
        onNodeWithTag(ManagerDashboardScreenTags.TOP_PRODUCT_ROW_PREFIX + "product-1")
            .assert(hasRole(Role.Button))
            .assert(hasOnClickLabel("View transactions for Widget"))
    }
}
