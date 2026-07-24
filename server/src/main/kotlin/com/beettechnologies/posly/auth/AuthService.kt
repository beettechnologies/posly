package com.beettechnologies.posly.auth

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import java.util.concurrent.ConcurrentHashMap

sealed class AuthResult {
    data class Success(val accessToken: String, val refreshToken: String) : AuthResult()
    data class MfaRequired(val mfaToken: String) : AuthResult()
    data object InvalidCredentials : AuthResult()
    data object MfaInvalid : AuthResult()
    data object TokenInvalid : AuthResult()
    data object UserNotFound : AuthResult()
}

class AuthService(
    private val userService: UserService,
    private val jwtService: JwtService
) {
    // In production, store refresh tokens in Redis/DB with TTL
    private val activeRefreshTokens = ConcurrentHashMap<String, String>() // token -> userId

    fun login(username: String, password: String, remoteIp: String? = null): AuthResult {
        val user = userService.findByUsername(username)
        if (user == null || !userService.checkPassword(user, password)) {
            AuditService.record(AuditEvent.LOGIN_FAILURE, username = username, remoteIp = remoteIp)
            return AuthResult.InvalidCredentials
        }
        if (user.mfaEnabled) {
            AuditService.record(AuditEvent.MFA_CHALLENGE, username = username, userId = user.id, remoteIp = remoteIp)
            return AuthResult.MfaRequired(jwtService.generateMfaToken(user.id))
        }
        val accessToken = jwtService.generateAccessToken(user.id, user.roles)
        val refreshToken = jwtService.generateRefreshToken(user.id)
        activeRefreshTokens[refreshToken] = user.id
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
        // Rotate refresh token
        activeRefreshTokens.remove(refreshToken)
        val newAccessToken = jwtService.generateAccessToken(user.id, user.roles)
        val newRefreshToken = jwtService.generateRefreshToken(user.id)
        activeRefreshTokens[newRefreshToken] = user.id
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
        val secret = user.mfaSecret ?: return AuthResult.MfaInvalid
        if (!MfaService.verifyCode(secret, code)) {
            AuditService.record(AuditEvent.MFA_FAILURE, userId = userId, remoteIp = remoteIp)
            return AuthResult.MfaInvalid
        }
        val accessToken = jwtService.generateAccessToken(user.id, user.roles)
        val refreshToken = jwtService.generateRefreshToken(user.id)
        activeRefreshTokens[refreshToken] = user.id
        AuditService.record(AuditEvent.MFA_SUCCESS, userId = userId, remoteIp = remoteIp)
        return AuthResult.Success(accessToken, refreshToken)
    }
}
