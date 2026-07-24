package com.beettechnologies.posly.auth

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.observability.AppObservability
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.SpanKind
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
    private val jwtService: JwtService,
    private val observability: AppObservability
) {
    // In production, store refresh tokens in Redis/DB with TTL
    private val activeRefreshTokens = ConcurrentHashMap<String, String>() // token -> userId

    fun login(username: String, password: String, remoteIp: String? = null): AuthResult {
        return observability.inSpan(
            name = "auth.login",
            kind = SpanKind.INTERNAL,
            attributes = mapOf("auth.remote_ip" to remoteIp)
        ) { span ->
            val user = userService.findByUsername(username)
            if (user == null || !userService.checkPassword(user, password)) {
                observability.recordAuthLogin("invalid_credentials")
                span.setAttribute("auth.result", "invalid_credentials")
                span.setStatus(StatusCode.ERROR)
                AuditService.record(AuditEvent.LOGIN_FAILURE, username = username, remoteIp = remoteIp)
                return@inSpan AuthResult.InvalidCredentials
            }
            if (user.mfaEnabled) {
                observability.recordAuthLogin("mfa_required")
                span.setAttribute("auth.result", "mfa_required")
                span.setAttribute("enduser.id", user.id)
                AuditService.record(AuditEvent.MFA_CHALLENGE, username = username, userId = user.id, remoteIp = remoteIp)
                return@inSpan AuthResult.MfaRequired(jwtService.generateMfaToken(user.id))
            }
            val accessToken = jwtService.generateAccessToken(user.id, user.roles)
            val refreshToken = jwtService.generateRefreshToken(user.id)
            activeRefreshTokens[refreshToken] = user.id
            observability.recordAuthLogin("success")
            span.setAttribute("auth.result", "success")
            span.setAttribute("enduser.id", user.id)
            AuditService.record(AuditEvent.LOGIN_SUCCESS, username = username, userId = user.id, remoteIp = remoteIp)
            AuthResult.Success(accessToken, refreshToken)
        }
    }

    fun refresh(refreshToken: String, remoteIp: String? = null): AuthResult {
        return observability.inSpan(
            name = "auth.refresh",
            kind = SpanKind.INTERNAL,
            attributes = mapOf("auth.remote_ip" to remoteIp)
        ) { span ->
            val userId = jwtService.verifyRefreshToken(refreshToken)
            if (userId == null || !activeRefreshTokens.containsKey(refreshToken)) {
                observability.recordAuthRefresh("invalid_token")
                span.setAttribute("auth.result", "invalid_token")
                span.setStatus(StatusCode.ERROR)
                AuditService.record(AuditEvent.REFRESH_FAILURE, remoteIp = remoteIp)
                return@inSpan AuthResult.TokenInvalid
            }
            val user = userService.findById(userId)
            if (user == null) {
                observability.recordAuthRefresh("user_not_found")
                span.setAttribute("auth.result", "user_not_found")
                span.setAttribute("enduser.id", userId)
                span.setStatus(StatusCode.ERROR)
                AuditService.record(AuditEvent.REFRESH_FAILURE, userId = userId, remoteIp = remoteIp)
                return@inSpan AuthResult.UserNotFound
            }
            activeRefreshTokens.remove(refreshToken)
            val newAccessToken = jwtService.generateAccessToken(user.id, user.roles)
            val newRefreshToken = jwtService.generateRefreshToken(user.id)
            activeRefreshTokens[newRefreshToken] = user.id
            observability.recordAuthRefresh("success")
            span.setAttribute("auth.result", "success")
            span.setAttribute("enduser.id", userId)
            AuditService.record(AuditEvent.REFRESH_SUCCESS, userId = userId, remoteIp = remoteIp)
            AuthResult.Success(newAccessToken, newRefreshToken)
        }
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
        return observability.inSpan(
            name = "auth.mfa.verify",
            kind = SpanKind.INTERNAL,
            attributes = mapOf("auth.remote_ip" to remoteIp)
        ) { span ->
            val userId = jwtService.verifyMfaToken(mfaToken)
            if (userId == null) {
                observability.recordMfaVerification("invalid_token")
                span.setAttribute("auth.result", "invalid_token")
                span.setStatus(StatusCode.ERROR)
                AuditService.record(AuditEvent.MFA_FAILURE, remoteIp = remoteIp)
                return@inSpan AuthResult.TokenInvalid
            }
            val user = userService.findById(userId)
            if (user == null) {
                observability.recordMfaVerification("user_not_found")
                span.setAttribute("auth.result", "user_not_found")
                span.setAttribute("enduser.id", userId)
                span.setStatus(StatusCode.ERROR)
                return@inSpan AuthResult.UserNotFound
            }
            val secret = user.mfaSecret
            if (secret == null || !MfaService.verifyCode(secret, code)) {
                observability.recordMfaVerification("invalid_code")
                span.setAttribute("auth.result", "invalid_code")
                span.setAttribute("enduser.id", userId)
                span.setStatus(StatusCode.ERROR)
                AuditService.record(AuditEvent.MFA_FAILURE, userId = userId, remoteIp = remoteIp)
                return@inSpan AuthResult.MfaInvalid
            }
            val accessToken = jwtService.generateAccessToken(user.id, user.roles)
            val refreshToken = jwtService.generateRefreshToken(user.id)
            activeRefreshTokens[refreshToken] = user.id
            observability.recordMfaVerification("success")
            span.setAttribute("auth.result", "success")
            span.setAttribute("enduser.id", userId)
            AuditService.record(AuditEvent.MFA_SUCCESS, userId = userId, remoteIp = remoteIp)
            AuthResult.Success(accessToken, refreshToken)
        }
    }
}
