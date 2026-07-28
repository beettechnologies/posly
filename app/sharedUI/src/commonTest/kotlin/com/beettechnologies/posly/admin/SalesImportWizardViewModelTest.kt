package com.beettechnologies.posly.admin

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

private fun testJob(
    id: String = "job-1",
    status: String = "COMPLETED",
    importedCount: Int = 1,
    skippedUnmatchedCount: Int = 0,
    skippedAlreadyImportedCount: Int = 0,
    rolledBack: Boolean = false
) = SalesImportJobResponse(
    id = id,
    fileId = "file-1",
    status = status,
    totalRows = 1,
    totalGroups = 1,
    processedGroups = if (status == "PENDING") 0 else 1,
    importedCount = importedCount,
    skippedUnmatchedCount = skippedUnmatchedCount,
    skippedAlreadyImportedCount = skippedAlreadyImportedCount,
    rowOutcomes = if (status == "COMPLETED") listOf(SalesRowOutcomeResponse(1, "ORD-1", "MATCHED", "SKU-1")) else emptyList(),
    importedOrders = if (status == "COMPLETED" && importedCount > 0) listOf(SalesImportedOrderResponse("ORD-1", "order-1", "store-1", 10.0, 1)) else emptyList(),
    rolledBack = rolledBack,
    createdAt = "2026-01-01T00:00:00Z",
    completedAt = if (status == "COMPLETED") "2026-01-01T00:00:01Z" else null
)

