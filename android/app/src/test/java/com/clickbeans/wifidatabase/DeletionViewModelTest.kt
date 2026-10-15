package com.clickbeans.wifidatabase

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.clickbeans.wifidatabase.data.api.*
import com.clickbeans.wifidatabase.data.local.DonationPrefs
import com.clickbeans.wifidatabase.data.local.SubmissionMemory
import com.clickbeans.wifidatabase.data.wifi.WifiSuggester
import com.clickbeans.wifidatabase.domain.DeletionPolicy
import com.clickbeans.wifidatabase.domain.repository.AccessPointRepository
import com.clickbeans.wifidatabase.ui.detail.DetailEvent
import com.clickbeans.wifidatabase.ui.detail.DetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import retrofit2.Response
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeletionViewModelTest {
    private val api = DeletionApi()
    private val store = ViewModelStore()
    private val eventScope = CoroutineScope(Dispatchers.Main)
    private val events = mutableListOf<DetailEvent>()
    private lateinit var viewModel: DetailViewModel
    private lateinit var memory: SubmissionMemory

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        memory = SubmissionMemory(context)
        viewModel = DetailViewModel(SavedStateHandle(mapOf("apId" to "ap")),
            AccessPointRepository(api), memory, WifiSuggester(context), DonationPrefs(context))
        store.put("detail", viewModel)
        eventScope.launch { viewModel.events.collect { events.add(it) } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        store.clear()
        eventScope.cancel()
    }

    @Test
    fun `rejected deletes keep passwords refresh permissions and never emit success`() {
        for (wholeAccessPoint in listOf(false, true)) {
            api.detailValue = freshDetail()
            viewModel.load()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(viewModel.canDeleteAccessPoint())
            api.detailValue = freshDetail().copy(canDelete = false,
                passwords = freshDetail().passwords.map { it.copy(canDelete = false) })
            val before = api.detailCalls
            if (wholeAccessPoint) viewModel.deleteAccessPoint() else viewModel.deleteCandidate("pw")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before + 1, api.detailCalls)
            assertEquals("pw", viewModel.state.detail!!.passwords.single().id)
            assertFalse(viewModel.canDeleteAccessPoint())
            assertFalse(viewModel.canDelete(viewModel.state.detail!!.passwords.single()))
            assertFalse(events.any { it is DetailEvent.CandidateDeleted || it is DetailEvent.AccessPointDeleted })
            assertTrue(events.contains(DetailEvent.Toast(DeletionPolicy.WINDOW_ENDED)))
        }
    }

    @Test
    fun `expiry while open disables both deletion methods and refreshed detail stays expired`() {
        assertTrue(viewModel.canDeleteAccessPoint())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(15))
        viewModel.tick()
        assertFalse(viewModel.canDeleteAccessPoint())
        assertFalse(viewModel.canDelete(viewModel.state.detail!!.passwords.single()))
        viewModel.deleteCandidate("pw")
        viewModel.deleteAccessPoint()
        assertEquals(0, api.deleteCalls)
        api.detailValue = freshDetail().copy(serverTime = "2026-10-15T18:15:00Z", canDelete = false,
            passwords = freshDetail().passwords.map { it.copy(canDelete = false) })
        viewModel.load()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(viewModel.canDeleteAccessPoint())
    }

    @Test
    fun `local submission memory never grants ownership or deletion`() {
        memory.markMyPassword("pw")
        memory.markMyAccessPoint("ap")
        api.detailValue = freshDetail().copy(mine = false,
            passwords = freshDetail().passwords.map { it.copy(mine = false) })
        viewModel.load()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(viewModel.isMyAccessPoint())
        assertFalse(viewModel.isMine(viewModel.state.detail!!.passwords.single()))
        assertFalse(viewModel.canDeleteAccessPoint())
    }

    @Test
    fun `resume refreshes server permissions without duplicating first entry load`() {
        val initialCalls = api.detailCalls
        viewModel.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initialCalls, api.detailCalls)
        api.detailValue = freshDetail().copy(canDelete = false)
        viewModel.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initialCalls + 1, api.detailCalls)
        assertFalse(viewModel.canDeleteAccessPoint())
    }

    companion object {
        private fun freshDetail() = AccessPointDetailDto(
            id = "ap", ssid = "Cafe", latitude = 0.0, longitude = 0.0, mine = true,
            serverTime = "2026-10-15T18:00:00Z", canDelete = true, deleteUntil = "2026-10-15T18:15:00Z",
            passwords = listOf(PasswordCandidateDto(id = "pw", password = "password123", mine = true,
                canDelete = true, deleteUntil = "2026-10-15T18:15:00Z")),
        )
    }

    private class DeletionApi : ApiService {
        var detailValue = freshDetail()
        var detailCalls = 0
        var deleteCalls = 0
        override suspend fun detail(apId: String): AccessPointDetailDto {
            detailCalls++
            return detailValue
        }
        private fun rejected(): Response<Unit> {
            deleteCalls++
            return Response.error(403, """{"detail":"${DeletionPolicy.WINDOW_ENDED}"}"""
                .toResponseBody("application/json".toMediaType()))
        }
        override suspend fun deleteCandidate(passwordId: String) = rejected()
        override suspend fun deleteAccessPoint(apId: String) = rejected()
        override suspend fun near(latitude: Double, longitude: Double, radiusM: Int?, limit: Int): List<AccessPointSummaryDto> = error("unused")
        override suspend fun search(query: String, limit: Int): List<AccessPointSummaryDto> = error("unused")
        override suspend fun inBbox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, limit: Int): List<AccessPointSummaryDto> = error("unused")
        override suspend fun createAccessPoint(body: AccessPointCreateRequest): Response<AccessPointDetailDto> = error("unused")
        override suspend fun addCandidate(apId: String, body: PasswordCandidateCreateRequest): Response<AccessPointDetailDto> = error("unused")
        override suspend fun vote(passwordId: String, body: VoteRequest): VoteResponseDto = error("unused")
        override suspend fun retractVote(passwordId: String): Response<Unit> = error("unused")
        override suspend fun report(body: ReportRequest): StatusResponse = error("unused")
    }
}
