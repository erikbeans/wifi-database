package com.clickbeans.wifidatabase

import com.clickbeans.wifidatabase.data.api.ApiResult
import com.clickbeans.wifidatabase.data.api.ApiService
import com.clickbeans.wifidatabase.data.api.EMAIL_UNVERIFIED
import com.clickbeans.wifidatabase.data.api.apiCall
import com.clickbeans.wifidatabase.domain.DeletionPolicy
import com.clickbeans.wifidatabase.domain.repository.AccessPointRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class DeletionRequestTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: AccessPointRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(ApiService::class.java)
        repository = AccessPointRepository(api)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `rejected password and access point deletes return failures with server explanation`() = runBlocking {
        for (delete in listOf<suspend () -> ApiResult<Unit>>(
            { repository.deleteCandidate("pw") }, { repository.deleteAccessPoint("ap") },
        )) {
            server.enqueue(MockResponse().setResponseCode(403)
                .setBody("""{"detail":"${DeletionPolicy.WINDOW_ENDED}"}"""))
            val result = delete()
            assertTrue(result is ApiResult.Failure)
            result as ApiResult.Failure
            assertEquals(403, result.code)
            assertEquals(DeletionPolicy.WINDOW_ENDED, result.message)
            assertEquals("DELETE", server.takeRequest().method)
        }
    }

    @Test
    fun `only successful responses report deleted`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(repository.deleteCandidate("pw") is ApiResult.Success)
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(repository.deleteAccessPoint("ap") is ApiResult.Failure)
    }

    @Test
    fun `error parsing preserves message responses and unverified email handling`() {
        for (body in listOf("""{"message":"$EMAIL_UNVERIFIED"}""", """{"detail":"$EMAIL_UNVERIFIED"}""")) {
            val response = Response.error<Unit>(403, okhttp3.ResponseBody.create("application/json".toMediaType(), body))
            val failure = apiCall<Unit> { throw HttpException(response) } as ApiResult.Failure
            assertEquals(EMAIL_UNVERIFIED, failure.message)
        }
    }
}
