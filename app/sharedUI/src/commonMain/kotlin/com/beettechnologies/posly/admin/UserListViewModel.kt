package com.beettechnologies.posly.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beettechnologies.posly.users.UserApi
import com.beettechnologies.posly.users.UserListResult
import com.beettechnologies.posly.users.UserResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UserListUiState(
    val users: List<UserResponse> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class UserListViewModel(private val userApi: UserApi) : ViewModel() {

    private val _uiState = MutableStateFlow(UserListUiState())
    val uiState: StateFlow<UserListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = userApi.listUsers()) {
                is UserListResult.Success -> _uiState.value = UserListUiState(users = result.users)
                UserListResult.Forbidden -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "You don't have permission to view users"
                )
                is UserListResult.NetworkError -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.message
                )
            }
        }
    }
}
