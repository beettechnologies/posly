package com.beettechnologies.posly.admin

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

private fun screenTestJob(status: String = "COMPLETED") = ImportJobResponse(
    id = "job-1",
    fileId = "file-1",
    status = status,
    totalRows = 1,
    processedRows = 1,
    createdCount = 1,
    updatedCount = 0,
    erroredCount = 0,
    rowOutcomes = listOf(ImportRowOutcomeResponse(1, "CREATED", "SKU-1")),
    rolledBack = false,
    createdAt = "2026-01-01T00:00:00Z",
    completedAt = "2026-01-01T00:00:01Z"
)

private class FakeScreenImportApi : ImportApi {
    override suspend fun uploadCsv(fileName: String, bytes: ByteArray): UploadCsvOutcome =
        UploadCsvOutcome.Success(
            UploadCsvResponse(fileId = "file-1", headers = listOf("sku", "name", "price"), previewRows = listOf(listOf("SKU-1", "Widget", "9.99")), totalRows = 1)
        )

    override suspend fun dryRun(fileId: String, mapping: Map<String, String>): DryRunOutcome =
        DryRunOutcome.Success(DryRunResponse(outcomes = listOf(ImportRowOutcomeResponse(1, "CREATED", "SKU-1"))))

    override suspend fun startImport(fileId: String, mapping: Map<String, String>): StartImportOutcome =
        StartImportOutcome.Success(screenTestJob())

    override suspend fun getJob(jobId: String): ImportJobOutcome = ImportJobOutcome.Success(screenTestJob())

    override suspend fun rollback(jobId: String): RollbackImportOutcome =
        RollbackImportOutcome.Success(screenTestJob().copy(rolledBack = true))
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ImportWizardScreenTest {

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
        val viewModel = ImportWizardViewModel(FakeScreenImportApi())

        setContent { ImportWizardScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ImportWizardScreenTags.PICK_FILE_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `picking a file advances to mapping, and the dry-run button is disabled until required fields are mapped`() = runComposeUiTest {
        val viewModel = ImportWizardViewModel(FakeScreenImportApi())

        setContent { ImportWizardScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        viewModel.onFilePicked(PickedFile("products.csv", ByteArray(0)))
        waitForIdle()

        // Auto-mapping matched sku/name/price from the fake's headers already.
        onNodeWithTag(ImportWizardScreenTags.RUN_DRY_RUN_BUTTON).assertIsDisplayed()

        viewModel.clearMapping("PRICE")
        waitForIdle()
        onNodeWithTag(ImportWizardScreenTags.RUN_DRY_RUN_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun `running the full wizard through to a rollback works end to end`() = runComposeUiTest {
        val viewModel = ImportWizardViewModel(FakeScreenImportApi())

        setContent { ImportWizardScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        viewModel.onFilePicked(PickedFile("products.csv", ByteArray(0)))
        waitForIdle()

        onNodeWithTag(ImportWizardScreenTags.RUN_DRY_RUN_BUTTON).performClick()
        waitForIdle()
        onNodeWithTag(ImportWizardScreenTags.DRY_RUN_ROW_PREFIX + "1").assertIsDisplayed()

        onNodeWithTag(ImportWizardScreenTags.CONFIRM_IMPORT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ImportWizardScreenTags.SUMMARY_TEXT).assertIsDisplayed()
        onNodeWithTag(ImportWizardScreenTags.ROLLBACK_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ImportWizardScreenTags.START_OVER_BUTTON).assertIsDisplayed()
    }
}
