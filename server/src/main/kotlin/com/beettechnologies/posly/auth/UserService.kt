package com.beettechnologies.posly.auth

import com.beettechnologies.posly.db.UsersTable
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.model.User
import com.beettechnologies.posly.model.UserStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.mindrot.jbcrypt.BCrypt

sealed class InviteUserResult {
    data class Success(val user: User) : InviteUserResult()
    data object UsernameTaken : InviteUserResult()
}

private fun rowToUser(row: ResultRow) = User(
    id = row[UsersTable.id],
    username = row[UsersTable.username],
    passwordHash = row[UsersTable.passwordHash],
    email = row[UsersTable.email],
    roles = row[UsersTable.roles].map { Role.valueOf(it) }.toSet(),
    storeIds = row[UsersTable.storeIds].toSet(),
    status = UserStatus.valueOf(row[UsersTable.status]),
    mfaEnabled = row[UsersTable.mfaEnabled],
    mfaSecret = row[UsersTable.mfaSecret],
    roleVersion = row[UsersTable.roleVersion],
    externalId = row[UsersTable.externalId]
)

/**
 * Owns the [User] aggregate - both locally-created accounts (with a password from day one) and
 * admin-invited or SSO-provisioned accounts (which start without one). `users` is keyed by
 * username, the one field guaranteed unique and stable for a lookup key.
 */
class UserService {

    init {
        // Seed default users for testing/demo - only on a genuinely empty table, since restarting
        // against an already-seeded database must not re-create (or reset the password of) these accounts.
        transaction {
            if (UsersTable.selectAll().empty()) {
                createUser("admin", "admin123", setOf(Role.ADMIN))
                createUser("manager", "manager123", setOf(Role.MANAGER))
                createUser("cashier", "cashier123", setOf(Role.CASHIER))
            }
        }
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
        transaction { insertUser(user) }
        return user
    }

    /** Creates a passwordless [UserStatus.INVITED] account - the invitee sets their own password via [setPassword]. */
    fun inviteUser(username: String, email: String, roles: Set<Role>, storeIds: Set<String> = emptySet()): InviteUserResult = transaction {
        if (!UsersTable.selectAll().where { UsersTable.username eq username }.empty()) return@transaction InviteUserResult.UsernameTaken
        val user = User(
            username = username,
            passwordHash = null,
            email = email,
            roles = roles,
            storeIds = storeIds,
            status = UserStatus.INVITED
        )
        insertUser(user)
        InviteUserResult.Success(user)
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

    fun listUsers(): List<User> = transaction {
        UsersTable.selectAll().map { rowToUser(it) }.sortedBy { it.username }
    }

    fun findByUsername(username: String): User? = transaction {
        UsersTable.selectAll().where { UsersTable.username eq username }.singleOrNull()?.let { rowToUser(it) }
    }

    fun findById(id: String): User? = transaction {
        UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.let { rowToUser(it) }
    }

    fun findByExternalId(externalId: String): User? = transaction {
        UsersTable.selectAll().where { UsersTable.externalId eq externalId }.singleOrNull()?.let { rowToUser(it) }
    }

    /** First-login provisioning for an SSO user with no local account yet. Fails if [username] (typically the SSO email) is already taken by an unrelated account. */
    fun provisionSsoUser(externalId: String, username: String, roles: Set<Role>): User? = transaction {
        if (!UsersTable.selectAll().where { UsersTable.username eq username }.empty()) return@transaction null
        val user = User(
            username = username,
            passwordHash = null,
            email = username,
            roles = roles,
            status = UserStatus.ACTIVE,
            externalId = externalId
        )
        insertUser(user)
        user
    }

    fun checkPassword(user: User, plainPassword: String): Boolean =
        user.passwordHash != null && BCrypt.checkpw(plainPassword, user.passwordHash)

    fun enableMfa(userId: String, secret: String): User? =
        updateUser(userId) { it.copy(mfaEnabled = true, mfaSecret = secret) }

    private fun insertUser(user: User) {
        UsersTable.insert {
            it[id] = user.id
            it[username] = user.username
            it[passwordHash] = user.passwordHash
            it[email] = user.email
            it[roles] = user.roles.map { role -> role.name }
            it[storeIds] = user.storeIds.toList()
            it[status] = user.status.name
            it[mfaEnabled] = user.mfaEnabled
            it[mfaSecret] = user.mfaSecret
            it[roleVersion] = user.roleVersion
            it[externalId] = user.externalId
        }
    }

    private fun updateUser(userId: String, transform: (User) -> User): User? = transaction {
        val existing = findById(userId) ?: return@transaction null
        val updated = transform(existing)
        UsersTable.update({ UsersTable.id eq userId }) {
            it[passwordHash] = updated.passwordHash
            it[email] = updated.email
            it[roles] = updated.roles.map { role -> role.name }
            it[storeIds] = updated.storeIds.toList()
            it[status] = updated.status.name
            it[mfaEnabled] = updated.mfaEnabled
            it[mfaSecret] = updated.mfaSecret
            it[roleVersion] = updated.roleVersion
            it[externalId] = updated.externalId
        }
        updated
    }
}
