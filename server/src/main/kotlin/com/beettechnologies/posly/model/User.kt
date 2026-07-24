package com.beettechnologies.posly.model

import java.util.UUID

data class User(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val passwordHash: String,
    val roles: Set<Role> = setOf(Role.CASHIER),
    val mfaEnabled: Boolean = false,
    val mfaSecret: String? = null
)
