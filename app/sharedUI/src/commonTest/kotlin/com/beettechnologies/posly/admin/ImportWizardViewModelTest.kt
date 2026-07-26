package com.beettechnologies.posly.admin

import com.beettechnologies.posly.catalog.DryRunOutcome
import com.beettechnologies.posly.catalog.DryRunResponse
import com.beettechnologies.posly.catalog.ImportApi
import com.beettechnologies.posly.catalog.ImportJobOutcome
import com.beettechnologies.posly.catalog.ImportJobResponse
import com.beettechnologies.posly.catalog.ImportRowOutcomeResponse
import com.beettechnologies.posly.catalog.PickedFile
import com.beettechnologies.posly.catalog.RollbackImportOutcome
import com.beettechnologies.posly.catalog.StartImportOutcome
import com.beettechnologies.posly.catalog.UploadCsvOutcome
import com.beettechnologies.posly.catalog.UploadCsvResponse
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
    createdCount: Int = 1,
    updatedCount: Int = 0,
    erroredCount: Int = 0,
    rolledBack: Boolean = false
) = ImportJobResponse(
    id = id,
    fileId = "file-1",
    status = status,
    totalRows = 1,
    processedRows = if (status == "PENDING") 0 else 1,
    createdCount = createdCount,
    updatedCount = updatedCount,
    erroredCount = erroredCount,
    rowOutcomes = if (status == "COMPLETED") listOf(ImportRowOutcomeResponse(1, "CREATED", "SKU-1")) else emptyList(),
    rolledBack = rolledBack,
    createdAt = "2026-01-01T00:00:00Z",
    completedAt = if (status == "COMPLETED") "2026-01-01T00:00:01Z" else null
)

