package com.beettechnologies.posly.admin

import com.beettechnologies.posly.users.AcceptInviteOutcome
import com.beettechnologies.posly.users.AuditLogEntryResponse
import com.beettechnologies.posly.users.InviteUserOutcome
import com.beettechnologies.posly.users.SsoConfigureOutcome
import com.beettechnologies.posly.users.SsoConfigurationResult
import com.beettechnologies.posly.users.SsoRoleMappingDto
import com.beettechnologies.posly.users.UserApi
import com.beettechnologies.posly.users.UserListResult
import com.beettechnologies.posly.users.UserResponse
import com.beettechnologies.posly.users.UserResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun testUser(
    id: String = "user-1",
    username: String = "cashier",
    roles: List<String> = listOf("CASHIER"),
    status: String = "ACTIVE",
    storeIds: List<String> = emptyList()
) = UserResponse(
    id = id,
    username = username,
    email = "$username@example.com",
    roles = roles,
    storeIds = storeIds,
    status = status,
    mfaEnabled = false,
    roleVersion = 0,
    externalId = null
)

internal class FakeUserApi(
    private val users: MutableList<UserResponse> = mutableListOf(),
    private val listResult: UserListResult? = null,
    private val inviteOutcome: InviteUserOutcome? = null,
    private val acceptInviteOutcome: AcceptInviteOutcome? = null
) : UserApi {
    var lastInviteRequest: Pair<String, List<String>>? = null
    var lastRolesUpdate: Pair<String, List<String>>? = null
    var lastStoreAccessUpdate: Pair<String, List<String>>? = null
    var lastStatusUpdate: Pair<String, String>? = null

    override suspend fun listUsers(): UserListResult = listResult ?: UserListResult.Success(users)

    override suspend fun getUser(id: String): UserResult =
        users.find { it.id == id }?.let { UserResult.Success(it) } ?: UserResult.NotFound

    override suspend fun inviteUser(username: String, email: String, roles: List<String>, storeIds: List<String>): InviteUserOutcome {
        lastInviteRequest = username to roles
        return inviteOutcome ?: InviteUserOutcome.Success(
            testUser(id = "new-user", username = username, roles = roles, status = "INVITED", storeIds = storeIds),
            inviteToken = "fake-invite-token",
            emailDelivered = true
        )
    }

    override suspend fun acceptInvite(token: String, newPassword: String): AcceptInviteOutcome =
        acceptInviteOutcome ?: AcceptInviteOutcome.Success

    override suspend fun updateRoles(userId: String, roles: List<String>): UserResult {
        lastRolesUpdate = userId to roles
        val user = users.find { it.id == userId } ?: return UserResult.NotFound
        return UserResult.Success(user.copy(roles = roles))
    }

    override suspend fun updateStoreAccess(userId: String, storeIds: List<String>): UserResult {
        lastStoreAccessUpdate = userId to storeIds
        val user = users.find { it.id == userId } ?: return UserResult.NotFound
        return UserResult.Success(user.copy(storeIds = storeIds))
    }

    override suspend fun updateStatus(userId: String, status: String): UserResult {
        lastStatusUpdate = userId to status
        val user = users.find { it.id == userId } ?: return UserResult.NotFound
        return UserResult.Success(user.copy(status = status))
    }

    override suspend fun listAuditLog(username: String?, event: String?): List<AuditLogEntryResponse> = emptyList()

    override suspend fun configureSso(
        providerName: String,
        roleMappings: List<SsoRoleMappingDto>,
        defaultRoles: List<String>,
        enabled: Boolean
    ): SsoConfigureOutcome = error("not used in this test")

    override suspend fun getSsoConfiguration(): SsoConfigurationResult = error("not used in this test")
}

@OptIn(ExperimentalCoroutinesApi::class)
class UserListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads and displays the seeded users on init`() = runTest(dispatcher) {
        val api = FakeUserApi(mutableListOf(testUser("u1", "admin", listOf("ADMIN")), testUser("u2", "cashier", listOf("CASHIER"))))
        val viewModel = UserListViewModel(api)

        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.users.size)
        assertTrue(viewModel.uiState.value.users.any { it.username == "admin" })
    }

    @Test
    fun `forbidden surfaces a permission error`() = runTest(dispatcher) {
        val api = FakeUserApi(listResult = UserListResult.Forbidden)
        val viewModel = UserListViewModel(api)

        advanceUntilIdle()

        assertEquals("You don't have permission to view users", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `network error surfaces its message`() = runTest(dispatcher) {
        val api = FakeUserApi(listResult = UserListResult.NetworkError("connection refused"))
        val viewModel = UserListViewModel(api)

        advanceUntilIdle()

        assertEquals("connection refused", viewModel.uiState.value.errorMessage)
    }
}
