package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.catalog.DryRunOutcome
import com.beettechnologies.posly.catalog.ImportApi
import com.beettechnologies.posly.catalog.ImportJobOutcome
import com.beettechnologies.posly.catalog.ImportJobResponse
import com.beettechnologies.posly.catalog.ImportRowOutcomeResponse
import com.beettechnologies.posly.catalog.PickedFile
import com.beettechnologies.posly.catalog.REQUIRED_PRODUCT_IMPORT_FIELDS
import com.beettechnologies.posly.catalog.RollbackImportOutcome
import com.beettechnologies.posly.catalog.StartImportOutcome
import com.beettechnologies.posly.catalog.UploadCsvOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ImportWizardStep { PICK_FILE, MAP_COLUMNS, DRY_RUN, RUNNING, SUMMARY }

data class ImportWizardUiState(
    val step: ImportWizardStep = ImportWizardStep.PICK_FILE,
    val fileName: String? = null,
    val fileId: String? = null,
    val headers: List<String> = emptyList(),
    val previewRows: List<List<String>> = emptyList(),
    /** Product field name -> chosen CSV header. */
    val mapping: Map<String, String> = emptyMap(),
    val dryRunOutcomes: List<ImportRowOutcomeResponse> = emptyList(),
    val job: ImportJobResponse? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val canRunDryRun: Boolean get() = REQUIRED_PRODUCT_IMPORT_FIELDS.all { mapping[it]?.isNotBlank() == true }
}

class ImportWizardViewModel(private val importApi: ImportApi) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportWizardUiState())
    val uiState: StateFlow<ImportWizardUiState> = _uiState.asStateFlow()

    fun onFilePicked(file: PickedFile) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = importApi.uploadCsv(file.name, file.bytes)) {
                is UploadCsvOutcome.Success -> {
                    val guessedMapping = guessMapping(result.response.headers)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        step = ImportWizardStep.MAP_COLUMNS,
                        fileName = file.name,
                        fileId = result.response.fileId,
                        headers = result.response.headers,
                        previewRows = result.response.previewRows,
                        mapping = guessedMapping
                    )
                }
                UploadCsvOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to import products"
                )
                is UploadCsvOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is UploadCsvOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    /** Best-effort case-insensitive header auto-mapping, so the user usually only has to confirm rather than pick everything by hand. */
    private fun guessMapping(headers: List<String>): Map<String, String> {
        val aliases = mapOf(
            "SKU" to listOf("sku"),
            "NAME" to listOf("name", "title", "product name"),
            "PRICE" to listOf("price", "cost"),
            "DESCRIPTION" to listOf("description", "desc"),
            "TAX_CATEGORY" to listOf("taxcategory", "tax_category", "tax category"),
            "BARCODE" to listOf("barcode", "upc", "ean"),
            "CATEGORY" to listOf("category"),
            "IN_STOCK" to listOf("instock", "in_stock", "in stock", "available")
        )
        val mapping = mutableMapOf<String, String>()
        for ((field, candidates) in aliases) {
            val match = headers.find { it.trim().lowercase() in candidates }
            if (match != null) mapping[field] = match
        }
        return mapping
    }

    fun setMapping(field: String, header: String) {
        _uiState.value = _uiState.value.copy(mapping = _uiState.value.mapping + (field to header), errorMessage = null)
    }

    fun clearMapping(field: String) {
        _uiState.value = _uiState.value.copy(mapping = _uiState.value.mapping - field)
    }

    fun runDryRun() {
        val state = _uiState.value
        val fileId = state.fileId ?: return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            when (val result = importApi.dryRun(fileId, state.mapping)) {
                is DryRunOutcome.Success -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    step = ImportWizardStep.DRY_RUN,
                    dryRunOutcomes = result.response.outcomes
                )
                DryRunOutcome.FileNotFound -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Import file no longer available")
                is DryRunOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is DryRunOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun backToMapping() {
        _uiState.value = _uiState.value.copy(step = ImportWizardStep.MAP_COLUMNS, dryRunOutcomes = emptyList())
    }

    fun confirmImport() {
        val state = _uiState.value
        val fileId = state.fileId ?: return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            when (val result = importApi.startImport(fileId, state.mapping)) {
                is StartImportOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, step = ImportWizardStep.RUNNING, job = result.job)
                    pollJob(result.job.id)
                }
                StartImportOutcome.FileNotFound -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Import file no longer available")
                is StartImportOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is StartImportOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    private fun pollJob(jobId: String) {
        viewModelScope.launch {
            while (true) {
                when (val result = importApi.getJob(jobId)) {
                    is ImportJobOutcome.Success -> {
                        _uiState.value = _uiState.value.copy(job = result.job)
                        if (result.job.status == "COMPLETED" || result.job.status == "FAILED") {
                            _uiState.value = _uiState.value.copy(step = ImportWizardStep.SUMMARY)
                            return@launch
                        }
                    }
                    ImportJobOutcome.NotFound -> return@launch
                    is ImportJobOutcome.NetworkError -> return@launch
                }
                delay(300)
            }
        }
    }

    fun rollback() {
        val jobId = _uiState.value.job?.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = importApi.rollback(jobId)) {
                is RollbackImportOutcome.Success -> _uiState.value = _uiState.value.copy(isLoading = false, job = result.job)
                RollbackImportOutcome.NotFound -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Import job not found")
                RollbackImportOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to roll back an import"
                )
                is RollbackImportOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is RollbackImportOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun startOver() {
        _uiState.value = ImportWizardUiState()
    }
}
