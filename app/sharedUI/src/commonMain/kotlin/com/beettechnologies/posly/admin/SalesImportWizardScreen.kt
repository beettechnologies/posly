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
import com.beettechnologies.posly.accessibility.statusMessage
import com.beettechnologies.posly.catalog.rememberFilePickerLauncher
import com.beettechnologies.posly.migration.REQUIRED_SALES_IMPORT_FIELDS
import com.beettechnologies.posly.migration.SALES_IMPORT_FIELDS
import org.koin.compose.viewmodel.koinViewModel

object SalesImportWizardScreenTags {
    const val PICK_FILE_BUTTON = "sales_import_wizard_pick_file_button"
    const val MAPPING_FIELD_BUTTON_PREFIX = "sales_import_wizard_mapping_field_button_"
    const val RUN_DRY_RUN_BUTTON = "sales_import_wizard_run_dry_run_button"
    const val BACK_TO_MAPPING_BUTTON = "sales_import_wizard_back_to_mapping_button"
    const val CONFIRM_IMPORT_BUTTON = "sales_import_wizard_confirm_import_button"
    const val ROLLBACK_BUTTON = "sales_import_wizard_rollback_button"
    const val START_OVER_BUTTON = "sales_import_wizard_start_over_button"
    const val ERROR_TEXT = "sales_import_wizard_error_text"
    const val DRY_RUN_GROUP_ROW_PREFIX = "sales_import_wizard_dry_run_group_row_"
    const val SUMMARY_TEXT = "sales_import_wizard_summary_text"
    const val VIEW_RECONCILIATION_BUTTON = "sales_import_wizard_view_reconciliation_button"
    const val RECONCILIATION_ROW_PREFIX = "sales_import_wizard_reconciliation_row_"
}

@Composable
fun SalesImportWizardScreen(
    onBack: () -> Unit,
    viewModel: SalesImportWizardViewModel = koinViewModel()
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
            Text("Import Historical Sales", style = MaterialTheme.typography.headlineSmall)
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(SalesImportWizardScreenTags.ERROR_TEXT).statusMessage()
            )
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        when (uiState.step) {
            SalesImportWizardStep.PICK_FILE -> PickFileStep(pickFile)
            SalesImportWizardStep.MAP_COLUMNS -> MapColumnsStep(uiState, viewModel)
            SalesImportWizardStep.DRY_RUN -> DryRunStep(uiState, viewModel)
            SalesImportWizardStep.RUNNING -> RunningStep(uiState)
            SalesImportWizardStep.SUMMARY -> SummaryStep(uiState, viewModel)
        }
    }
}

@Composable
private fun PickFileStep(pickFile: () -> Unit) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text("Select a CSV of historical sales - one row per line item, grouped by an order reference column.")
        Button(
            onClick = pickFile,
            modifier = Modifier.padding(top = 16.dp).testTag(SalesImportWizardScreenTags.PICK_FILE_BUTTON)
        ) {
            Text("Choose CSV file")
        }
    }
}

@Composable
private fun MapColumnsStep(uiState: SalesImportWizardUiState, viewModel: SalesImportWizardViewModel) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text("File: ${uiState.fileName} (${uiState.headers.size} columns)", style = MaterialTheme.typography.titleMedium)
        Text("Map each order field to a column from your file.", modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

        SALES_IMPORT_FIELDS.forEach { field ->
            var expanded by remember(field) { mutableStateOf(false) }
            val required = field in REQUIRED_SALES_IMPORT_FIELDS
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = field + if (required) " *" else "",
                    modifier = Modifier.weight(1f)
                )
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.testTag(SalesImportWizardScreenTags.MAPPING_FIELD_BUTTON_PREFIX + field)
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
            modifier = Modifier.padding(top = 20.dp).testTag(SalesImportWizardScreenTags.RUN_DRY_RUN_BUTTON)
        ) {
            Text("Preview (Dry Run)")
        }
    }
}

@Composable
private fun DryRunStep(uiState: SalesImportWizardUiState, viewModel: SalesImportWizardViewModel) {
    val report = uiState.dryRunReport
    val importableCount = report?.groups?.count { it.importable } ?: 0
    val notImportableCount = (report?.groups?.size ?: 0) - importableCount

    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            "Dry run: $importableCount order(s) ready to import, $notImportableCount will be skipped.",
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).size(300.dp)) {
            items(report?.groups.orEmpty()) { group ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .testTag(SalesImportWizardScreenTags.DRY_RUN_GROUP_ROW_PREFIX + group.orderReference)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Order ${group.orderReference}: ${group.itemCount} item(s), ${if (group.importable) "MATCHED" else "UNMATCHED"}")
                        if (group.errors.isNotEmpty()) {
                            Text(group.errors.joinToString("; "), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            OutlinedButton(
                onClick = viewModel::backToMapping,
                modifier = Modifier.testTag(SalesImportWizardScreenTags.BACK_TO_MAPPING_BUTTON)
            ) {
                Text("Back to mapping")
            }
            Button(
                onClick = viewModel::confirmImport,
                modifier = Modifier.padding(start = 12.dp).testTag(SalesImportWizardScreenTags.CONFIRM_IMPORT_BUTTON)
            ) {
                Text("Start Import")
            }
        }
    }
}

@Composable
private fun RunningStep(uiState: SalesImportWizardUiState) {
    val job = uiState.job
    Column(modifier = Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(
            "Importing... ${job?.processedGroups ?: 0} / ${job?.totalGroups ?: 0} orders",
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun SummaryStep(uiState: SalesImportWizardUiState, viewModel: SalesImportWizardViewModel) {
    val job = uiState.job ?: return
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = "Import ${job.status.lowercase()}: ${job.importedCount} imported, " +
                "${job.skippedUnmatchedCount} skipped (unmatched), ${job.skippedAlreadyImportedCount} already imported.",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(SalesImportWizardScreenTags.SUMMARY_TEXT)
        )

        val reconciliation = uiState.reconciliation
        if (reconciliation == null) {
            OutlinedButton(
                onClick = viewModel::viewReconciliation,
                enabled = !uiState.isLoading,
                modifier = Modifier.padding(top = 12.dp).testTag(SalesImportWizardScreenTags.VIEW_RECONCILIATION_BUTTON)
            ) {
                Text("View Reconciliation Report")
            }
        } else {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    "Reconciliation: ${reconciliation.totalGroups} order(s) processed, " +
                        "${reconciliation.importedCount} imported. Sample record mappings:",
                    style = MaterialTheme.typography.bodyMedium
                )
                reconciliation.sampleMappings.forEach { sample ->
                    Text(
                        "${sample.orderReference} -> order ${sample.orderId} (${sample.itemCount} item(s), ${sample.total})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp).testTag(SalesImportWizardScreenTags.RECONCILIATION_ROW_PREFIX + sample.orderReference)
                    )
                }
            }
        }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            if (!job.rolledBack) {
                OutlinedButton(
                    onClick = viewModel::rollback,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.testTag(SalesImportWizardScreenTags.ROLLBACK_BUTTON)
                ) {
                    Text("Undo this import")
                }
            } else {
                Text("This import has been rolled back.", modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = viewModel::startOver,
                modifier = Modifier.padding(start = 12.dp).testTag(SalesImportWizardScreenTags.START_OVER_BUTTON)
            ) {
                Text("Import Another File")
            }
        }
    }
}
