package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.finance.CreateScheduleOutcome
import com.beettechnologies.posly.finance.CreateScheduleRequest
import com.beettechnologies.posly.finance.DeleteScheduleOutcome
import com.beettechnologies.posly.finance.FinanceReportApi
import com.beettechnologies.posly.finance.ListSchedulesOutcome
import com.beettechnologies.posly.finance.RunScheduleOutcome
import com.beettechnologies.posly.finance.ScheduledReportResponse
import com.beettechnologies.posly.finance.ScheduledReportRunResponse
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FinanceReportsUiState(
    val isLoadingStores: Boolean = true,
    val stores: List<StoreResponse> = emptyList(),
    val selectedStoreId: String? = null,
    val isLoadingSchedules: Boolean = false,
    val schedules: List<ScheduledReportResponse> = emptyList(),
    val lastRun: Map<String, ScheduledReportRunResponse> = emptyMap(),
    val runningScheduleId: String? = null,
    val typeInput: String = "SALES",
    val formatInput: String = "CSV",
    val timezoneInput: String = "UTC",
    val frequencyInput: String = "DAILY",
    val recipientsInput: String = "",
    val isCreating: Boolean = false,
    val errorMessage: String? = null
) {
    val selectedStoreName: String? get() = stores.find { it.id == selectedStoreId }?.name
    val canCreateSchedule: Boolean get() = selectedStoreId != null && recipientsInput.isNotBlank() && !isCreating
}

/**
 * Admin screen for ticket 36: create/list/delete recurring finance-report schedules for a store,
 * and trigger an ad-hoc "run now" delivery. Mirrors [com.beettechnologies.posly.pos.ManagerDashboardViewModel]'s
 * store-loading pattern; on-demand single-report generation/download is intentionally out of scope
 * here since the acceptance criteria only require scheduled email delivery, not a local download.
 */
class FinanceReportsViewModel(
    private val financeReportApi: FinanceReportApi,
    private val storeApi: StoreApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(FinanceReportsUiState())
    val uiState: StateFlow<FinanceReportsUiState> = _uiState.asStateFlow()

    fun loadStores() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingStores = true, errorMessage = null)
            when (val result = storeApi.listStores()) {
                is StoreListResult.Success -> {
                    val selected = _uiState.value.selectedStoreId ?: result.stores.firstOrNull()?.id
                    _uiState.value = _uiState.value.copy(isLoadingStores = false, stores = result.stores, selectedStoreId = selected)
                    if (selected != null) refreshSchedules()
                }
                StoreListResult.Forbidden -> _uiState.value =
                    _uiState.value.copy(isLoadingStores = false, errorMessage = "You don't have permission to view stores")
                is StoreListResult.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoadingStores = false, errorMessage = result.message)
            }
        }
    }

    fun selectStore(storeId: String) {
        _uiState.value = _uiState.value.copy(selectedStoreId = storeId)
        refreshSchedules()
    }

    fun refreshSchedules() {
        val storeId = _uiState.value.selectedStoreId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingSchedules = true, errorMessage = null)
            when (val result = financeReportApi.listSchedules(storeId)) {
                is ListSchedulesOutcome.Success -> _uiState.value = _uiState.value.copy(isLoadingSchedules = false, schedules = result.schedules)
                ListSchedulesOutcome.Forbidden -> _uiState.value =
                    _uiState.value.copy(isLoadingSchedules = false, errorMessage = "You don't have permission to view schedules")
                is ListSchedulesOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoadingSchedules = false, errorMessage = result.message)
            }
        }
    }

    fun updateTypeInput(value: String) {
        _uiState.value = _uiState.value.copy(typeInput = value)
    }

    fun updateFormatInput(value: String) {
        _uiState.value = _uiState.value.copy(formatInput = value)
    }

    fun updateTimezoneInput(value: String) {
        _uiState.value = _uiState.value.copy(timezoneInput = value)
    }

    fun updateFrequencyInput(value: String) {
        _uiState.value = _uiState.value.copy(frequencyInput = value)
    }

    fun updateRecipientsInput(value: String) {
        _uiState.value = _uiState.value.copy(recipientsInput = value)
    }

    fun createSchedule() {
        val state = _uiState.value
        val storeId = state.selectedStoreId ?: return
        if (!state.canCreateSchedule) return
        val recipients = state.recipientsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true, errorMessage = null)
            val request = CreateScheduleRequest(
                storeId = storeId,
                type = state.typeInput,
                format = state.formatInput,
                timezone = state.timezoneInput,
                frequency = state.frequencyInput,
                recipients = recipients
            )
            when (val result = financeReportApi.createSchedule(request)) {
                is CreateScheduleOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(isCreating = false, recipientsInput = "")
                    refreshSchedules()
                }
                CreateScheduleOutcome.Forbidden -> _uiState.value =
                    _uiState.value.copy(isCreating = false, errorMessage = "You don't have permission to create schedules")
                is CreateScheduleOutcome.Rejected -> _uiState.value = _uiState.value.copy(isCreating = false, errorMessage = result.message)
                is CreateScheduleOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isCreating = false, errorMessage = result.message)
            }
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            when (val result = financeReportApi.deleteSchedule(id)) {
                DeleteScheduleOutcome.Success -> refreshSchedules()
                DeleteScheduleOutcome.Forbidden -> _uiState.value = _uiState.value.copy(errorMessage = "You don't have permission to delete schedules")
                DeleteScheduleOutcome.NotFound -> refreshSchedules()
                is DeleteScheduleOutcome.NetworkError -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }
        }
    }

    fun runScheduleNow(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(runningScheduleId = id, errorMessage = null)
            when (val result = financeReportApi.runScheduleNow(id)) {
                is RunScheduleOutcome.Success -> _uiState.value = _uiState.value.copy(
                    runningScheduleId = null,
                    lastRun = _uiState.value.lastRun + (id to result.run)
                )
                RunScheduleOutcome.Forbidden -> _uiState.value =
                    _uiState.value.copy(runningScheduleId = null, errorMessage = "You don't have permission to run this schedule")
                RunScheduleOutcome.NotFound -> _uiState.value =
                    _uiState.value.copy(runningScheduleId = null, errorMessage = "Schedule not found")
                is RunScheduleOutcome.NetworkError -> _uiState.value = _uiState.value.copy(runningScheduleId = null, errorMessage = result.message)
            }
        }
    }
}
