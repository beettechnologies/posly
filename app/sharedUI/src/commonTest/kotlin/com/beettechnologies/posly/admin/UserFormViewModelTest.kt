package com.beettechnologies.posly.admin

import com.beettechnologies.posly.stores.AddressDto
import com.beettechnologies.posly.stores.CreateStoreRequest
import com.beettechnologies.posly.stores.DeleteStoreResult
import com.beettechnologies.posly.stores.StoreApi
import com.beettechnologies.posly.stores.StoreListResult
import com.beettechnologies.posly.stores.StoreResponse
import com.beettechnologies.posly.stores.StoreResult
import com.beettechnologies.posly.stores.UpdateStoreRequest
import com.beettechnologies.posly.users.InviteUserOutcome
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun formTestStore(id: String = "store-1", name: String = "Downtown") = StoreResponse(
    id = id,
    name = name,
    address = AddressDto(line1 = "1 Main St", city = "NY", postalCode = "10001", country = "US"),
    timezone = "America/New_York",
    currency = "USD",
    createdAt = 0L,
    updatedAt = 0L
)

private class FakeFormStoreApi(private val stores: List<StoreResponse> = listOf(formTestStore())) : StoreApi {
    override suspend fun createStore(request: CreateStoreRequest) = error("not used in this test")
    override suspend fun listStores(): StoreListResult = StoreListResult.Success(stores)
    override suspend fun getStore(id: String): StoreResult = error("not used in this test")
    override suspend fun updateStore(id: String, request: UpdateStoreRequest) = error("not used in this test")
    override suspend fun deleteStore(id: String): DeleteStoreResult = error("not used in this test")
}

@OptIn(ExperimentalCoroutinesApi::class)
class UserFormViewModelTest {

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
    fun `create mode defaults to CASHIER and loads the store list`() = runTest(dispatcher) {
        val viewModel = UserFormViewModel(FakeUserApi(), FakeFormStoreApi(listOf(formTestStore("store-1", "Downtown"))))

        viewModel.initialize(null)
        advanceUntilIdle()

        assertEquals(setOf("CASHIER"), viewModel.uiState.value.selectedRoles)
        assertEquals(1, viewModel.uiState.value.stores.size)
        assertEquals(false, viewModel.uiState.value.isEditing)
    }

    @Test
    fun `submitInvite requires a username and email`() = runTest(dispatcher) {
        val api = FakeUserApi()
        val viewModel = UserFormViewModel(api, FakeFormStoreApi())
        viewModel.initialize(null)
        advanceUntilIdle()

        viewModel.submitInvite()
        advanceUntilIdle()

        assertEquals("Username and email are required", viewModel.uiState.value.errorMessage)
        assertNull(api.lastInviteRequest)
    }

    @Test
    fun `submitInvite requires at least one role`() = runTest(dispatcher) {
        val api = FakeUserApi()
        val viewModel = UserFormViewModel(api, FakeFormStoreApi())
        viewModel.initialize(null)
        advanceUntilIdle()
        viewModel.onUsernameChange("newhire")
        viewModel.onEmailChange("newhire@example.com")
        viewModel.toggleRole("CASHIER") // unchecks the default CASHIER selection

        viewModel.submitInvite()
        advanceUntilIdle()

        assertEquals("Select at least one role", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a successful invite surfaces the invite token for the no-mail-server demo flow`() = runTest(dispatcher) {
        val api = FakeUserApi()
        val viewModel = UserFormViewModel(api, FakeFormStoreApi())
        viewModel.initialize(null)
        advanceUntilIdle()
        viewModel.onUsernameChange("newhire")
        viewModel.onEmailChange("newhire@example.com")
        viewModel.toggleRole("MANAGER")

        viewModel.submitInvite()
        advanceUntilIdle()

        assertEquals("newhire" to setOf("CASHIER", "MANAGER").toList().sorted(), api.lastInviteRequest?.let { it.first to it.second.sorted() })
        assertTrue(viewModel.uiState.value.invited)
        assertEquals("fake-invite-token", viewModel.uiState.value.inviteToken)
    }

    @Test
    fun `a taken username surfaces a rejection message`() = runTest(dispatcher) {
        val api = FakeUserApi(inviteOutcome = InviteUserOutcome.UsernameTaken)
        val viewModel = UserFormViewModel(api, FakeFormStoreApi())
        viewModel.initialize(null)
        advanceUntilIdle()
        viewModel.onUsernameChange("cashier")
        viewModel.onEmailChange("dupe@example.com")

        viewModel.submitInvite()
        advanceUntilIdle()

        assertEquals("That username is already taken", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `edit mode loads the existing user's roles, store access, and status`() = runTest(dispatcher) {
        val user = testUser(id = "u1", username = "cashier", roles = listOf("CASHIER"), storeIds = listOf("store-1"))
        val api = FakeUserApi(mutableListOf(user))
        val viewModel = UserFormViewModel(api, FakeFormStoreApi())

        viewModel.initialize("u1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEditing)
        assertEquals("cashier", viewModel.uiState.value.username)
        assertEquals(setOf("CASHIER"), viewModel.uiState.value.selectedRoles)
        assertEquals(setOf("store-1"), viewModel.uiState.value.selectedStoreIds)
        assertEquals("ACTIVE", viewModel.uiState.value.status)
    }

    @Test
    fun `saveRoles persists the currently checked roles`() = runTest(dispatcher) {
        val user = testUser(id = "u1", roles = listOf("CASHIER"))
        val api = FakeUserApi(mutableListOf(user))
        val viewModel = UserFormViewModel(api, FakeFormStoreApi())
        viewModel.initialize("u1")
        advanceUntilIdle()
        viewModel.toggleRole("MANAGER")

        viewModel.saveRoles()
        advanceUntilIdle()

        assertEquals("u1" to setOf("CASHIER", "MANAGER").toList().sorted(), api.lastRolesUpdate?.let { it.first to it.second.sorted() })
        assertEquals("Roles updated", viewModel.uiState.value.infoMessage)
    }

    @Test
    fun `saveStoreAccess persists the currently checked stores`() = runTest(dispatcher) {
        val user = testUser(id = "u1")
        val api = FakeUserApi(mutableListOf(user))
        val viewModel = UserFormViewModel(api, FakeFormStoreApi(listOf(formTestStore("store-1"))))
        viewModel.initialize("u1")
        advanceUntilIdle()
        viewModel.toggleStore("store-1")

        viewModel.saveStoreAccess()
        advanceUntilIdle()

        assertEquals("u1" to listOf("store-1"), api.lastStoreAccessUpdate)
    }

    @Test
    fun `toggleStatus disables an active user and re-enables a disabled one`() = runTest(dispatcher) {
        val user = testUser(id = "u1", status = "ACTIVE")
        val api = FakeUserApi(mutableListOf(user))
        val viewModel = UserFormViewModel(api, FakeFormStoreApi())
        viewModel.initialize("u1")
        advanceUntilIdle()

        viewModel.toggleStatus()
        advanceUntilIdle()
        assertEquals("u1" to "DISABLED", api.lastStatusUpdate)
        assertEquals("DISABLED", viewModel.uiState.value.status)

        viewModel.toggleStatus()
        advanceUntilIdle()
        assertEquals("u1" to "ACTIVE", api.lastStatusUpdate)
        assertEquals("ACTIVE", viewModel.uiState.value.status)
    }
}
