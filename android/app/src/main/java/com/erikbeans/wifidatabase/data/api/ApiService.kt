package com.erikbeans.wifidatabase.data.api

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("api/v1/access-points/near")
    suspend fun near(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("radius_m") radiusM: Int? = null,
        @Query("limit") limit: Int = 100,
    ): List<AccessPointSummaryDto>

    @GET("api/v1/access-points/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int = 100,
    ): List<AccessPointSummaryDto>

    @GET("api/v1/access-points/{id}")
    suspend fun detail(@Path("id") apId: String): AccessPointDetailDto

    @POST("api/v1/access-points")
    suspend fun createAccessPoint(@Body body: AccessPointCreateRequest): retrofit2.Response<AccessPointDetailDto>

    @POST("api/v1/access-points/{ap_id}/passwords")
    suspend fun addCandidate(
        @Path("ap_id") apId: String,
        @Body body: PasswordCandidateCreateRequest,
    ): retrofit2.Response<AccessPointDetailDto>

    @POST("api/v1/passwords/{id}/vote")
    suspend fun vote(@Path("id") passwordId: String, @Body body: VoteRequest): VoteResponseDto

    @DELETE("api/v1/passwords/{id}/vote")
    suspend fun retractVote(@Path("id") passwordId: String): retrofit2.Response<Unit>

    @DELETE("api/v1/passwords/{id}")
    suspend fun deleteCandidate(@Path("id") passwordId: String): retrofit2.Response<Unit>

    @DELETE("api/v1/access-points/{id}")
    suspend fun deleteAccessPoint(@Path("id") apId: String): retrofit2.Response<Unit>

    @POST("api/v1/reports")
    suspend fun report(@Body body: ReportRequest): StatusResponse
}
