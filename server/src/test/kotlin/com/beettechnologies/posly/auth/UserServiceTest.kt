package com.beettechnologies.posly.auth

import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.model.UserStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserServiceTest {

    @Test
    fun `seeded admin manager cashier accounts exist with expected roles`() {
        val service = UserService()
        assertEquals(setOf(Role.ADMIN), service.findByUsername("admin")?.roles)
        assertEquals(setOf(Role.MANAGER), service.findByUsername("manager")?.roles)
        assertEquals(setOf(Role.CASHIER), service.findByUsername("cashier")?.roles)
    }

    @Test
    fun `inviteUser creates an INVITED user with no password`() {
        val service = UserService()
        val result = service.inviteUser("newhire", "newhire@example.com", setOf(Role.CASHIER))
        val invited = assertIs<InviteUserResult.Success>(result).user
        assertEquals(UserStatus.INVITED, invited.status)
        assertNull(invited.passwordHash)
        assertEquals("newhire@example.com", invited.email)
    }

    @Test
    fun `inviteUser rejects a username that is already taken`() {
        val service = UserService()
        val result = service.inviteUser("admin", "admin2@example.com", setOf(Role.CASHIER))
        assertIs<InviteUserResult.UsernameTaken>(result)
    }

    @Test
    fun `setPassword activates an invited user and clears the invited state`() {
        val service = UserService()
        val invited = (service.inviteUser("newhire", "n@example.com", setOf(Role.CASHIER)) as InviteUserResult.Success).user
        val activated = service.setPassword(invited.id, "newpass123")
        assertNotNull(activated)
        assertEquals(UserStatus.ACTIVE, activated.status)
        assertTrue(service.checkPassword(activated, "newpass123"))
    }

    @Test
    fun `updateRoles bumps roleVersion`() {
        val service = UserService()
        val admin = service.findByUsername("admin")!!
        val updated = service.updateRoles(admin.id, setOf(Role.MANAGER))
        assertNotNull(updated)
        assertEquals(setOf(Role.MANAGER), updated.roles)
        assertEquals(admin.roleVersion + 1, updated.roleVersion)
    }

    @Test
    fun `setStatus bumps roleVersion so previously issued tokens can be invalidated`() {
        val service = UserService()
        val cashier = service.findByUsername("cashier")!!
        val disabled = service.setStatus(cashier.id, UserStatus.DISABLED)
        assertNotNull(disabled)
        assertEquals(UserStatus.DISABLED, disabled.status)
        assertEquals(cashier.roleVersion + 1, disabled.roleVersion)
    }

    @Test
    fun `updateStoreIds does not bump roleVersion`() {
        val service = UserService()
        val cashier = service.findByUsername("cashier")!!
        val updated = service.updateStoreIds(cashier.id, setOf("store-1", "store-2"))
        assertNotNull(updated)
        assertEquals(setOf("store-1", "store-2"), updated.storeIds)
        assertEquals(cashier.roleVersion, updated.roleVersion)
    }

    @Test
    fun `provisionSsoUser creates an active user keyed by externalId`() {
        val service = UserService()
        val user = service.provisionSsoUser("okta|abc123", "sso.user@example.com", setOf(Role.CASHIER))
        assertNotNull(user)
        assertEquals(UserStatus.ACTIVE, user.status)
        assertEquals("okta|abc123", user.externalId)
        assertEquals(user, service.findByExternalId("okta|abc123"))
    }

    @Test
    fun `provisionSsoUser returns null on username collision with an unrelated account`() {
        val service = UserService()
        val user = service.provisionSsoUser("okta|xyz", "admin", setOf(Role.CASHIER))
        assertNull(user)
    }

    @Test
    fun `checkPassword returns false for a user with no password set yet`() {
        val service = UserService()
        val invited = (service.inviteUser("newhire", "n@example.com", setOf(Role.CASHIER)) as InviteUserResult.Success).user
        assertFalse(service.checkPassword(invited, "anything"))
    }
}
