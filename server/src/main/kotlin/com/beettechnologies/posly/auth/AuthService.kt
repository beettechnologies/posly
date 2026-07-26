package com.beettechnologies.posly.auth

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.email.EmailGateway
import com.beettechnologies.posly.email.SimulatorEmailGateway
import com.beettechnologies.posly.gateway.GatewayException
import com.beettechnologies.posly.gateway.RetryPolicy
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.model.User
import com.beettechnologies.posly.model.UserStatus
import java.util.concurrent.ConcurrentHashMap

sealed class AuthResult {
    data class Success(val accessToken: String, val refreshToken: String) : AuthResult()
    data class MfaRequired(val mfaToken: String) : AuthResult()
    data object InvalidCredentials : AuthResult()
    data object MfaInvalid : AuthResult()
    data object TokenInvalid : AuthResult()
    data object UserNotFound : AuthResult()
}

sealed class InviteResult {
    data class Success(val user: User, val inviteToken: String, val emailMessageId: String?) : InviteResult()
    data object UsernameTaken : InviteResult()
}

sealed class AcceptInviteResult {
    data object Success : AcceptInviteResult()
    data object TokenInvalid : AcceptInviteResult()
    data object UserNotFound : AcceptInviteResult()
    data object NotInvited : AcceptInviteResult()
}

sealed class SsoLoginResult {
    data class Success(val accessToken: String, val refreshToken: String, val user: User) : SsoLoginResult()
    data object NotConfigured : SsoLoginResult()
    data object NoRoleMapped : SsoLoginResult()
    data object AccountDisabled : SsoLoginResult()
    data object ProvisioningConflict : SsoLoginResult()
}

/**
 * Orchestrates everything token-shaped: password login, refresh rotation, MFA, admin-issued
 * invites, and (simulated) SSO login - [UserService] stays a plain data owner, this class is
 * where token issuance and audit-recording for auth/identity actions live.
 */
