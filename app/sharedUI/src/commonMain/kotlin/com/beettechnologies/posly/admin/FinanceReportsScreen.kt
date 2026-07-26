package com.beettechnologies.posly.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beettechnologies.posly.finance.ScheduledReportResponse
import com.beettechnologies.posly.finance.ScheduledReportRunResponse
import org.koin.compose.viewmodel.koinViewModel

object FinanceReportsScreenTags {
    const val LOADING_INDICATOR = "finance_reports_loading_indicator"
    const val ERROR_TEXT = "finance_reports_error_text"
    const val STORE_BUTTON = "finance_reports_store_button"
    const val TIMEZONE_FIELD = "finance_reports_timezone_field"
    const val RECIPIENTS_FIELD = "finance_reports_recipients_field"
    const val CREATE_BUTTON = "finance_reports_create_button"
    const val TYPE_OPTION_PREFIX = "finance_reports_type_option_"
    const val FORMAT_OPTION_PREFIX = "finance_reports_format_option_"
    const val FREQUENCY_OPTION_PREFIX = "finance_reports_frequency_option_"
    const val SCHEDULE_CARD_PREFIX = "finance_reports_schedule_card_"
    const val RUN_NOW_BUTTON_PREFIX = "finance_reports_run_now_button_"
    const val DELETE_BUTTON_PREFIX = "finance_reports_delete_button_"
    const val LAST_RUN_TEXT_PREFIX = "finance_reports_last_run_text_"
    const val EMPTY_TEXT = "finance_reports_empty_text"
}

private val REPORT_TYPES = listOf("SALES", "TAX", "RECONCILIATION")
private val REPORT_FORMATS = listOf("CSV", "PDF")
private val FREQUENCIES = listOf("DAILY", "WEEKLY", "MONTHLY")

@Composable
private fun OptionRow(options: List<String>, selected: String, tagPrefix: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            OutlinedButton(
                onClick = { onSelect(option) },
                modifier = Modifier.testTag(tagPrefix + option)
            ) {
                Text(if (isSelected) "[$option]" else option)
            }
        }
    }
}

@Composable
fun FinanceReportsScreen(
    onBack: () -> Unit,
    viewModel: FinanceReportsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var storeMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadStores() }

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
            Text("Finance Reports", style = MaterialTheme.typography.headlineSmall)
        }

        if (uiState.stores.size > 1) {
            Box(modifier = Modifier.padding(top = 12.dp)) {
                OutlinedButton(
                    onClick = { storeMenuExpanded = true },
                    modifier = Modifier.testTag(FinanceReportsScreenTags.STORE_BUTTON)
                ) {
                    Text(uiState.selectedStoreName ?: "Select store")
                }
                DropdownMenu(expanded = storeMenuExpanded, onDismissRequest = { storeMenuExpanded = false }) {
                    uiState.stores.forEach { store ->
                        DropdownMenuItem(
                            text = { Text(store.name) },
                            onClick = {
                                viewModel.selectStore(store.id)
                                storeMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (uiState.isLoadingStores || uiState.isLoadingSchedules) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp).testTag(FinanceReportsScreenTags.LOADING_INDICATOR))
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(FinanceReportsScreenTags.ERROR_TEXT)
            )
        }

        Text("New Schedule", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Text("Report type", style = MaterialTheme.typography.labelLarge)
        OptionRow(REPORT_TYPES, uiState.typeInput, FinanceReportsScreenTags.TYPE_OPTION_PREFIX, viewModel::updateTypeInput)
        Text("Format", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        OptionRow(REPORT_FORMATS, uiState.formatInput, FinanceReportsScreenTags.FORMAT_OPTION_PREFIX, viewModel::updateFormatInput)
        Text("Frequency", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        OptionRow(FREQUENCIES, uiState.frequencyInput, FinanceReportsScreenTags.FREQUENCY_OPTION_PREFIX, viewModel::updateFrequencyInput)

        OutlinedTextField(
            value = uiState.timezoneInput,
            onValueChange = viewModel::updateTimezoneInput,
            label = { Text("Timezone (e.g. America/New_York)") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(FinanceReportsScreenTags.TIMEZONE_FIELD)
        )
        OutlinedTextField(
            value = uiState.recipientsInput,
            onValueChange = viewModel::updateRecipientsInput,
            label = { Text("Recipients (comma-separated emails)") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(FinanceReportsScreenTags.RECIPIENTS_FIELD)
        )
        TextButton(
            onClick = { viewModel.createSchedule() },
            enabled = uiState.canCreateSchedule,
            modifier = Modifier.padding(top = 12.dp).testTag(FinanceReportsScreenTags.CREATE_BUTTON)
        ) {
            Text("Create Schedule")
        }

        Text("Schedules", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        if (!uiState.isLoadingSchedules && uiState.schedules.isEmpty()) {
            Text("No scheduled reports for this store yet.", modifier = Modifier.testTag(FinanceReportsScreenTags.EMPTY_TEXT))
        }
        uiState.schedules.forEach { schedule ->
            ScheduleCard(schedule, uiState.runningScheduleId == schedule.id, uiState.lastRun[schedule.id], viewModel)
        }
    }
}

@Composable
private fun ScheduleCard(
    schedule: ScheduledReportResponse,
    isRunning: Boolean,
    lastRun: ScheduledReportRunResponse?,
    viewModel: FinanceReportsViewModel
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(FinanceReportsScreenTags.SCHEDULE_CARD_PREFIX + schedule.id)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("${schedule.type} (${schedule.format}) - ${schedule.frequency}", style = MaterialTheme.typography.titleSmall)
            Text("Timezone: ${schedule.timezone}")
            Text("Recipients: ${schedule.recipients.joinToString(", ")}")
            Text("Next run: ${schedule.nextRunAt}")
            if (schedule.lastRunStatus != null) {
                Text("Last run: ${schedule.lastRunStatus} at ${schedule.lastRunAt}")
            }
            if (lastRun != null) {
                Text(
                    "Run now result: ${lastRun.status}, delivered to ${lastRun.deliveredTo.size}, failed ${lastRun.failedRecipients.size}",
                    modifier = Modifier.testTag(FinanceReportsScreenTags.LAST_RUN_TEXT_PREFIX + schedule.id)
                )
            }
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { viewModel.runScheduleNow(schedule.id) },
                    enabled = !isRunning,
                    modifier = Modifier.testTag(FinanceReportsScreenTags.RUN_NOW_BUTTON_PREFIX + schedule.id)
                ) {
                    Text(if (isRunning) "Running..." else "Run Now")
                }
                TextButton(
                    onClick = { viewModel.deleteSchedule(schedule.id) },
                    modifier = Modifier.testTag(FinanceReportsScreenTags.DELETE_BUTTON_PREFIX + schedule.id)
                ) {
                    Text("Delete")
                }
            }
        }
    }
}
