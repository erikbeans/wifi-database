package com.clickbeans.wifidatabase

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.clickbeans.wifidatabase.data.api.*
import com.clickbeans.wifidatabase.data.local.DonationPrefs
import com.clickbeans.wifidatabase.data.local.SubmissionMemory
import com.clickbeans.wifidatabase.data.wifi.WifiSuggester
import com.clickbeans.wifidatabase.domain.repository.AccessPointRepository
import com.clickbeans.wifidatabase.ui.detail.DetailEvent
import com.clickbeans.wifidatabase.ui.detail.DetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordPruningTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var memory: SubmissionMemory
    private lateinit var viewModel: DetailViewModel
    private var detail = AccessPointDetailDto(
        id = "network", ssid = "Cafe", latitude = 0.0, longitude = 0.0,
        passwords = listOf(
            PasswordCandidateDto(id = "bad", password = "password-one", score = -9),
            PasswordCandidateDto(id = "good", password = "password-two"),
        ),
    )
    private var missingNetwork = false
    private var outcome = VoteResponseDto(
        passwordId = "bad", upVotes = 0, downVotes = 10, score = -10, myVote = -1,
        passwordDeleted = true,
    )
    private val unusedApi = Proxy.newProxyInstance(
        ApiService::class.java.classLoader, arrayOf(ApiService::class.java),
    ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as ApiService

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication() as Context
        memory = SubmissionMemory(context)
        memory.clear()
        memory.markMyPassword("bad")
        memory.markMyPassword("good")
        memory.markMyAccessPoint("network")
        val api = object : ApiService by unusedApi {
            override suspend fun detail(apId: String): AccessPointDetailDto {
                if (missingNetwork) throw HttpException(Response.error<Any>(404,
                    "{}".toResponseBody("application/json".toMediaType())))
                return detail
            }
            override suspend fun vote(passwordId: String, body: VoteRequest): VoteResponseDto {
                if (outcome.passwordDeleted) {
                    detail = detail.copy(passwords = detail.passwords.filterNot { it.id == passwordId })
                }
                return outcome
            }
            override suspend fun retractVote(passwordId: String): Response<Unit> {
                detail = detail.copy(passwords = detail.passwords.filterNot { it.id == passwordId })
                return Response.success<Unit>(204, null)
            }
        }
        viewModel = DetailViewModel(SavedStateHandle(mapOf("apId" to "network")),
            AccessPointRepository(api), memory, WifiSuggester(context), DonationPrefs(context))
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `vote removes password and clears copied state and local memory`() = runTest(dispatcher) {
        val events = mutableListOf<DetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events.add(it) } }
        advanceUntilIdle()
        viewModel.markCopied("bad")
        viewModel.vote("bad", -1)
        advanceUntilIdle()
        assertEquals(listOf("good"), viewModel.state.detail!!.passwords.map { it.id })
        assertNull(viewModel.state.copiedPasswordId)
        assertFalse(memory.isMyPassword("bad"))
        assertTrue(memory.isMyPassword("good"))
        assertTrue(events.contains(DetailEvent.PasswordPruned(false)))
    }

    @Test
    fun `last password removal requests navigation and clears network memory`() = runTest(dispatcher) {
        val events = mutableListOf<DetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events.add(it) } }
        advanceUntilIdle()
        outcome = outcome.copy(accessPointDeleted = true)
        viewModel.vote("bad", -1)
        advanceUntilIdle()
        assertNull(viewModel.state.detail)
        assertTrue(viewModel.state.deleted)
        assertFalse(memory.isMyAccessPoint("network"))
        assertFalse(memory.isMyPassword("good"))
        assertTrue(events.contains(DetailEvent.PasswordPruned(true)))
    }

    @Test
    fun `missing network on reload requests navigation and refresh`() = runTest(dispatcher) {
        val events = mutableListOf<DetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events.add(it) } }
        advanceUntilIdle()
        missingNetwork = true
        viewModel.load()
        advanceUntilIdle()
        assertNull(viewModel.state.detail)
        assertTrue(events.contains(DetailEvent.AccessPointDeleted))
        assertFalse(memory.isMyAccessPoint("network"))
    }

    @Test
    fun `retract reload removes candidate and its copied state`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.markCopied("bad")
        viewModel.retractVote("bad")
        advanceUntilIdle()
        assertEquals(listOf("good"), viewModel.state.detail!!.passwords.map { it.id })
        assertFalse(memory.isMyPassword("bad"))
        assertNull(viewModel.state.copiedPasswordId)
    }

    @Test
    fun `ordinary vote keeps candidate and updates its score`() = runTest(dispatcher) {
        advanceUntilIdle()
        outcome = outcome.copy(downVotes = 8, score = -8, passwordDeleted = false)
        viewModel.vote("bad", -1)
        advanceUntilIdle()
        assertEquals(-8, viewModel.state.detail!!.passwords.first().score)
        assertTrue(memory.isMyPassword("bad"))
        assertFalse(viewModel.state.deleted)
    }

    @Test
    fun `old server response defaults to no deletion`() {
        val result = Json.decodeFromString<VoteResponseDto>(
            """{"password_id":"bad","up_votes":0,"down_votes":9,"score":-9,"my_vote":-1}""")
        assertFalse(result.passwordDeleted)
        assertFalse(result.accessPointDeleted)
    }
}
