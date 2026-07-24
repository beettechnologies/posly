package com.beettechnologies.posly.auth

import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.model.User
import org.mindrot.jbcrypt.BCrypt
import java.util.concurrent.ConcurrentHashMap

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

    fun findByUsername(username: String): User? = users[username]

    fun findById(id: String): User? = users.values.firstOrNull { it.id == id }

    fun checkPassword(user: User, plainPassword: String): Boolean =
        BCrypt.checkpw(plainPassword, user.passwordHash)

    fun enableMfa(userId: String, secret: String): User? {
        val user = findById(userId) ?: return null
        val updated = user.copy(mfaEnabled = true, mfaSecret = secret)
        users[user.username] = updated
        return updated
    }
}
