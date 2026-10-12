package com.erikbeans.wifidatabase.data.api

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST

interface AuthApi {

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): MessageResponse

    @POST("api/v1/auth/verify")
    suspend fun verify(@Body body: VerifyRequest): TokenPair

    @POST("api/v1/auth/resend-verification")
    suspend fun resendVerification(@Body body: EmailRequest): MessageResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): TokenPair

    @POST("api/v1/auth/refresh")
    fun refreshSync(@Body body: RefreshRequest): Call<TokenPair>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequest): retrofit2.Response<Unit>

    @POST("api/v1/auth/password-reset-request")
    suspend fun requestPasswordReset(@Body body: EmailRequest): MessageResponse

    @POST("api/v1/auth/password-reset")
    suspend fun confirmPasswordReset(@Body body: PasswordResetConfirm): retrofit2.Response<Unit>

    @DELETE("api/v1/auth/me")
    suspend fun deleteAccount(): retrofit2.Response<Unit>
}
