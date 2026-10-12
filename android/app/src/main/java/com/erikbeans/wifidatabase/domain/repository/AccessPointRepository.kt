package com.erikbeans.wifidatabase.domain.repository

import com.erikbeans.wifidatabase.data.api.AccessPointCreateRequest
import com.erikbeans.wifidatabase.data.api.AccessPointDetailDto
import com.erikbeans.wifidatabase.data.api.AccessPointSummaryDto
import com.erikbeans.wifidatabase.data.api.ApiResult
import com.erikbeans.wifidatabase.data.api.ApiService
import com.erikbeans.wifidatabase.data.api.PasswordCandidateCreateRequest
import com.erikbeans.wifidatabase.data.api.ReportRequest
import com.erikbeans.wifidatabase.data.api.VoteRequest
import com.erikbeans.wifidatabase.data.api.VoteResponseDto
import com.erikbeans.wifidatabase.data.api.apiCall
import javax.inject.Inject
import javax.inject.Singleton

data class SubmissionResult(
    val detail: AccessPointDetailDto,
    val statusCode: Int,
    val duplicatePassword: Boolean?,
)

@Singleton
class AccessPointRepository @Inject constructor(
    private val api: ApiService,
) {

    suspend fun near(latitude: Double, longitude: Double, radiusM: Int?): ApiResult<List<AccessPointSummaryDto>> =
        apiCall { api.near(latitude, longitude, radiusM) }

    suspend fun search(query: String): ApiResult<List<AccessPointSummaryDto>> =
        apiCall { api.search(query) }

    suspend fun detail(apId: String): ApiResult<AccessPointDetailDto> =
        apiCall { api.detail(apId) }

    suspend fun create(request: AccessPointCreateRequest): ApiResult<SubmissionResult> = apiCall {
        val response = api.createAccessPoint(request)
        val body = response.body() ?: throw IllegalStateException("empty body")
        SubmissionResult(body, response.code(), body.duplicatePassword)
    }

    suspend fun addCandidate(apId: String, request: PasswordCandidateCreateRequest): ApiResult<SubmissionResult> =
        apiCall {
            val response = api.addCandidate(apId, request)
            val body = response.body() ?: throw IllegalStateException("empty body")
            SubmissionResult(body, response.code(), body.duplicatePassword)
        }

    suspend fun vote(passwordId: String, value: Int): ApiResult<VoteResponseDto> =
        apiCall { api.vote(passwordId, VoteRequest(value)) }

    suspend fun retractVote(passwordId: String): ApiResult<Unit> =
        apiCall { api.retractVote(passwordId) }

    suspend fun deleteCandidate(passwordId: String): ApiResult<Unit> =
        apiCall { api.deleteCandidate(passwordId) }

    suspend fun deleteAccessPoint(apId: String): ApiResult<Unit> =
        apiCall { api.deleteAccessPoint(apId) }

    suspend fun report(apId: String?, passwordId: String?, reason: String): ApiResult<Unit> =
        apiCall { api.report(ReportRequest(apId = apId, passwordId = passwordId, reason = reason)) }
}
