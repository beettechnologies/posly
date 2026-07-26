package com.beettechnologies.posly.auth

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.model.UserStatus
import com.beettechnologies.posly.secrets.InMemorySecretsManager
import com.beettechnologies.posly.secrets.SecretName
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {

    private val jwtService = JwtService(
        secretsManager = InMemorySecretsManager(
            mapOf(
                SecretName.JWT_SIGNING_KEY to "test-secret-at-least-32-characters-long!!",
                SecretName.PAYMENT_WEBHOOK_SECRET to "test-webhook-secret"
            ),
            gracePeriodMs = 86_400_000L
        ),
        issuer = "posly",
        audience = "posly-api",
        accessTokenExpirationMs = 900_000L,
        refreshTokenExpirationMs = 604_800_000L,
        mfaTokenExpirationMs = 300_000L
    )

    @BeforeTest
    fun clearAudit() {
        TestDatabase.reset()
        AuditService.clearForTests()
    }

    // -------------------------------------------------------------------------
    // Invite / accept-invite
    // -------------------------------------------------------------------------

    @Test
    fun `inviteUser emails an accept link and acceptInvite activates the account`() = runBlocking {
        val userService = UserService()
        val authService = AuthService(userService, jwtService)

        val invite = assertIs<InviteResult.Success>(
            authService.inviteUser("newhire", "newhire@example.com", setOf(Role.CASHIER))
        )
        assertNotNull(invite.emailMessageId)
        assertEquals(UserStatus.INVITED, userService.findById(invite.user.id)?.status)

        val accept = authService.acceptInvite(invite.inviteToken, "brandnewpass123")
        assertIs<AcceptInviteResult.Success>(accept)
        val activated = userService.findById(invite.user.id)!!
        assertEquals(UserStatus.ACTIVE, activated.status)
        assertTrue(userService.checkPassword(activated, "brandnewpass123"))
    }

    @Test
    fun `inviteUser to a bouncing address still succeeds but reports no delivery`() = runBlocking {
        val userService = UserService()
        val authService = AuthService(userService, jwtService)

        val invite = assertIs<InviteResult.Success>(
            authService.inviteUser("newhire", "newhire+bounce@example.com", setOf(Role.CASHIER))
        )
        assertNull(invite.emailMessageId)
        // The account still exists and the token still works, even though the email bounced.
        assertEquals(UserStatus.INVITED, userService.findById(invite.user.id)?.status)
    }

    @Test
    fun `inviteUser username collision returns UsernameTaken`() {
        runBlocking {
            val userService = UserService()
            val authService = AuthService(userService, jwtService)
            val result = authService.inviteUser("admin", "a@example.com", setOf(Role.CASHIER))
            assertIs<InviteResult.UsernameTaken>(result)
        }
    }

    @Test
    fun `acceptInvite with an invalid token fails`() {
        val userService = UserService()
        val authService = AuthService(userService, jwtService)
        assertIs<AcceptInviteResult.TokenInvalid>(authService.acceptInvite("not-a-real-token", "newpass123"))
    }

    @Test
    fun `acceptInvite on an already-active account is rejected`() {
        runBlocking {
            val userService = UserService()
            val authService = AuthService(userService, jwtService)
            val invite = assertIs<InviteResult.Success>(authService.inviteUser("newhire", "n@example.com", setOf(Role.CASHIER)))
            authService.acceptInvite(invite.inviteToken, "firstpass123")

            // Redeeming the same invite token again must fail - the account is no longer INVITED.
            val result = authService.acceptInvite(invite.inviteToken, "secondpass123")
            assertIs<AcceptInviteResult.NotInvited>(result)
        }
    }

    // -------------------------------------------------------------------------
    // Role / status changes - audit trail + roleVersion
    // -------------------------------------------------------------------------

    @Test
    fun `updateUserRoles records a USER_ROLES_CHANGED audit event`() {
        val userService = UserService()
        val authService = AuthService(userService, jwtService)
        val cashier = userService.findByUsername("cashier")!!

        authService.updateUserRoles(cashier.id, setOf(Role.MANAGER), changedBy = "admin-id")

        val events = AuditService.list(event = AuditEvent.USER_ROLES_CHANGED)
        assertEquals(1, events.size)
        assertEquals(cashier.id, events.first().userId)
    }

    @Test
    fun `setUserStatus to DISABLED records a USER_STATUS_CHANGED audit event`() {
        val userService = UserService()
        val authService = AuthService(userService, jwtService)
        val cashier = userService.findByUsername("cashier")!!

        authService.setUserStatus(cashier.id, UserStatus.DISABLED, changedBy = "admin-id")

        val events = AuditService.list(event = AuditEvent.USER_STATUS_CHANGED)
        assertEquals(1, events.size)
        assertEquals(cashier.id, events.first().userId)
    }

    // -------------------------------------------------------------------------
    // SSO login
    // -------------------------------------------------------------------------

    @Test
    fun `ssoLogin without any configuration returns NotConfigured`() {
        val userService = UserService()
        val authService = AuthService(userService, jwtService)
        val result = authService.ssoLogin(SsoAssertion("ext-1", "u@example.com", listOf("engineering")))
        assertIs<SsoLoginResult.NotConfigured>(result)
    }

    @Test
    fun `ssoLogin provisions a new local user mapped from external groups`() {
        val userService = UserService()
        val ssoConfigService = SsoConfigService()
        val authService = AuthService(userService, jwtService, ssoConfigService = ssoConfigService)
        ssoConfigService.configure(
            providerName = "Okta",
            roleMappings = listOf(SsoRoleMapping("store-managers", Role.MANAGER)),
            defaultRoles = setOf(Role.CASHIER)
        )

        val result = assertIs<SsoLoginResult.Success>(
            authService.ssoLogin(SsoAssertion("okta|ext-1", "person@example.com", listOf("store-managers")))
        )
        assertEquals(setOf(Role.MANAGER), result.user.roles)
        assertEquals("okta|ext-1", result.user.externalId)
        assertNotNull(userService.findByExternalId("okta|ext-1"))
    }

    @Test
    fun `ssoLogin falls back to defaultRoles when no group mapping matches`() {
        val userService = UserService()
        val ssoConfigService = SsoConfigService()
        val authService = AuthService(userService, jwtService, ssoConfigService = ssoConfigService)
        ssoConfigService.configure(
            providerName = "Okta",
            roleMappings = listOf(SsoRoleMapping("store-managers", Role.MANAGER)),
            defaultRoles = setOf(Role.CASHIER)
        )

        val result = assertIs<SsoLoginResult.Success>(
            authService.ssoLogin(SsoAssertion("okta|ext-2", "person2@example.com", listOf("some-other-group")))
        )
        assertEquals(setOf(Role.CASHIER), result.user.roles)
    }

    @Test
    fun `ssoLogin with no default roles and no matching group returns NoRoleMapped`() {
        val userService = UserService()
        val ssoConfigService = SsoConfigService()
        val authService = AuthService(userService, jwtService, ssoConfigService = ssoConfigService)
        ssoConfigService.configure(
            providerName = "Okta",
            roleMappings = listOf(SsoRoleMapping("store-managers", Role.MANAGER)),
            defaultRoles = emptySet()
        )

        val result = authService.ssoLogin(SsoAssertion("okta|ext-3", "person3@example.com", listOf("nope")))
        assertIs<SsoLoginResult.NoRoleMapped>(result)
    }

    @Test
    fun `ssoLogin re-syncs roles for a returning user whose group mapping changed`() {
        val userService = UserService()
        val ssoConfigService = SsoConfigService()
        val authService = AuthService(userService, jwtService, ssoConfigService = ssoConfigService)
        ssoConfigService.configure(
            providerName = "Okta",
            roleMappings = listOf(SsoRoleMapping("store-managers", Role.MANAGER), SsoRoleMapping("cashiers", Role.CASHIER)),
            defaultRoles = setOf(Role.CASHIER)
        )

        val first = assertIs<SsoLoginResult.Success>(
            authService.ssoLogin(SsoAssertion("okta|ext-4", "person4@example.com", listOf("cashiers")))
        )
        assertEquals(setOf(Role.CASHIER), first.user.roles)

        val second = assertIs<SsoLoginResult.Success>(
            authService.ssoLogin(SsoAssertion("okta|ext-4", "person4@example.com", listOf("store-managers")))
        )
        assertEquals(setOf(Role.MANAGER), second.user.roles)
        assertEquals(first.user.id, second.user.id)
    }

    @Test
    fun `ssoLogin for a disabled account returns AccountDisabled`() {
        val userService = UserService()
        val ssoConfigService = SsoConfigService()
        val authService = AuthService(userService, jwtService, ssoConfigService = ssoConfigService)
        ssoConfigService.configure(
            providerName = "Okta",
            roleMappings = listOf(SsoRoleMapping("store-managers", Role.MANAGER)),
            defaultRoles = setOf(Role.CASHIER)
        )
        val first = assertIs<SsoLoginResult.Success>(
            authService.ssoLogin(SsoAssertion("okta|ext-5", "person5@example.com", listOf("store-managers")))
        )
        userService.setStatus(first.user.id, UserStatus.DISABLED)

        val result = authService.ssoLogin(SsoAssertion("okta|ext-5", "person5@example.com", listOf("store-managers")))
        assertIs<SsoLoginResult.AccountDisabled>(result)
    }

    @Test
    fun `ssoLogin provisioning conflict when externalId is new but username already exists locally`() {
        val userService = UserService()
        val ssoConfigService = SsoConfigService()
        val authService = AuthService(userService, jwtService, ssoConfigService = ssoConfigService)
        ssoConfigService.configure(
            providerName = "Okta",
            roleMappings = emptyList(),
            defaultRoles = setOf(Role.CASHIER)
        )

        // "admin" already exists as a local password-based account (seeded), and provisioning
        // uses the email/username directly - a fresh externalId colliding on username must not
        // silently take over the existing account.
        val result = authService.ssoLogin(SsoAssertion("okta|new-ext", "admin", listOf()))
        assertIs<SsoLoginResult.ProvisioningConflict>(result)
    }

    // -------------------------------------------------------------------------
    // roleVersion propagation into issued tokens
    // -------------------------------------------------------------------------

    @Test
    fun `login issues an access token stamped with the user's current roleVersion`() {
        val userService = UserService()
        val authService = AuthService(userService, jwtService)
        val cashier = userService.findByUsername("cashier")!!
        authService.updateUserRoles(cashier.id, setOf(Role.MANAGER))
        val bumped = userService.findById(cashier.id)!!

        val result = assertIs<AuthResult.Success>(authService.login("cashier", "cashier123"))
        val claims = jwtService.verifyAccessToken(result.accessToken)
        assertNotNull(claims)
        assertEquals(bumped.roleVersion, claims.roleVersion)
        assertEquals(setOf(Role.MANAGER), claims.roles)
    }
}
