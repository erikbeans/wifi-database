package com.clickbeans.wifidatabase

import com.clickbeans.wifidatabase.data.auth.SessionManager
import com.clickbeans.wifidatabase.data.auth.TokenManager
import com.clickbeans.wifidatabase.data.auth.TokenStore
import com.clickbeans.wifidatabase.data.api.AuthApi
import com.clickbeans.wifidatabase.data.api.TokenAuthenticator
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private class FakeTokenStore : TokenStore {
    private var refresh: String? = null
    private var mail: String? = null

    override fun save(refreshToken: String, email: String?) {
        refresh = refreshToken
        if (email != null) mail = email
    }

    override fun refreshToken(): String? = refresh

    override fun email(): String? = mail

    override fun clear() {
        refresh = null
        mail = null
    }
}

class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var store: FakeTokenStore
    private lateinit var manager: TokenManager
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        val authApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)
        store = FakeTokenStore()
        manager = TokenManager(authApi, store, SessionManager())
        manager.saveTokens("access-1", "refresh-1", "user@example.com")
        client = OkHttpClient.Builder()
            .authenticator(TokenAuthenticator(manager))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `401 triggers refresh and retry with the new access token`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"access-2","refresh_token":"refresh-2","token_type":"bearer"}""")
                .addHeader("Content-Type", "application/json")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(
            Request.Builder()
                .url(server.url("/protected"))
                .header("Authorization", "Bearer access-1")
                .build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals("refresh-2", store.refreshToken())

        val first = server.takeRequest()
        assertEquals("access-1", first.getHeader("Authorization")?.removePrefix("Bearer "))
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("refresh-1"))
        val retry = server.takeRequest()
        assertEquals("access-2", retry.getHeader("Authorization")?.removePrefix("Bearer "))
    }

    @Test
    fun `failed refresh gives up and clears the session`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(400))

        val response = client.newCall(
            Request.Builder()
                .url(server.url("/protected"))
                .header("Authorization", "Bearer access-1")
                .build()
        ).execute()

        assertEquals(401, response.code)
        assertNull(store.refreshToken())
        assertFalse(manager.currentAccess() != null)
    }
}
