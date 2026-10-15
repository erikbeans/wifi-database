package com.clickbeans.wifidatabase.domain.repository

import com.clickbeans.wifidatabase.data.api.ApiResult
import com.clickbeans.wifidatabase.data.api.AuthApi
import com.clickbeans.wifidatabase.data.api.EmailRequest
import com.clickbeans.wifidatabase.data.api.LoginRequest
import com.clickbeans.wifidatabase.data.api.PasswordResetConfirm
import com.clickbeans.wifidatabase.data.api.RefreshRequest
import com.clickbeans.wifidatabase.data.api.RegisterRequest
import com.clickbeans.wifidatabase.data.api.TokenPair
import com.clickbeans.wifidatabase.data.api.VerifyRequest
import com.clickbeans.wifidatabase.data.api.apiCall
import com.clickbeans.wifidatabase.data.auth.TokenManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val tokenManager: TokenManager,
) {

    suspend fun register(email: String, password: String): ApiResult<Unit> =
        apiCall { authApi.register(RegisterRequest(email, password)); Unit }

    suspend fun verify(token: String): ApiResult<Unit> = apiCall {
        val pair = authApi.verify(VerifyRequest(token))
        tokenManager.saveTokens(pair.accessToken, pair.refreshToken, null)
    }

    suspend fun login(email: String, password: String): ApiResult<Unit> = apiCall {
        val pair = authApi.login(LoginRequest(email, password))
        tokenManager.saveTokens(pair.accessToken, pair.refreshToken, email)
    }

    suspend fun resendVerification(email: String): ApiResult<Unit> =
        apiCall { authApi.resendVerification(EmailRequest(email)); Unit }

    suspend fun requestPasswordReset(email: String): ApiResult<Unit> =
        apiCall { authApi.requestPasswordReset(EmailRequest(email)); Unit }

    suspend fun confirmPasswordReset(token: String, newPassword: String): ApiResult<Unit> =
        apiCall { authApi.confirmPasswordReset(PasswordResetConfirm(token, newPassword)); Unit }

    suspend fun logout(): ApiResult<Unit> {
        val refresh = tokenManager.currentRefresh()
        val result = if (refresh != null) {
            apiCall { authApi.logout(RefreshRequest(refresh)); Unit }
        } else {
            ApiResult.Success(Unit)
        }
        tokenManager.clearSession()
        return result
    }

    suspend fun deleteAccount(): ApiResult<Unit> {
        tokenManager.clearSession()
        return apiCall { authApi.deleteAccount(); Unit }
    }

    fun signedInEmail(): String? = tokenManager.storedEmail()
}
