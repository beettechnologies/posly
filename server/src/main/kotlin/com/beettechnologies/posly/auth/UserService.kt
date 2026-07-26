package com.beettechnologies.posly.auth

import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.model.User
import com.beettechnologies.posly.model.UserStatus
import org.mindrot.jbcrypt.BCrypt
import java.util.concurrent.ConcurrentHashMap

sealed class InviteUserResult {
    data class Success(val user: User) : InviteUserResult()
    data object UsernameTaken : InviteUserResult()
}

/**
 * Owns the [User] aggregate - both locally-created accounts (with a password from day one) and
 * admin-invited or SSO-provisioned accounts (which start without one). `users` is keyed by
 * username, the one field guaranteed unique and stable for a lookup key.
 */
class UserService {

    private val users = ConcurrentHashMap<String, User>()

    init {
        // Seed default users for testing/demo
        createUser("admin", "admin123", setOf(Role.ADMIN))
        createUser("manager", "manager123", setOf(Role.MANAGER))
        createUser("cashier", "cashier123", setOf(Role.CASHIER))
    }

    fun createUser(
        username: String,
        plainPassword: String,
        roles: Set<Role> = setOf(Role.CASHIER),
        mfaEnabled: Boolean = false,
        mfaSecret: String? = null
    ): User {
        val hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt())
        val user = User(
            username = username,
            passwordHash = hash,
            roles = roles,
            mfaEnabled = mfaEnabled,
            mfaSecret = mfaSecret
        )
        users[username] = user
        return user
    }

    /** Creates a passwordless [UserStatus.INVITED] account - the invitee sets their own password via [setPassword]. */
    fun inviteUser(username: String, email: String, roles: Set<Role>, storeIds: Set<String> = emptySet()): InviteUserResult {
        if (users.containsKey(username)) return InviteUserResult.UsernameTaken
        val user = User(
            username = username,
            passwordHash = null,
            email = email,
            roles = roles,
            storeIds = storeIds,
            status = UserStatus.INVITED
        )
        users[username] = user
        return InviteUserResult.Success(user)
    }

    /** Sets the invitee's chosen password and activates the account - the last step of accepting an invite. */
    fun setPassword(userId: String, plainPassword: String): User? =
        updateUser(userId) { it.copy(passwordHash = BCrypt.hashpw(plainPassword, BCrypt.gensalt()), status = UserStatus.ACTIVE) }

    /** Bumps [User.roleVersion] so every previously-issued access token for this user fails its next live check. */
    fun updateRoles(userId: String, roles: Set<Role>): User? =
        updateUser(userId) { it.copy(roles = roles, roleVersion = it.roleVersion + 1) }

    /** Recorded for reporting only - see [User.storeIds]; does not itself bump [User.roleVersion]. */
    fun updateStoreIds(userId: String, storeIds: Set<String>): User? =
        updateUser(userId) { it.copy(storeIds = storeIds) }

    /** Also bumps [User.roleVersion] - disabling a user must invalidate their outstanding tokens too. */
    fun setStatus(userId: String, status: UserStatus): User? =
        updateUser(userId) { it.copy(status = status, roleVersion = it.roleVersion + 1) }

    fun listUsers(): List<User> = users.values.sortedBy { it.username }

    fun findByUsername(username: String): User? = users[username]

    fun findById(id: String): User? = users.values.firstOrNull { it.id == id }

    fun findByExternalId(externalId: String): User? = users.values.firstOrNull { it.externalId == externalId }

    /** First-login provisioning for an SSO user with no local account yet. Fails if [username] (typically the SSO email) is already taken by an unrelated account. */
    fun provisionSsoUser(externalId: String, username: String, roles: Set<Role>): User? {
        if (users.containsKey(username)) return null
        val user = User(
            username = username,
            passwordHash = null,
            email = username,
            roles = roles,
            status = UserStatus.ACTIVE,
            externalId = externalId
        )
        users[username] = user
        return user
    }

    fun checkPassword(user: User, plainPassword: String): Boolean =
        user.passwordHash != null && BCrypt.checkpw(plainPassword, user.passwordHash)

    fun enableMfa(userId: String, secret: String): User? =
        updateUser(userId) { it.copy(mfaEnabled = true, mfaSecret = secret) }

    private fun updateUser(userId: String, transform: (User) -> User): User? {
        val existing = findById(userId) ?: return null
        val updated = transform(existing)
        users[existing.username] = updated
        return updated
    }
}
