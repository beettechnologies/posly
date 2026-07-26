package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import com.beettechnologies.posly.users.InviteUserOutcome
import com.beettechnologies.posly.users.UserApi
import com.beettechnologies.posly.users.UserResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

val ASSIGNABLE_ROLES = listOf("ADMIN", "MANAGER", "CASHIER", "MERCHANDISER")

data class UserFormUiState(
    val id: String? = null,
    val username: String = "",
    val email: String = "",
    val selectedRoles: Set<String> = setOf("CASHIER"),
    val stores: List<StoreResponse> = emptyList(),
    val selectedStoreIds: Set<String> = emptySet(),
    val status: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val invited: Boolean = false,
    /** Shown after a successful invite so the demo/test flow can redeem it without a real inbox. */
    val inviteToken: String? = null
) {
    val isEditing: Boolean get() = id != null
}

class UserFormViewModel(
    private val userApi: UserApi,
    private val storeApi: StoreApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserFormUiState())
    val uiState: StateFlow<UserFormUiState> = _uiState.asStateFlow()

    private var initialized = false

    fun initialize(userId: String?) {
        if (initialized) return
        initialized = true
        _uiState.value = _uiState.value.copy(id = userId)
        loadStores()
        if (userId != null) loadUser(userId)
    }

    private fun loadStores() {
        viewModelScope.launch {
            val result = storeApi.listStores()
            if (result is StoreListResult.Success) {
                _uiState.value = _uiState.value.copy(stores = result.stores)
            }
        }
    }

    private fun loadUser(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = userApi.getUser(id)) {
                is UserResult.Success -> {
                    val user = result.user
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        username = user.username,
                        email = user.email.orEmpty(),
                        selectedRoles = user.roles.toSet(),
                        selectedStoreIds = user.storeIds.toSet(),
                        status = user.status
                    )
                }
                else -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to load user")
            }
        }
    }

    fun onUsernameChange(value: String) {
        _uiState.value = _uiState.value.copy(username = value, errorMessage = null)
    }

    fun onEmailChange(value: String) {
        _uiState.value = _uiState.value.copy(email = value, errorMessage = null)
    }

    fun toggleRole(role: String) {
        val current = _uiState.value.selectedRoles
        _uiState.value = _uiState.value.copy(
            selectedRoles = if (role in current) current - role else current + role,
            errorMessage = null
        )
    }

    fun toggleStore(storeId: String) {
        val current = _uiState.value.selectedStoreIds
        _uiState.value = _uiState.value.copy(
            selectedStoreIds = if (storeId in current) current - storeId else current + storeId
        )
    }

    /** Invite flow only (id == null): creates the account and sends the accept-invite email. */
    fun submitInvite() {
        val state = _uiState.value
        if (state.username.isBlank() || state.email.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Username and email are required")
            return
        }
        if (state.selectedRoles.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "Select at least one role")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            when (
                val result = userApi.inviteUser(
                    state.username,
                    state.email,
                    state.selectedRoles.toList(),
                    state.selectedStoreIds.toList()
                )
            ) {
                is InviteUserOutcome.Success -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    invited = true,
                    inviteToken = result.inviteToken,
                    infoMessage = if (result.emailDelivered) "Invite email sent" else "Invite created, but the email could not be delivered"
                )
                InviteUserOutcome.UsernameTaken -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "That username is already taken"
                )
                InviteUserOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "You don't have permission to invite users"
                )
                is InviteUserOutcome.Rejected -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
                is InviteUserOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
            }
        }
    }

    /** Edit flow only (id != null): persists the currently-checked roles. */
    fun saveRoles() {
        val id = _uiState.value.id ?: return
        if (_uiState.value.selectedRoles.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Select at least one role")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null, infoMessage = null)
            applyUserResult(userApi.updateRoles(id, _uiState.value.selectedRoles.toList()), "Roles updated")
        }
    }

    /** Edit flow only (id != null): persists the currently-checked store access grants. */
    fun saveStoreAccess() {
        val id = _uiState.value.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null, infoMessage = null)
            applyUserResult(userApi.updateStoreAccess(id, _uiState.value.selectedStoreIds.toList()), "Store access updated")
        }
    }

    /** Edit flow only (id != null): toggles between ACTIVE and DISABLED. */
    fun toggleStatus() {
        val id = _uiState.value.id ?: return
        val newStatus = if (_uiState.value.status == "DISABLED") "ACTIVE" else "DISABLED"
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null, infoMessage = null)
            applyUserResult(userApi.updateStatus(id, newStatus), "Status updated")
        }
    }

    private fun applyUserResult(result: UserResult, successMessage: String) {
        when (result) {
            is UserResult.Success -> _uiState.value = _uiState.value.copy(
                isSaving = false,
                selectedRoles = result.user.roles.toSet(),
                selectedStoreIds = result.user.storeIds.toSet(),
                status = result.user.status,
                infoMessage = successMessage
            )
            UserResult.NotFound -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = "User not found")
            UserResult.Forbidden -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = "You don't have permission to do this")
            is UserResult.Rejected -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
            is UserResult.NetworkError -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
        }
    }
}