class AuthService(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val emailGateway: EmailGateway = SimulatorEmailGateway(),
    private val ssoConfigService: SsoConfigService = SsoConfigService(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val inviteBaseUrl: String = "https://app.posly.local/accept-invite"
) {
    // In production, store refresh tokens in Redis/DB with TTL
    private val activeRefreshTokens = ConcurrentHashMap<String, String>() // token -> userId

    fun login(username: String, password: String, remoteIp: String? = null): AuthResult {
        val user = userService.findByUsername(username)
        if (user == null || !userService.checkPassword(user, password)) {
            AuditService.record(AuditEvent.LOGIN_FAILURE, username = username, remoteIp = remoteIp)
            return AuthResult.InvalidCredentials
        }
        if (user.status == UserStatus.DISABLED) {
            AuditService.record(AuditEvent.LOGIN_FAILURE, username = username, userId = user.id, remoteIp = remoteIp, detail = "account disabled")
            return AuthResult.InvalidCredentials
        }
        if (user.mfaEnabled) {
            AuditService.record(AuditEvent.MFA_CHALLENGE, username = username, userId = user.id, remoteIp = remoteIp)
            return AuthResult.MfaRequired(jwtService.generateMfaToken(user.id))
        }
        val (accessToken, refreshToken) = issueTokens(user)
        AuditService.record(AuditEvent.LOGIN_SUCCESS, username = username, userId = user.id, remoteIp = remoteIp)
        return AuthResult.Success(accessToken, refreshToken)
    }

    fun refresh(refreshToken: String, remoteIp: String? = null): AuthResult {
        val userId = jwtService.verifyRefreshToken(refreshToken)
        if (userId == null || !activeRefreshTokens.containsKey(refreshToken)) {
            AuditService.record(AuditEvent.REFRESH_FAILURE, remoteIp = remoteIp)
            return AuthResult.TokenInvalid
        }
        val user = userService.findById(userId)
        if (user == null) {
            AuditService.record(AuditEvent.REFRESH_FAILURE, userId = userId, remoteIp = remoteIp)
            return AuthResult.UserNotFound
        }
        activeRefreshTokens.remove(refreshToken)
        if (user.status == UserStatus.DISABLED) {
            AuditService.record(AuditEvent.REFRESH_FAILURE, userId = userId, remoteIp = remoteIp, detail = "account disabled")
            return AuthResult.TokenInvalid
        }
        val (newAccessToken, newRefreshToken) = issueTokens(user)
        AuditService.record(AuditEvent.REFRESH_SUCCESS, userId = userId, remoteIp = remoteIp)
        return AuthResult.Success(newAccessToken, newRefreshToken)
    }

    fun logout(refreshToken: String, remoteIp: String? = null): Boolean {
        val userId = jwtService.verifyRefreshToken(refreshToken)
        val removed = activeRefreshTokens.remove(refreshToken) != null
        if (removed) {
            AuditService.record(AuditEvent.LOGOUT, userId = userId, remoteIp = remoteIp)
        }
        return removed
    }

    fun verifyMfa(mfaToken: String, code: String, remoteIp: String? = null): AuthResult {
        val userId = jwtService.verifyMfaToken(mfaToken)
        if (userId == null) {
            AuditService.record(AuditEvent.MFA_FAILURE, remoteIp = remoteIp)
            return AuthResult.TokenInvalid
        }
        val user = userService.findById(userId) ?: return AuthResult.UserNotFound
        if (user.status == UserStatus.DISABLED) {
            AuditService.record(AuditEvent.MFA_FAILURE, userId = userId, remoteIp = remoteIp, detail = "account disabled")
            return AuthResult.TokenInvalid
        }
        val secret = user.mfaSecret ?: return AuthResult.MfaInvalid
        if (!MfaService.verifyCode(secret, code)) {
            AuditService.record(AuditEvent.MFA_FAILURE, userId = userId, remoteIp = remoteIp)
            return AuthResult.MfaInvalid
        }
        val (accessToken, refreshToken) = issueTokens(user)
        AuditService.record(AuditEvent.MFA_SUCCESS, userId = userId, remoteIp = remoteIp)
        return AuthResult.Success(accessToken, refreshToken)
    }

    /** Creates a passwordless [UserStatus.INVITED] account and emails a link to [AcceptInviteResult]-consuming set-password flow. */
    suspend fun inviteUser(username: String, email: String, roles: Set<Role>, storeIds: Set<String> = emptySet()): InviteResult {
        val invited = when (val result = userService.inviteUser(username, email, roles, storeIds)) {
            is InviteUserResult.Success -> result.user
            InviteUserResult.UsernameTaken -> return InviteResult.UsernameTaken
        }
        val token = jwtService.generateInviteToken(invited.id)
        val acceptUrl = "$inviteBaseUrl?token=$token"
        val messageId = try {
            retryPolicy.withBackoff {
                emailGateway.sendPlainText(
                    recipient = email,
                    subject = "You've been invited to Posly",
                    body = "Hi $username,\n\nAn administrator has invited you to Posly. " +
                        "Set your password to get started:\n$acceptUrl\n\nThis link expires in 7 days."
                )
            }
        } catch (e: GatewayException) {
            null
        }
        AuditService.record(
            AuditEvent.USER_INVITED, username = username, userId = invited.id,
            detail = "roles=${roles.joinToString()} emailDelivered=${messageId != null}"
        )
        return InviteResult.Success(invited, token, messageId)
    }

    fun acceptInvite(token: String, newPassword: String): AcceptInviteResult {
        val userId = jwtService.verifyInviteToken(token) ?: return AcceptInviteResult.TokenInvalid
        val user = userService.findById(userId) ?: return AcceptInviteResult.UserNotFound
        if (user.status != UserStatus.INVITED) return AcceptInviteResult.NotInvited
        userService.setPassword(userId, newPassword)
        AuditService.record(AuditEvent.USER_STATUS_CHANGED, username = user.username, userId = userId, detail = "status=ACTIVE (invite accepted)")
        return AcceptInviteResult.Success
    }

    fun updateUserRoles(userId: String, roles: Set<Role>, changedBy: String? = null): User? {
        val updated = userService.updateRoles(userId, roles) ?: return null
        AuditService.record(
            AuditEvent.USER_ROLES_CHANGED, username = updated.username, userId = userId,
            detail = "roles=${roles.joinToString()} changedBy=$changedBy"
        )
        return updated
    }

    fun updateUserStoreAccess(userId: String, storeIds: Set<String>): User? = userService.updateStoreIds(userId, storeIds)

    fun setUserStatus(userId: String, status: UserStatus, changedBy: String? = null): User? {
        val updated = userService.setStatus(userId, status) ?: return null
        AuditService.record(
            AuditEvent.USER_STATUS_CHANGED, username = updated.username, userId = userId,
            detail = "status=$status changedBy=$changedBy"
        )
        return updated
    }

    /**
     * Applies the configured SSO role mapping to [assertion]'s external groups, finds-or-provisions
     * the local user (keyed by [SsoAssertion.externalId]), syncs their roles if the mapping produced
     * something different from what's on file, and issues normal tokens - from here on an SSO
     * session is indistinguishable from a password one.
     */
    fun ssoLogin(assertion: SsoAssertion, remoteIp: String? = null): SsoLoginResult {
        val config = ssoConfigService.getConfiguration()?.takeIf { it.enabled } ?: run {
            AuditService.record(AuditEvent.SSO_LOGIN_FAILURE, username = assertion.email, remoteIp = remoteIp, detail = "SSO not configured or disabled")
            return SsoLoginResult.NotConfigured
        }
        val mappedRoles = config.roleMappings.filter { it.externalGroup in assertion.externalGroups }
            .map { it.role }.toSet().ifEmpty { config.defaultRoles }
        if (mappedRoles.isEmpty()) {
            AuditService.record(AuditEvent.SSO_LOGIN_FAILURE, username = assertion.email, remoteIp = remoteIp, detail = "no role mapping matched")
            return SsoLoginResult.NoRoleMapped
        }

        var user = userService.findByExternalId(assertion.externalId)
        if (user == null) {
            user = userService.provisionSsoUser(assertion.externalId, assertion.email, mappedRoles) ?: run {
                AuditService.record(
                    AuditEvent.SSO_LOGIN_FAILURE, username = assertion.email, remoteIp = remoteIp,
                    detail = "username conflict during provisioning"
                )
                return SsoLoginResult.ProvisioningConflict
            }
        } else if (user.status == UserStatus.DISABLED) {
            AuditService.record(AuditEvent.SSO_LOGIN_FAILURE, username = user.username, userId = user.id, remoteIp = remoteIp, detail = "account disabled")
            return SsoLoginResult.AccountDisabled
        } else if (user.roles != mappedRoles) {
            user = userService.updateRoles(user.id, mappedRoles) ?: user
        }

        val (accessToken, refreshToken) = issueTokens(user)
        AuditService.record(
            AuditEvent.SSO_LOGIN_SUCCESS, username = user.username, userId = user.id,
            remoteIp = remoteIp, detail = "provider=${config.providerName}"
        )
        return SsoLoginResult.Success(accessToken, refreshToken, user)
    }

    private fun issueTokens(user: User): Pair<String, String> {
        val accessToken = jwtService.generateAccessToken(user.id, user.roles, user.roleVersion)
        val refreshToken = jwtService.generateRefreshToken(user.id)
        activeRefreshTokens[refreshToken] = user.id
        return accessToken to refreshToken
    }
}
