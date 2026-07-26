package com.beettechnologies.posly.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beettechnologies.posly.catalog.PRODUCT_IMPORT_FIELDS
import com.beettechnologies.posly.catalog.REQUIRED_PRODUCT_IMPORT_FIELDS
import com.beettechnologies.posly.catalog.rememberFilePickerLauncher
import org.koin.compose.viewmodel.koinViewModel

object ImportWizardScreenTags {
    const val PICK_FILE_BUTTON = "import_wizard_pick_file_button"
    const val MAPPING_FIELD_BUTTON_PREFIX = "import_wizard_mapping_field_button_"
    const val RUN_DRY_RUN_BUTTON = "import_wizard_run_dry_run_button"
    const val BACK_TO_MAPPING_BUTTON = "import_wizard_back_to_mapping_button"
    const val CONFIRM_IMPORT_BUTTON = "import_wizard_confirm_import_button"
    const val ROLLBACK_BUTTON = "import_wizard_rollback_button"
    const val START_OVER_BUTTON = "import_wizard_start_over_button"
    const val ERROR_TEXT = "import_wizard_error_text"
    const val DRY_RUN_ROW_PREFIX = "import_wizard_dry_run_row_"
    const val SUMMARY_TEXT = "import_wizard_summary_text"
}

@Composable
fun ImportWizardScreen(
    onBack: () -> Unit,
    viewModel: ImportWizardViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pickFile = rememberFilePickerLauncher { file -> viewModel.onFilePicked(file) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Import Products", style = MaterialTheme.typography.headlineSmall)
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(ImportWizardScreenTags.ERROR_TEXT)
            )
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        when (uiState.step) {
            ImportWizardStep.PICK_FILE -> PickFileStep(pickFile)
            ImportWizardStep.MAP_COLUMNS -> MapColumnsStep(uiState, viewModel)
            ImportWizardStep.DRY_RUN -> DryRunStep(uiState, viewModel)
            ImportWizardStep.RUNNING -> RunningStep(uiState)
            ImportWizardStep.SUMMARY -> SummaryStep(uiState, viewModel)
        }
    }
}

@Composable
private fun PickFileStep(pickFile: () -> Unit) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text("Select a CSV file of products to create or update in bulk.")
        Button(
            onClick = pickFile,
            modifier = Modifier.padding(top = 16.dp).testTag(ImportWizardScreenTags.PICK_FILE_BUTTON)
        ) {
            Text("Choose CSV file")
        }
    }
}

@Composable
private fun MapColumnsStep(uiState: ImportWizardUiState, viewModel: ImportWizardViewModel) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text("File: ${uiState.fileName} (${uiState.headers.size} columns)", style = MaterialTheme.typography.titleMedium)
        Text("Map each product field to a column from your file.", modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

        PRODUCT_IMPORT_FIELDS.forEach { field ->
            var expanded by remember(field) { mutableStateOf(false) }
            val required = field in REQUIRED_PRODUCT_IMPORT_FIELDS
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = field + if (required) " *" else "",
                    modifier = Modifier.weight(1f)
                )
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.testTag(ImportWizardScreenTags.MAPPING_FIELD_BUTTON_PREFIX + field)
                    ) {
                        Text(uiState.mapping[field] ?: "Not mapped")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        if (!required) {
                            DropdownMenuItem(
                                text = { Text("Not mapped") },
                                onClick = {
                                    viewModel.clearMapping(field)
                                    expanded = false
                                }
                            )
                        }
                        uiState.headers.forEach { header ->
                            DropdownMenuItem(
                                text = { Text(header) },
                                onClick = {
                                    viewModel.setMapping(field, header)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = viewModel::runDryRun,
            enabled = uiState.canRunDryRun && !uiState.isLoading,
            modifier = Modifier.padding(top = 20.dp).testTag(ImportWizardScreenTags.RUN_DRY_RUN_BUTTON)
        ) {
            Text("Preview (Dry Run)")
        }
    }
}

@Composable
private fun DryRunStep(uiState: ImportWizardUiState, viewModel: ImportWizardViewModel) {
    val errorCount = uiState.dryRunOutcomes.count { it.action == "ERROR" }
    val createCount = uiState.dryRunOutcomes.count { it.action == "CREATED" }
    val updateCount = uiState.dryRunOutcomes.count { it.action == "UPDATED" }

    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text("Dry run: $createCount to create, $updateCount to update, $errorCount will fail.", style = MaterialTheme.typography.titleMedium)

        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).size(300.dp)) {
            items(uiState.dryRunOutcomes) { outcome ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag(ImportWizardScreenTags.DRY_RUN_ROW_PREFIX + outcome.rowNumber)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Row ${outcome.rowNumber}: ${outcome.action}${outcome.sku?.let { " ($it)" } ?: ""}")
                        if (outcome.errors.isNotEmpty()) {
                            Text(outcome.errors.joinToString("; "), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            OutlinedButton(
                onClick = viewModel::backToMapping,
                modifier = Modifier.testTag(ImportWizardScreenTags.BACK_TO_MAPPING_BUTTON)
            ) {
                Text("Back to mapping")
            }
            Button(
                onClick = viewModel::confirmImport,
                modifier = Modifier.padding(start = 12.dp).testTag(ImportWizardScreenTags.CONFIRM_IMPORT_BUTTON)
            ) {
                Text("Start Import")
            }
        }
    }
}

@Composable
private fun RunningStep(uiState: ImportWizardUiState) {
    val job = uiState.job
    Column(modifier = Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(
            "Importing... ${job?.processedRows ?: 0} / ${job?.totalRows ?: 0} rows",
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun SummaryStep(uiState: ImportWizardUiState, viewModel: ImportWizardViewModel) {
    val job = uiState.job ?: return
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = "Import ${job.status.lowercase()}: ${job.createdCount} created, ${job.updatedCount} updated, ${job.erroredCount} errored.",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(ImportWizardScreenTags.SUMMARY_TEXT)
        )

        if (job.erroredCount > 0) {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).size(200.dp)) {
                items(job.rowOutcomes.filter { it.action == "ERROR" }) { outcome ->
                    Text("Row ${outcome.rowNumber}: ${outcome.errors.joinToString("; ")}", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            if (!job.rolledBack) {
                OutlinedButton(
                    onClick = viewModel::rollback,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.testTag(ImportWizardScreenTags.ROLLBACK_BUTTON)
                ) {
                    Text("Undo this import")
                }
            } else {
                Text("This import has been rolled back.", modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = viewModel::startOver,
                modifier = Modifier.padding(start = 12.dp).testTag(ImportWizardScreenTags.START_OVER_BUTTON)
            ) {
                Text("Import Another File")
            }
        }
    }
}
