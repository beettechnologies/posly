package com.beettechnologies.posly.pos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import org.koin.compose.viewmodel.koinViewModel

object ShiftScreenTags {
    const val LOADING_INDICATOR = "shift_loading_indicator"
    const val ERROR_TEXT = "shift_error_text"
    const val STORE_BUTTON = "shift_store_button"
    const val OPENING_FLOAT_FIELD = "shift_opening_float_field"
    const val OPEN_SHIFT_BUTTON = "shift_open_button"
    const val EXPECTED_CASH_TEXT = "shift_expected_cash_text"
    const val REFRESH_EXPECTED_CASH_BUTTON = "shift_refresh_expected_cash_button"
    const val CLOSING_COUNT_FIELD = "shift_closing_count_field"
    const val NOTE_FIELD = "shift_note_field"
    const val OVERRIDE_REQUIRED_TEXT = "shift_override_required_text"
    const val CLOSE_SHIFT_BUTTON = "shift_close_button"
    const val SUMMARY_TEXT = "shift_summary_text"
    const val START_NEW_SHIFT_BUTTON = "shift_start_new_button"
}

@Composable
fun ShiftScreen(
    onBack: () -> Unit,
    viewModel: ShiftViewModel = koinViewModel()
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
            Text("Shift", style = MaterialTheme.typography.headlineSmall)
        }

        if (uiState.isLoadingStores) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp).testTag(ShiftScreenTags.LOADING_INDICATOR))
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp).testTag(ShiftScreenTags.ERROR_TEXT)
            )
        }

        val shift = uiState.shift
        when {
            shift == null -> OpenShiftForm(uiState, viewModel, storeMenuExpanded, onExpandStoreMenu = { storeMenuExpanded = it })
            shift.status == "OPEN" -> CloseShiftForm(uiState, viewModel)
            else -> ClosedShiftSummary(uiState, viewModel)
        }
    }
}

@Composable
private fun OpenShiftForm(
    uiState: ShiftUiState,
    viewModel: ShiftViewModel,
    storeMenuExpanded: Boolean,
    onExpandStoreMenu: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Store", style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { onExpandStoreMenu(true) },
                modifier = Modifier.fillMaxWidth().testTag(ShiftScreenTags.STORE_BUTTON)
            ) {
                Text(uiState.selectedStoreName ?: "Select a store")
            }
            DropdownMenu(expanded = storeMenuExpanded, onDismissRequest = { onExpandStoreMenu(false) }) {
                uiState.stores.forEach { store ->
                    DropdownMenuItem(
                        text = { Text(store.name) },
                        onClick = {
                            viewModel.selectStore(store.id)
                            onExpandStoreMenu(false)
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = uiState.openingFloatInput,
            onValueChange = viewModel::updateOpeningFloatInput,
            label = { Text("Opening float") },
            enabled = !uiState.isBusy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(ShiftScreenTags.OPENING_FLOAT_FIELD)
        )

        Button(
            onClick = viewModel::openShift,
            enabled = uiState.canOpenShift,
            modifier = Modifier.padding(top = 12.dp).testTag(ShiftScreenTags.OPEN_SHIFT_BUTTON)
        ) {
            Text("Open shift")
        }
    }
}

@Composable
private fun CloseShiftForm(uiState: ShiftUiState, viewModel: ShiftViewModel) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Opening float: $${uiState.shift?.openingFloat}")

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(
                text = "Expected cash so far: $${uiState.expectedCashPreview ?: "-"}",
                modifier = Modifier.testTag(ShiftScreenTags.EXPECTED_CASH_TEXT)
            )
            TextButton(
                onClick = viewModel::refreshExpectedCash,
                modifier = Modifier.padding(start = 8.dp).testTag(ShiftScreenTags.REFRESH_EXPECTED_CASH_BUTTON)
            ) {
                Text("Refresh")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        OutlinedTextField(
            value = uiState.closingCountInput,
            onValueChange = viewModel::updateClosingCountInput,
            label = { Text("Closing cash count") },
            enabled = !uiState.isBusy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(ShiftScreenTags.CLOSING_COUNT_FIELD)
        )

        OutlinedTextField(
            value = uiState.noteInput,
            onValueChange = viewModel::updateNoteInput,
            label = { Text("Note (optional, required for a large variance)") },
            enabled = !uiState.isBusy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(ShiftScreenTags.NOTE_FIELD)
        )

        val requirement = uiState.requiresOverrideOrNote
        if (requirement != null) {
            Text(
                text = "Variance of $${requirement.variance} exceeds the $${requirement.threshold} threshold - " +
                    "add a note above, or have a manager close this shift.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp).testTag(ShiftScreenTags.OVERRIDE_REQUIRED_TEXT)
            )
        }

        Button(
            onClick = viewModel::closeShift,
            enabled = uiState.canCloseShift,
            modifier = Modifier.padding(top = 12.dp).testTag(ShiftScreenTags.CLOSE_SHIFT_BUTTON)
        ) {
            Text("Close shift")
        }
    }
}

@Composable
private fun ClosedShiftSummary(uiState: ShiftUiState, viewModel: ShiftViewModel) {
    val shift = uiState.shift ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = shiftSummaryText(shift, uiState.selectedStoreName),
                modifier = Modifier.padding(16.dp).testTag(ShiftScreenTags.SUMMARY_TEXT)
            )
        }
        Button(
            onClick = viewModel::startNewShift,
            modifier = Modifier.padding(top = 12.dp).testTag(ShiftScreenTags.START_NEW_SHIFT_BUTTON)
        ) {
            Text("Start new shift")
        }
    }
}
