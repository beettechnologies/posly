package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.users.SsoConfigurationResult
import com.beettechnologies.posly.users.SsoConfigureOutcome
import com.beettechnologies.posly.users.SsoRoleMappingDto
import com.beettechnologies.posly.users.UserApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SsoRoleMappingUi(val externalGroup: String = "", val role: String = ASSIGNABLE_ROLES.first())

data class SsoConfigUiState(
    val providerName: String = "",
    val enabled: Boolean = true,
    val roleMappings: List<SsoRoleMappingUi> = emptyList(),
    val defaultRoles: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class SsoConfigViewModel(private val userApi: UserApi) : ViewModel() {

    private val _uiState = MutableStateFlow(SsoConfigUiState())
    val uiState: StateFlow<SsoConfigUiState> = _uiState.asStateFlow()

    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = userApi.getSsoConfiguration()) {
                is SsoConfigurationResult.Success -> {
                    val config = result.configuration
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        providerName = config.providerName,
                        enabled = config.enabled,
                        roleMappings = config.roleMappings.map { SsoRoleMappingUi(it.externalGroup, it.role) },
                        defaultRoles = config.defaultRoles.toSet()
                    )
                }
                SsoConfigurationResult.NotConfigured -> _uiState.value = _uiState.value.copy(isLoading = false)
                SsoConfigurationResult.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to view SSO configuration"
                )
                is SsoConfigurationResult.NetworkError -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun onProviderNameChange(value: String) {
        _uiState.value = _uiState.value.copy(providerName = value, errorMessage = null)
    }

    fun toggleEnabled() {
        _uiState.value = _uiState.value.copy(enabled = !_uiState.value.enabled)
    }

    fun toggleDefaultRole(role: String) {
        val current = _uiState.value.defaultRoles
        _uiState.value = _uiState.value.copy(defaultRoles = if (role in current) current - role else current + role)
    }

    fun addMapping() {
        _uiState.value = _uiState.value.copy(roleMappings = _uiState.value.roleMappings + SsoRoleMappingUi())
    }

    fun removeMapping(index: Int) {
        _uiState.value = _uiState.value.copy(roleMappings = _uiState.value.roleMappings.filterIndexed { i, _ -> i != index })
    }

    fun updateMappingGroup(index: Int, externalGroup: String) {
        _uiState.value = _uiState.value.copy(
            roleMappings = _uiState.value.roleMappings.mapIndexed { i, m -> if (i == index) m.copy(externalGroup = externalGroup) else m }
        )
    }

    fun updateMappingRole(index: Int, role: String) {
        _uiState.value = _uiState.value.copy(
            roleMappings = _uiState.value.roleMappings.mapIndexed { i, m -> if (i == index) m.copy(role = role) else m }
        )
    }

    fun save() {
        val state = _uiState.value
        if (state.providerName.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Provider name is required")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null, infoMessage = null)
            val result = userApi.configureSso(
                providerName = state.providerName,
                roleMappings = state.roleMappings
                    .filter { it.externalGroup.isNotBlank() }
                    .map { SsoRoleMappingDto(it.externalGroup, it.role) },
                defaultRoles = state.defaultRoles.toList(),
                enabled = state.enabled
            )
            when (result) {
                is SsoConfigureOutcome.Success -> _uiState.value = _uiState.value.copy(isSaving = false, infoMessage = "SSO configuration saved")
                SsoConfigureOutcome.Forbidden -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "You don't have permission to configure SSO"
                )
                is SsoConfigureOutcome.Rejected -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
                is SsoConfigureOutcome.NetworkError -> _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = result.message)
            }
        }
    }
}
