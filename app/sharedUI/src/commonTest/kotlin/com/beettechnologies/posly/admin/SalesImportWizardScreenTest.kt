package com.beettechnologies.posly.admin

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.catalog.PickedFile
import com.beettechnologies.posly.migration.RollbackSalesImportOutcome
import com.beettechnologies.posly.migration.SalesDryRunOutcome
import com.beettechnologies.posly.migration.SalesDryRunResponse
import com.beettechnologies.posly.migration.SalesImportApi
import com.beettechnologies.posly.migration.SalesImportJobOutcome
import com.beettechnologies.posly.migration.SalesImportJobResponse
import com.beettechnologies.posly.migration.SalesImportedOrderResponse
import com.beettechnologies.posly.migration.SalesOrderGroupPreviewResponse
import com.beettechnologies.posly.migration.SalesReconciliationOutcome
import com.beettechnologies.posly.migration.SalesReconciliationReportResponse
import com.beettechnologies.posly.migration.SalesRowOutcomeResponse
import com.beettechnologies.posly.migration.StartSalesImportOutcome
import com.beettechnologies.posly.migration.UploadSalesCsvOutcome
import com.beettechnologies.posly.migration.UploadSalesCsvResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

private fun screenTestJob(status: String = "COMPLETED") = SalesImportJobResponse(
    id = "job-1",
    fileId = "file-1",
    status = status,
    totalRows = 1,
    totalGroups = 1,
    processedGroups = 1,
    importedCount = 1,
    skippedUnmatchedCount = 0,
    skippedAlreadyImportedCount = 0,
    rowOutcomes = listOf(SalesRowOutcomeResponse(1, "ORD-1", "MATCHED", "SKU-1")),
    importedOrders = listOf(SalesImportedOrderResponse("ORD-1", "order-1", "store-1", 10.0, 1)),
    rolledBack = false,
    createdAt = "2026-01-01T00:00:00Z",
    completedAt = "2026-01-01T00:00:01Z"
)

private class FakeScreenSalesImportApi : SalesImportApi {
    override suspend fun uploadCsv(fileName: String, bytes: ByteArray): UploadSalesCsvOutcome =
        UploadSalesCsvOutcome.Success(
            UploadSalesCsvResponse(
                fileId = "file-1",
                headers = listOf("orderRef", "storeId", "sku", "qty", "unitPrice", "soldAt", "paymentMethod", "total"),
                previewRows = listOf(listOf("ORD-1", "store-1", "SKU-1", "1", "10.00", "2024-01-01T00:00:00Z", "CASH", "10.00")),
                totalRows = 1
            )
        )

    override suspend fun dryRun(fileId: String, mapping: Map<String, String>): SalesDryRunOutcome =
        SalesDryRunOutcome.Success(
            SalesDryRunResponse(
                rowOutcomes = listOf(SalesRowOutcomeResponse(1, "ORD-1", "MATCHED", "SKU-1")),
                groups = listOf(SalesOrderGroupPreviewResponse("ORD-1", listOf(1), importable = true, itemCount = 1, total = 10.0, errors = emptyList()))
            )
        )

    override suspend fun startImport(fileId: String, mapping: Map<String, String>): StartSalesImportOutcome =
        StartSalesImportOutcome.Success(screenTestJob())

    override suspend fun getJob(jobId: String): SalesImportJobOutcome = SalesImportJobOutcome.Success(screenTestJob())

    override suspend fun getReconciliationReport(jobId: String): SalesReconciliationOutcome =
        SalesReconciliationOutcome.Success(
            SalesReconciliationReportResponse(
                jobId = jobId,
                totalRowsProcessed = 1,
                totalGroups = 1,
                importedCount = 1,
                skippedUnmatchedCount = 0,
                skippedAlreadyImportedCount = 0,
                sampleMappings = listOf(SalesImportedOrderResponse("ORD-1", "order-1", "store-1", 10.0, 1)),
                generatedAt = "2026-01-01T00:00:02Z"
            )
        )

    override suspend fun rollback(jobId: String): RollbackSalesImportOutcome =
        RollbackSalesImportOutcome.Success(screenTestJob().copy(rolledBack = true))
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SalesImportWizardScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the wizard starts on the pick-file step`() = runComposeUiTest {
        val viewModel = SalesImportWizardViewModel(FakeScreenSalesImportApi())

        setContent { SalesImportWizardScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(SalesImportWizardScreenTags.PICK_FILE_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `picking a file advances to mapping, and the dry-run button is disabled until required fields are mapped`() = runComposeUiTest {
        val viewModel = SalesImportWizardViewModel(FakeScreenSalesImportApi())

        setContent { SalesImportWizardScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        waitForIdle()

        onNodeWithTag(SalesImportWizardScreenTags.RUN_DRY_RUN_BUTTON).performScrollTo().assertIsDisplayed()

        viewModel.clearMapping("TOTAL_AMOUNT")
        waitForIdle()
        onNodeWithTag(SalesImportWizardScreenTags.RUN_DRY_RUN_BUTTON).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `running the full wizard through dry-run, import, reconciliation, and rollback works end to end`() = runComposeUiTest {
        val viewModel = SalesImportWizardViewModel(FakeScreenSalesImportApi())

        setContent { SalesImportWizardScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        waitForIdle()

        onNodeWithTag(SalesImportWizardScreenTags.RUN_DRY_RUN_BUTTON).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag(SalesImportWizardScreenTags.DRY_RUN_GROUP_ROW_PREFIX + "ORD-1").performScrollTo().assertIsDisplayed()

        onNodeWithTag(SalesImportWizardScreenTags.CONFIRM_IMPORT_BUTTON).performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag(SalesImportWizardScreenTags.SUMMARY_TEXT).performScrollTo().assertIsDisplayed()

        onNodeWithTag(SalesImportWizardScreenTags.VIEW_RECONCILIATION_BUTTON).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag(SalesImportWizardScreenTags.RECONCILIATION_ROW_PREFIX + "ORD-1").performScrollTo().assertIsDisplayed()

        onNodeWithTag(SalesImportWizardScreenTags.ROLLBACK_BUTTON).performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag(SalesImportWizardScreenTags.START_OVER_BUTTON).performScrollTo().assertIsDisplayed()
    }
}
