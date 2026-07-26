package com.beettechnologies.posly.model

import java.util.UUID

enum class UserStatus { INVITED, ACTIVE, DISABLED }

data class User(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    /** Null while [status] is [UserStatus.INVITED] - the invited user hasn't set a password yet. */
    val passwordHash: String?,
    val email: String? = null,
    val roles: Set<Role> = setOf(Role.CASHIER),
    /** Stores the admin's store-access grant; not currently enforced by any route (recorded only). */
    val storeIds: Set<String> = emptySet(),
    val status: UserStatus = UserStatus.ACTIVE,
    val mfaEnabled: Boolean = false,
    val mfaSecret: String? = null,
    /**
     * Bumped on any role or status change. Embedded in every access token issued for this user and
     * checked against the live value on every authenticated request (see the JWT `validate` block in
     * Application.kt) - a mismatch instantly invalidates all previously-issued tokens, satisfying
     * "revoke permissions immediately" without needing a token blocklist.
     */
    val roleVersion: Int = 0,
    /** The provider-side subject identifier for a user provisioned via SSO; null for locally-created users. */
    val externalId: String? = null
)