private class FakeImportApi(
    private val uploadOutcome: UploadCsvOutcome? = null,
    private val dryRunOutcome: DryRunOutcome? = null,
    private val startOutcome: StartImportOutcome? = null,
    private val jobSequence: List<ImportJobResponse>? = null,
    private val rollbackOutcome: RollbackImportOutcome? = null
) : ImportApi {
    var lastUploadFileName: String? = null
    var lastDryRunMapping: Map<String, String>? = null
    var lastStartMapping: Map<String, String>? = null
    private var pollIndex = 0

    override suspend fun uploadCsv(fileName: String, bytes: ByteArray): UploadCsvOutcome {
        lastUploadFileName = fileName
        return uploadOutcome ?: UploadCsvOutcome.Success(
            UploadCsvResponse(
                fileId = "file-1",
                headers = listOf("sku", "name", "price"),
                previewRows = listOf(listOf("SKU-1", "Widget", "9.99")),
                totalRows = 1
            )
        )
    }

    override suspend fun dryRun(fileId: String, mapping: Map<String, String>): DryRunOutcome {
        lastDryRunMapping = mapping
        return dryRunOutcome ?: DryRunOutcome.Success(DryRunResponse(outcomes = listOf(ImportRowOutcomeResponse(1, "CREATED", "SKU-1"))))
    }

    override suspend fun startImport(fileId: String, mapping: Map<String, String>): StartImportOutcome {
        lastStartMapping = mapping
        return startOutcome ?: StartImportOutcome.Success(testJob(status = "PENDING"))
    }

    override suspend fun getJob(jobId: String): ImportJobOutcome {
        val sequence = jobSequence ?: return ImportJobOutcome.Success(testJob(status = "COMPLETED"))
        val index = pollIndex.coerceAtMost(sequence.size - 1)
        pollIndex++
        return ImportJobOutcome.Success(sequence[index])
    }

    override suspend fun rollback(jobId: String): RollbackImportOutcome =
        rollbackOutcome ?: RollbackImportOutcome.Success(testJob(status = "COMPLETED", rolledBack = true))
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImportWizardViewModelTest {

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
        val api = FakeImportApi()
        val viewModel = ImportWizardViewModel(api)

        viewModel.onFilePicked(PickedFile("products.csv", "sku,name,price\n".toByteArray()))
        advanceUntilIdle()

        assertEquals("products.csv", api.lastUploadFileName)
        assertEquals(ImportWizardStep.MAP_COLUMNS, viewModel.uiState.value.step)
        assertEquals("file-1", viewModel.uiState.value.fileId)
        assertEquals("sku", viewModel.uiState.value.mapping["SKU"])
        assertEquals("name", viewModel.uiState.value.mapping["NAME"])
        assertEquals("price", viewModel.uiState.value.mapping["PRICE"])
    }

    @Test
    fun `a forbidden upload surfaces a permission error and stays on the pick-file step`() = runTest(dispatcher) {
        val viewModel = ImportWizardViewModel(FakeImportApi(uploadOutcome = UploadCsvOutcome.Forbidden))

        viewModel.onFilePicked(PickedFile("products.csv", ByteArray(0)))
        advanceUntilIdle()

        assertEquals(ImportWizardStep.PICK_FILE, viewModel.uiState.value.step)
        assertEquals("You don't have permission to import products", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `canRunDryRun requires all of sku, name, and price to be mapped`() = runTest(dispatcher) {
        val viewModel = ImportWizardViewModel(FakeImportApi())
        viewModel.onFilePicked(PickedFile("products.csv", ByteArray(0)))
        advanceUntilIdle()

        // Auto-mapping already filled all three from the default fake headers.
        assertTrue(viewModel.uiState.value.canRunDryRun)

        viewModel.clearMapping("PRICE")
        assertFalse(viewModel.uiState.value.canRunDryRun)

        viewModel.setMapping("PRICE", "price")
        assertTrue(viewModel.uiState.value.canRunDryRun)
    }

    @Test
    fun `running a dry-run advances to the dry-run step with outcomes`() = runTest(dispatcher) {
        val api = FakeImportApi(
            dryRunOutcome = DryRunOutcome.Success(
                DryRunResponse(
                    outcomes = listOf(
                        ImportRowOutcomeResponse(1, "CREATED", "SKU-1"),
                        ImportRowOutcomeResponse(2, "ERROR", errors = listOf("Duplicate SKU 'SKU-1' also appears at row 1 in this file"))
                    )
                )
            )
        )
        val viewModel = ImportWizardViewModel(api)
        viewModel.onFilePicked(PickedFile("products.csv", ByteArray(0)))
        advanceUntilIdle()

        viewModel.runDryRun()
        advanceUntilIdle()

        assertEquals(ImportWizardStep.DRY_RUN, viewModel.uiState.value.step)
        assertEquals(2, viewModel.uiState.value.dryRunOutcomes.size)
        assertEquals("ERROR", viewModel.uiState.value.dryRunOutcomes[1].action)
    }

    @Test
    fun `backToMapping returns from the dry-run step without losing the mapping`() = runTest(dispatcher) {
        val viewModel = ImportWizardViewModel(FakeImportApi())
        viewModel.onFilePicked(PickedFile("products.csv", ByteArray(0)))
        advanceUntilIdle()
        viewModel.runDryRun()
        advanceUntilIdle()

        viewModel.backToMapping()

        assertEquals(ImportWizardStep.MAP_COLUMNS, viewModel.uiState.value.step)
        assertEquals("sku", viewModel.uiState.value.mapping["SKU"])
    }

    @Test
    fun `confirming an import polls until completion and shows the summary`() = runTest(dispatcher) {
        val api = FakeImportApi(
            jobSequence = listOf(
                testJob(status = "PENDING"),
                testJob(status = "RUNNING"),
                testJob(status = "COMPLETED", createdCount = 3, updatedCount = 1, erroredCount = 2)
            )
        )
        val viewModel = ImportWizardViewModel(api)
        viewModel.onFilePicked(PickedFile("products.csv", ByteArray(0)))
        advanceUntilIdle()

        viewModel.confirmImport()
        advanceUntilIdle()

        assertEquals(ImportWizardStep.SUMMARY, viewModel.uiState.value.step)
        val job = viewModel.uiState.value.job
        assertEquals("COMPLETED", job?.status)
        assertEquals(3, job?.createdCount)
        assertEquals(1, job?.updatedCount)
        assertEquals(2, job?.erroredCount)
    }

    @Test
    fun `rollback updates the job to rolled back`() = runTest(dispatcher) {
        val viewModel = ImportWizardViewModel(FakeImportApi())
        viewModel.onFilePicked(PickedFile("products.csv", ByteArray(0)))
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
        val viewModel = ImportWizardViewModel(FakeImportApi())
        viewModel.onFilePicked(PickedFile("products.csv", ByteArray(0)))
        advanceUntilIdle()
        viewModel.confirmImport()
        advanceUntilIdle()

        viewModel.startOver()

        assertEquals(ImportWizardUiState(), viewModel.uiState.value)
    }
}
