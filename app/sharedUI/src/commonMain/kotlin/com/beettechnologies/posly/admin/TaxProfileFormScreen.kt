package com.beettechnologies.posly.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

object TaxProfileFormScreenTags {
    const val NAME_FIELD = "tax_profile_form_name_field"
    const val SUBMIT_BUTTON = "tax_profile_form_submit_button"
    const val ADD_RATE_BUTTON = "tax_profile_form_add_rate_button"
    const val ERROR_TEXT = "tax_profile_form_error_text"
    fun rateNameField(index: Int) = "tax_profile_form_rate_name_$index"
    fun ratePercentField(index: Int) = "tax_profile_form_rate_percent_$index"
    fun removeRateButton(index: Int) = "tax_profile_form_remove_rate_$index"
}

@Composable
fun TaxProfileFormScreen(
    profileId: String?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: TaxProfileFormViewModel = koinViewModel()
) {
    LaunchedEffect(profileId) { viewModel.initialize(profileId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

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
            Text(
                if (profileId == null) "Add Tax Profile" else "Edit Tax Profile",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        OutlinedTextField(
            value = uiState.name,
            onValueChange = viewModel::onNameChange,
            label = { Text("Profile name") },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(TaxProfileFormScreenTags.NAME_FIELD)
        )

        Text("Rates", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))

        uiState.rates.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = row.name,
                    onValueChange = { viewModel.onRateNameChange(index, it) },
                    label = { Text("Name") },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.weight(2f).testTag(TaxProfileFormScreenTags.rateNameField(index))
                )
                OutlinedTextField(
                    value = row.ratePercent,
                    onValueChange = { viewModel.onRatePercentChange(index, it) },
                    label = { Text("Rate %") },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.weight(1f).testTag(TaxProfileFormScreenTags.ratePercentField(index))
                )
                TextButton(
                    onClick = { viewModel.removeRateRow(index) },
                    enabled = !uiState.isSaving && uiState.rates.size > 1,
                    modifier = Modifier.testTag(TaxProfileFormScreenTags.removeRateButton(index))
                ) {
                    Text("Remove")
                }
            }
        }

        TextButton(
            onClick = viewModel::addRateRow,
            enabled = !uiState.isSaving,
            modifier = Modifier.padding(top = 8.dp).testTag(TaxProfileFormScreenTags.ADD_RATE_BUTTON)
        ) {
            Text("+ Add rate")
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(TaxProfileFormScreenTags.ERROR_TEXT)
            )
        }

        Button(
            onClick = viewModel::submit,
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag(TaxProfileFormScreenTags.SUBMIT_BUTTON)
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Save")
            }
        }
    }
}
