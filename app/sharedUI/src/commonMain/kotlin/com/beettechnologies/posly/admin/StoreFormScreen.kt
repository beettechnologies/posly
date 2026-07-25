package com.beettechnologies.posly.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import org.koin.compose.viewmodel.koinViewModel

object StoreFormScreenTags {
    const val NAME_FIELD = "store_form_name_field"
    const val LINE1_FIELD = "store_form_line1_field"
    const val CITY_FIELD = "store_form_city_field"
    const val POSTAL_CODE_FIELD = "store_form_postal_code_field"
    const val COUNTRY_FIELD = "store_form_country_field"
    const val TIMEZONE_FIELD = "store_form_timezone_field"
    const val CURRENCY_FIELD = "store_form_currency_field"
    const val TAX_PROFILE_BUTTON = "store_form_tax_profile_button"
    const val SUBMIT_BUTTON = "store_form_submit_button"
    const val ERROR_TEXT = "store_form_error_text"
}

@Composable
fun StoreFormScreen(
    storeId: String?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: StoreFormViewModel = koinViewModel()
) {
    LaunchedEffect(storeId) { viewModel.initialize(storeId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    var taxProfileMenuExpanded by remember { mutableStateOf(false) }
    val selectedTaxProfile = uiState.taxProfiles.find { it.id == uiState.taxProfileId }

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
                if (storeId == null) "Add Store" else "Edit Store",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        OutlinedTextField(
            value = uiState.name,
            onValueChange = viewModel::onNameChange,
            label = { Text("Store name") },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(StoreFormScreenTags.NAME_FIELD)
        )
        OutlinedTextField(
            value = uiState.line1,
            onValueChange = viewModel::onLine1Change,
            label = { Text("Address line 1") },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(StoreFormScreenTags.LINE1_FIELD)
        )
        OutlinedTextField(
            value = uiState.city,
            onValueChange = viewModel::onCityChange,
            label = { Text("City") },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(StoreFormScreenTags.CITY_FIELD)
        )
        OutlinedTextField(
            value = uiState.postalCode,
            onValueChange = viewModel::onPostalCodeChange,
            label = { Text("Postal code") },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(StoreFormScreenTags.POSTAL_CODE_FIELD)
        )
        OutlinedTextField(
            value = uiState.country,
            onValueChange = viewModel::onCountryChange,
            label = { Text("Country") },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(StoreFormScreenTags.COUNTRY_FIELD)
        )
        OutlinedTextField(
            value = uiState.timezone,
            onValueChange = viewModel::onTimezoneChange,
            label = { Text("Timezone (e.g. America/New_York)") },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(StoreFormScreenTags.TIMEZONE_FIELD)
        )
        OutlinedTextField(
            value = uiState.currency,
            onValueChange = viewModel::onCurrencyChange,
            label = { Text("Currency (e.g. USD)") },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(StoreFormScreenTags.CURRENCY_FIELD)
        )

        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Tax profile", style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(
                    onClick = { taxProfileMenuExpanded = true },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth().testTag(StoreFormScreenTags.TAX_PROFILE_BUTTON)
                ) {
                    Text(selectedTaxProfile?.name ?: "None")
                }
                DropdownMenu(
                    expanded = taxProfileMenuExpanded,
                    onDismissRequest = { taxProfileMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            viewModel.onTaxProfileSelected(null)
                            taxProfileMenuExpanded = false
                        }
                    )
                    uiState.taxProfiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            onClick = {
                                viewModel.onTaxProfileSelected(profile.id)
                                taxProfileMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(StoreFormScreenTags.ERROR_TEXT)
            )
        }

        Button(
            onClick = viewModel::submit,
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag(StoreFormScreenTags.SUBMIT_BUTTON)
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Save")
            }
        }
    }
}
