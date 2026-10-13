package com.erikbeans.wifidatabase.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(val email: String, val password: String)

@Serializable
data class VerifyRequest(val token: String)

@Serializable
data class EmailRequest(val email: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class PasswordResetConfirm(val token: String, @SerialName("new_password") val newPassword: String)

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class AccessPointCreateRequest(
    val ssid: String,
    val password: String,
    val bssid: String? = null,
    val security: String? = null,
    val latitude: Double,
    val longitude: Double,
    @SerialName("consent_confirmed") val consentConfirmed: Boolean,
)

@Serializable
data class PasswordCandidateCreateRequest(
    val password: String,
    @SerialName("consent_confirmed") val consentConfirmed: Boolean,
)

@Serializable
data class VoteRequest(val value: Int)

@Serializable
data class ReportRequest(
    @SerialName("ap_id") val apId: String? = null,
    @SerialName("password_id") val passwordId: String? = null,
    val reason: String,
)

@Serializable
data class PasswordCandidateDto(
    val id: String,
    val password: String,
    @SerialName("up_votes") val upVotes: Int = 0,
    @SerialName("down_votes") val downVotes: Int = 0,
    val score: Int = 0,
    @SerialName("my_vote") val myVote: Int? = null,
    val mine: Boolean = false,
)

@Serializable
data class AccessPointSummaryDto(
    val id: String,
    val ssid: String,
    val bssid: String? = null,
    val security: String? = null,
    val latitude: Double,
    val longitude: Double,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("password_count") val passwordCount: Int = 0,
    @SerialName("best_score") val bestScore: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class AccessPointDetailDto(
    val id: String,
    val ssid: String,
    val bssid: String? = null,
    val security: String? = null,
    val latitude: Double,
    val longitude: Double,
    val passwords: List<PasswordCandidateDto> = emptyList(),
    @SerialName("duplicate_password") val duplicatePassword: Boolean? = null,
    val mine: Boolean = false,
)

@Serializable
data class VoteResponseDto(
    @SerialName("password_id") val passwordId: String,
    @SerialName("up_votes") val upVotes: Int,
    @SerialName("down_votes") val downVotes: Int,
    val score: Int,
    @SerialName("my_vote") val myVote: Int? = null,
)

@Serializable
data class StatusResponse(val status: String)
