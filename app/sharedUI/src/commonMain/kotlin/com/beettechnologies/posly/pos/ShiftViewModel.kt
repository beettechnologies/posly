package com.beettechnologies.posly.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.shifts.CloseShiftOutcome
import com.beettechnologies.posly.shifts.ExpectedCashOutcome
import com.beettechnologies.posly.shifts.OpenShiftOutcome
import com.beettechnologies.posly.shifts.ShiftApi
import com.beettechnologies.posly.shifts.ShiftResponse
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ShiftUiState(
    val isLoadingStores: Boolean = true,
    val stores: List<StoreResponse> = emptyList(),
    val selectedStoreId: String? = null,
    val openingFloatInput: String = "",
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val requiresOverrideOrNote: OverrideRequirement? = null,
    val shift: ShiftResponse? = null,
    val expectedCashPreview: Double? = null,
    val closingCountInput: String = "",
    val noteInput: String = ""
) {
    data class OverrideRequirement(val variance: Double, val threshold: Double)

    val selectedStoreName: String? get() = stores.find { it.id == selectedStoreId }?.name
    val openingFloatValue: Double? get() = openingFloatInput.toDoubleOrNull()?.takeIf { it >= 0.0 }
    val closingCountValue: Double? get() = closingCountInput.toDoubleOrNull()?.takeIf { it >= 0.0 }
    val canOpenShift: Boolean get() = !isBusy && selectedStoreId != null && openingFloatValue != null
    val canCloseShift: Boolean get() = !isBusy && closingCountValue != null
    val isShiftOpen: Boolean get() = shift?.status == "OPEN"
    val isShiftClosed: Boolean get() = shift?.status == "CLOSED"
}

/**
 * Drives the standalone shift lifecycle screen: pick a store and opening float to start a shift,
 * then a closing count (+ optional note) to end it. A variance beyond the server's threshold comes
 * back as [CloseShiftOutcome.RequiresOverrideOrNote] rather than a hard error - the cashier can
 * supply a note and retry, or a manager can retry the same close with no note required (enforced
 * server-side by role, not by anything client-side).
 */
class ShiftViewModel(
    private val shiftApi: ShiftApi,
    private val storeApi: StoreApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShiftUiState())
    val uiState: StateFlow<ShiftUiState> = _uiState.asStateFlow()

    fun loadStores() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingStores = true, errorMessage = null)
            when (val result = storeApi.listStores()) {
                is StoreListResult.Success -> _uiState.value = _uiState.value.copy(
                    isLoadingStores = false,
                    stores = result.stores,
                    selectedStoreId = _uiState.value.selectedStoreId ?: result.stores.firstOrNull()?.id
                )
                StoreListResult.Forbidden -> _uiState.value =
                    _uiState.value.copy(isLoadingStores = false, errorMessage = "You don't have permission to view stores")
                is StoreListResult.NetworkError -> _uiState.value =
                    _uiState.value.copy(isLoadingStores = false, errorMessage = result.message)
            }
        }
    }

    fun selectStore(storeId: String) {
        _uiState.value = _uiState.value.copy(selectedStoreId = storeId)
    }

    fun updateOpeningFloatInput(value: String) {
        _uiState.value = _uiState.value.copy(openingFloatInput = value)
    }

    fun openShift() {
        val state = _uiState.value
        val storeId = state.selectedStoreId ?: return
        val openingFloat = state.openingFloatValue ?: return
        if (!state.canOpenShift) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, errorMessage = null)
            when (val result = shiftApi.openShift(storeId, openingFloat)) {
                is OpenShiftOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(isBusy = false, shift = result.shift)
                    refreshExpectedCash()
                }
                OpenShiftOutcome.StoreNotFound -> _uiState.value =
                    _uiState.value.copy(isBusy = false, errorMessage = "Store not found")
                OpenShiftOutcome.ShiftAlreadyOpen -> _uiState.value =
                    _uiState.value.copy(isBusy = false, errorMessage = "You already have an open shift at this store")
                is OpenShiftOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(isBusy = false, errorMessage = result.message)
                is OpenShiftOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isBusy = false, errorMessage = result.message)
            }
        }
    }

    fun refreshExpectedCash() {
        val shiftId = _uiState.value.shift?.id ?: return
        viewModelScope.launch {
            when (val result = shiftApi.getExpectedCash(shiftId)) {
                is ExpectedCashOutcome.Success -> _uiState.value = _uiState.value.copy(expectedCashPreview = result.expectedCash)
                ExpectedCashOutcome.NotFound -> Unit
                is ExpectedCashOutcome.NetworkError -> Unit
            }
        }
    }

    fun updateClosingCountInput(value: String) {
        _uiState.value = _uiState.value.copy(closingCountInput = value)
    }

    fun updateNoteInput(value: String) {
        _uiState.value = _uiState.value.copy(noteInput = value)
    }

    fun closeShift() {
        val state = _uiState.value
        val shiftId = state.shift?.id ?: return
        val closingCount = state.closingCountValue ?: return
        if (!state.canCloseShift) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, errorMessage = null, requiresOverrideOrNote = null)
            val note = state.noteInput.trim().ifEmpty { null }
            when (val result = shiftApi.closeShift(shiftId, closingCount, note)) {
                is CloseShiftOutcome.Success -> _uiState.value = _uiState.value.copy(isBusy = false, shift = result.shift)
                CloseShiftOutcome.NotFound -> _uiState.value =
                    _uiState.value.copy(isBusy = false, errorMessage = "Shift not found")
                CloseShiftOutcome.NotOpen -> _uiState.value =
                    _uiState.value.copy(isBusy = false, errorMessage = "This shift is already closed")
                is CloseShiftOutcome.RequiresOverrideOrNote -> _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    requiresOverrideOrNote = ShiftUiState.OverrideRequirement(result.variance, result.threshold)
                )
                is CloseShiftOutcome.Rejected -> _uiState.value =
                    _uiState.value.copy(isBusy = false, errorMessage = result.message)
                is CloseShiftOutcome.NetworkError -> _uiState.value =
                    _uiState.value.copy(isBusy = false, errorMessage = result.message)
            }
        }
    }

    /** Discards the closed shift's summary and returns to the open-shift form for a new shift. */
    fun startNewShift() {
        _uiState.value = ShiftUiState(
            isLoadingStores = false,
            stores = _uiState.value.stores,
            selectedStoreId = _uiState.value.selectedStoreId
        )
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

/** A plain-text, printable-style recap of a closed shift - opening/closing figures, variance, and any note. */
fun shiftSummaryText(shift: ShiftResponse, storeName: String?): String = buildString {
    appendLine("===== SHIFT SUMMARY =====")
    appendLine("Store: ${storeName ?: shift.storeId}")
    appendLine("Opened: ${shift.openedAt}")
    appendLine("Closed: ${shift.closedAt ?: "-"}")
    appendLine("-------------------------")
    appendLine("Opening float: $${shift.openingFloat}")
    appendLine("Expected cash: $${shift.expectedCash ?: "-"}")
    appendLine("Closing count: $${shift.closingCount ?: "-"}")
    appendLine("Variance: $${shift.variance ?: "-"}${shift.varianceCause?.let { " ($it)" } ?: ""}")
    if (shift.possibleReasons.isNotEmpty()) {
        appendLine("Possible reasons:")
        shift.possibleReasons.forEach { appendLine(" - $it") }
    }
    if (!shift.note.isNullOrBlank()) {
        appendLine("Note: ${shift.note}")
    }
    appendLine("=========================")
}
