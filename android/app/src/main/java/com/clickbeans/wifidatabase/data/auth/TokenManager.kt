package com.clickbeans.wifidatabase.data.auth

import com.clickbeans.wifidatabase.data.api.AuthApi
import com.clickbeans.wifidatabase.data.api.RefreshRequest
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenManager @Inject constructor(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    private val sessionManager: SessionManager,
) {

    @Volatile
    private var accessToken: String? = null

    @Synchronized
    fun currentAccess(): String? = accessToken

    @Synchronized
    fun loadSessionFromDisk(): Boolean {
        val refresh = tokenStore.refreshToken() ?: return false
        if (refresh.isBlank()) return false
        sessionManager.markLoggedIn()
        return true
    }

    @Synchronized
    fun saveTokens(access: String, refresh: String, email: String?) {
        accessToken = access
        tokenStore.save(refresh, email)
        sessionManager.markLoggedIn()
    }

    @Synchronized
    fun currentRefresh(): String? = tokenStore.refreshToken()

    @Synchronized
    fun storedEmail(): String? = tokenStore.email()

    @Synchronized
    fun clearSession() {
        accessToken = null
        tokenStore.clear()
        sessionManager.markLoggedOut()
    }

    @Synchronized
    fun refreshStaleAccess(staleAccess: String?): String? {
        val current = accessToken
        if (current != null && current != staleAccess) {
            return current
        }
        val refresh = tokenStore.refreshToken() ?: return null
        return try {
            val response = authApi.refreshSync(RefreshRequest(refresh)).execute()
            if (response.isSuccessful) {
                val pair = response.body() ?: return null
                accessToken = pair.accessToken
                tokenStore.save(pair.refreshToken, null)
                accessToken
            } else {
                if (response.code() == 400 || response.code() == 401) {
                    clearSession()
                }
                null
            }
        } catch (e: IOException) {
            null
        }
    }
}
