package com.beettechnologies.posly.auth

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val mfaRequired: Boolean = false,
    val mfaToken: String? = null
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class RefreshResponse(val accessToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class MfaVerifyRequest(val mfaToken: String, val code: String)

@Serializable
data class MfaVerifyResponse(val accessToken: String, val refreshToken: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class MeResponse(val userId: String?, val roles: List<String>)