private class FakeSalesImportApi(
    private val uploadOutcome: UploadSalesCsvOutcome? = null,
    private val dryRunOutcome: SalesDryRunOutcome? = null,
    private val startOutcome: StartSalesImportOutcome? = null,
    private val jobSequence: List<SalesImportJobResponse>? = null,
    private val reconciliationOutcome: SalesReconciliationOutcome? = null,
    private val rollbackOutcome: RollbackSalesImportOutcome? = null
) : SalesImportApi {
    var lastUploadFileName: String? = null
    var lastDryRunMapping: Map<String, String>? = null
    var lastStartMapping: Map<String, String>? = null
    private var pollIndex = 0

    override suspend fun uploadCsv(fileName: String, bytes: ByteArray): UploadSalesCsvOutcome {
        lastUploadFileName = fileName
        return uploadOutcome ?: UploadSalesCsvOutcome.Success(
            UploadSalesCsvResponse(
                fileId = "file-1",
                headers = listOf("orderRef", "storeId", "sku", "qty", "unitPrice", "soldAt", "paymentMethod", "total"),
                previewRows = listOf(listOf("ORD-1", "store-1", "SKU-1", "1", "10.00", "2024-01-01T00:00:00Z", "CASH", "10.00")),
                totalRows = 1
            )
        )
    }

    override suspend fun dryRun(fileId: String, mapping: Map<String, String>): SalesDryRunOutcome {
        lastDryRunMapping = mapping
        return dryRunOutcome ?: SalesDryRunOutcome.Success(
            SalesDryRunResponse(
                rowOutcomes = listOf(SalesRowOutcomeResponse(1, "ORD-1", "MATCHED", "SKU-1")),
                groups = listOf(SalesOrderGroupPreviewResponse("ORD-1", listOf(1), importable = true, itemCount = 1, total = 10.0, errors = emptyList()))
            )
        )
    }

    override suspend fun startImport(fileId: String, mapping: Map<String, String>): StartSalesImportOutcome {
        lastStartMapping = mapping
        return startOutcome ?: StartSalesImportOutcome.Success(testJob(status = "PENDING"))
    }

    override suspend fun getJob(jobId: String): SalesImportJobOutcome {
        val sequence = jobSequence ?: return SalesImportJobOutcome.Success(testJob(status = "COMPLETED"))
        val index = pollIndex.coerceAtMost(sequence.size - 1)
        pollIndex++
        return SalesImportJobOutcome.Success(sequence[index])
    }

    override suspend fun getReconciliationReport(jobId: String): SalesReconciliationOutcome =
        reconciliationOutcome ?: SalesReconciliationOutcome.Success(
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
        rollbackOutcome ?: RollbackSalesImportOutcome.Success(testJob(status = "COMPLETED", rolledBack = true))
}

@OptIn(ExperimentalCoroutinesApi::class)
class SalesImportWizardViewModelTest {

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
    fun `picking a file uploads it and advances to mapping with auto-guessed columns`() = runTest(dispatcher) {
        val api = FakeSalesImportApi()
        val viewModel = SalesImportWizardViewModel(api)

        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        advanceUntilIdle()

        assertEquals("sales.csv", api.lastUploadFileName)
        assertEquals(SalesImportWizardStep.MAP_COLUMNS, viewModel.uiState.value.step)
        assertEquals("file-1", viewModel.uiState.value.fileId)
        assertEquals("orderRef", viewModel.uiState.value.mapping["ORDER_REFERENCE"])
        assertEquals("storeId", viewModel.uiState.value.mapping["STORE_ID"])
        assertEquals("sku", viewModel.uiState.value.mapping["SKU"])
        assertEquals("total", viewModel.uiState.value.mapping["TOTAL_AMOUNT"])
    }

    @Test
    fun `a forbidden upload surfaces a permission error and stays on the pick-file step`() = runTest(dispatcher) {
        val viewModel = SalesImportWizardViewModel(FakeSalesImportApi(uploadOutcome = UploadSalesCsvOutcome.Forbidden))

        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        advanceUntilIdle()

        assertEquals(SalesImportWizardStep.PICK_FILE, viewModel.uiState.value.step)
        assertEquals("You don't have permission to import sales", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `canRunDryRun requires all required fields to be mapped`() = runTest(dispatcher) {
        val viewModel = SalesImportWizardViewModel(FakeSalesImportApi())
        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canRunDryRun)

        viewModel.clearMapping("TOTAL_AMOUNT")
        assertFalse(viewModel.uiState.value.canRunDryRun)

        viewModel.setMapping("TOTAL_AMOUNT", "total")
        assertTrue(viewModel.uiState.value.canRunDryRun)
    }

    @Test
    fun `running a dry-run advances to the dry-run step with grouped outcomes`() = runTest(dispatcher) {
        val api = FakeSalesImportApi(
            dryRunOutcome = SalesDryRunOutcome.Success(
                SalesDryRunResponse(
                    rowOutcomes = listOf(
                        SalesRowOutcomeResponse(1, "ORD-1", "MATCHED", "SKU-1"),
                        SalesRowOutcomeResponse(2, "ORD-2", "UNMATCHED", "SKU-X", errors = listOf("sku 'SKU-X' does not match any product"))
                    ),
                    groups = listOf(
                        SalesOrderGroupPreviewResponse("ORD-1", listOf(1), importable = true, itemCount = 1, total = 10.0, errors = emptyList()),
                        SalesOrderGroupPreviewResponse("ORD-2", listOf(2), importable = false, itemCount = 1, total = 5.0, errors = listOf("sku 'SKU-X' does not match any product"))
                    )
                )
            )
        )
        val viewModel = SalesImportWizardViewModel(api)
        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        advanceUntilIdle()

        viewModel.runDryRun()
        advanceUntilIdle()

        assertEquals(SalesImportWizardStep.DRY_RUN, viewModel.uiState.value.step)
        assertEquals(2, viewModel.uiState.value.dryRunReport?.groups?.size)
        assertEquals(false, viewModel.uiState.value.dryRunReport?.groups?.get(1)?.importable)
    }

    @Test
    fun `backToMapping returns from the dry-run step without losing the mapping`() = runTest(dispatcher) {
        val viewModel = SalesImportWizardViewModel(FakeSalesImportApi())
        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        advanceUntilIdle()
        viewModel.runDryRun()
        advanceUntilIdle()

        viewModel.backToMapping()

        assertEquals(SalesImportWizardStep.MAP_COLUMNS, viewModel.uiState.value.step)
        assertEquals("orderRef", viewModel.uiState.value.mapping["ORDER_REFERENCE"])
    }

    @Test
    fun `confirming an import polls until completion and shows the summary`() = runTest(dispatcher) {
        val api = FakeSalesImportApi(
            jobSequence = listOf(
                testJob(status = "PENDING"),
                testJob(status = "RUNNING"),
                testJob(status = "COMPLETED", importedCount = 3, skippedUnmatchedCount = 1, skippedAlreadyImportedCount = 1)
            )
        )
        val viewModel = SalesImportWizardViewModel(api)
        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        advanceUntilIdle()

        viewModel.confirmImport()
        advanceUntilIdle()

        assertEquals(SalesImportWizardStep.SUMMARY, viewModel.uiState.value.step)
        val job = viewModel.uiState.value.job
        assertEquals("COMPLETED", job?.status)
        assertEquals(3, job?.importedCount)
        assertEquals(1, job?.skippedUnmatchedCount)
        assertEquals(1, job?.skippedAlreadyImportedCount)
    }

    @Test
    fun `viewing reconciliation fetches counts and sample record mappings`() = runTest(dispatcher) {
        val viewModel = SalesImportWizardViewModel(FakeSalesImportApi())
        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        advanceUntilIdle()
        viewModel.confirmImport()
        advanceUntilIdle()

        viewModel.viewReconciliation()
        advanceUntilIdle()

        val report = viewModel.uiState.value.reconciliation
        assertEquals(1, report?.importedCount)
        assertEquals(1, report?.sampleMappings?.size)
        assertEquals("ORD-1", report?.sampleMappings?.get(0)?.orderReference)
    }

    @Test
    fun `rollback updates the job to rolled back`() = runTest(dispatcher) {
        val viewModel = SalesImportWizardViewModel(FakeSalesImportApi())
        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        advanceUntilIdle()
        viewModel.confirmImport()
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.job?.rolledBack)

        viewModel.rollback()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.job?.rolledBack == true)
    }

    @Test
    fun `startOver resets the wizard back to the pick-file step`() = runTest(dispatcher) {
        val viewModel = SalesImportWizardViewModel(FakeSalesImportApi())
        viewModel.onFilePicked(PickedFile("sales.csv", ByteArray(0)))
        advanceUntilIdle()
        viewModel.confirmImport()
        advanceUntilIdle()

        viewModel.startOver()

        assertEquals(SalesImportWizardUiState(), viewModel.uiState.value)
    }
}
