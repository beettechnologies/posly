package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.catalog.PickedFile
import com.beettechnologies.posly.migration.REQUIRED_SALES_IMPORT_FIELDS
import com.beettechnologies.posly.migration.RollbackSalesImportOutcome
import com.beettechnologies.posly.migration.SalesDryRunOutcome
import com.beettechnologies.posly.migration.SalesDryRunResponse
import com.beettechnologies.posly.migration.SalesImportApi
import com.beettechnologies.posly.migration.SalesImportJobOutcome
import com.beettechnologies.posly.migration.SalesImportJobResponse
import com.beettechnologies.posly.migration.SalesReconciliationOutcome
import com.beettechnologies.posly.migration.SalesReconciliationReportResponse
import com.beettechnologies.posly.migration.StartSalesImportOutcome
import com.beettechnologies.posly.migration.UploadSalesCsvOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SalesImportWizardStep { PICK_FILE, MAP_COLUMNS, DRY_RUN, RUNNING, SUMMARY }

data class SalesImportWizardUiState(
    val step: SalesImportWizardStep = SalesImportWizardStep.PICK_FILE,
    val fileName: String? = null,
    val fileId: String? = null,
    val headers: List<String> = emptyList(),
    val previewRows: List<List<String>> = emptyList(),
    /** Order field name -> chosen CSV header. */
    val mapping: Map<String, String> = emptyMap(),
    val dryRunReport: SalesDryRunResponse? = null,
    val job: SalesImportJobResponse? = null,
    val reconciliation: SalesReconciliationReportResponse? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val canRunDryRun: Boolean get() = REQUIRED_SALES_IMPORT_FIELDS.all { mapping[it]?.isNotBlank() == true }
}

class SalesImportWizardViewModel(private val importApi: SalesImportApi) : ViewModel() {

    private val _uiState = MutableStateFlow(SalesImportWizardUiState())
    val uiState: StateFlow<SalesImportWizardUiState> = _uiState.asStateFlow()

    fun onFilePicked(file: PickedFile) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = importApi.uploadCsv(file.name, file.bytes)) {
                is UploadSalesCsvOutcome.Success -> {
                    val guessedMapping = guessMapping(result.response.headers)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        step = SalesImportWizardStep.MAP_COLUMNS,
                        fileName = file.name,
                        fileId = result.response.fileId,
                        headers = result.response.headers,
                        previewRows = result.response.previewRows,
                        mapping = guessedMapping
                    )
                }
                UploadSalesCsvOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to import sales"
                )
                is UploadSalesCsvOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is UploadSalesCsvOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    /** Best-effort case-insensitive header auto-mapping, so the user usually only has to confirm rather than pick everything by hand. */
    private fun guessMapping(headers: List<String>): Map<String, String> {
        val aliases = mapOf(
            "ORDER_REFERENCE" to listOf("orderreference", "order_reference", "orderref", "order ref", "reference", "receiptnumber", "receipt"),
            "STORE_ID" to listOf("storeid", "store_id", "store"),
            "SKU" to listOf("sku"),
            "QUANTITY" to listOf("quantity", "qty"),
            "UNIT_PRICE" to listOf("unitprice", "unit_price", "price"),
            "SOLD_AT" to listOf("soldat", "sold_at", "date", "saledate", "sale_date", "timestamp"),
            "PAYMENT_METHOD" to listOf("paymentmethod", "payment_method", "method", "tender"),
            "TOTAL_AMOUNT" to listOf("total", "totalamount", "total_amount", "amount"),
            "SUBTOTAL" to listOf("subtotal", "sub_total"),
            "TAX_AMOUNT" to listOf("tax", "taxamount", "tax_amount"),
            "PAYMENT_REFERENCE" to listOf("paymentreference", "payment_reference", "txnid", "transactionid"),
            "SOLD_BY" to listOf("soldby", "sold_by", "cashier", "employee")
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
                is SalesDryRunOutcome.Success -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    step = SalesImportWizardStep.DRY_RUN,
                    dryRunReport = result.response
                )
                SalesDryRunOutcome.FileNotFound -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Import file no longer available")
                is SalesDryRunOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is SalesDryRunOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun backToMapping() {
        _uiState.value = _uiState.value.copy(step = SalesImportWizardStep.MAP_COLUMNS, dryRunReport = null)
    }

    fun confirmImport() {
        val state = _uiState.value
        val fileId = state.fileId ?: return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            when (val result = importApi.startImport(fileId, state.mapping)) {
                is StartSalesImportOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, step = SalesImportWizardStep.RUNNING, job = result.job)
                    pollJob(result.job.id)
                }
                StartSalesImportOutcome.FileNotFound -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Import file no longer available")
                is StartSalesImportOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is StartSalesImportOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    private fun pollJob(jobId: String) {
        viewModelScope.launch {
            while (true) {
                when (val result = importApi.getJob(jobId)) {
                    is SalesImportJobOutcome.Success -> {
                        _uiState.value = _uiState.value.copy(job = result.job)
                        if (result.job.status == "COMPLETED" || result.job.status == "FAILED") {
                            _uiState.value = _uiState.value.copy(step = SalesImportWizardStep.SUMMARY)
                            return@launch
                        }
                    }
                    SalesImportJobOutcome.NotFound -> return@launch
                    is SalesImportJobOutcome.NetworkError -> return@launch
                }
                delay(300)
            }
        }
    }

    fun viewReconciliation() {
        val jobId = _uiState.value.job?.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = importApi.getReconciliationReport(jobId)) {
                is SalesReconciliationOutcome.Success -> _uiState.value = _uiState.value.copy(isLoading = false, reconciliation = result.report)
                SalesReconciliationOutcome.NotFound -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Import job not found")
                is SalesReconciliationOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is SalesReconciliationOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun rollback() {
        val jobId = _uiState.value.job?.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = importApi.rollback(jobId)) {
                is RollbackSalesImportOutcome.Success -> _uiState.value = _uiState.value.copy(isLoading = false, job = result.job)
                RollbackSalesImportOutcome.NotFound -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Import job not found")
                RollbackSalesImportOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to roll back an import"
                )
                is RollbackSalesImportOutcome.Rejected -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                is RollbackSalesImportOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun startOver() {
        _uiState.value = SalesImportWizardUiState()
    }
}
