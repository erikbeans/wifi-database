package com.clickbeans.wifidatabase.ui.detail

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clickbeans.wifidatabase.AppConfig
import com.clickbeans.wifidatabase.data.api.ApiResult
import com.clickbeans.wifidatabase.data.api.EMAIL_UNVERIFIED
import com.clickbeans.wifidatabase.data.local.DonationPrefs
import com.clickbeans.wifidatabase.data.local.SubmissionMemory
import com.clickbeans.wifidatabase.data.wifi.SuggestionResult
import com.clickbeans.wifidatabase.data.wifi.WifiSuggester
import com.clickbeans.wifidatabase.domain.DonationPolicy
import com.clickbeans.wifidatabase.domain.DeletionPolicy
import com.clickbeans.wifidatabase.domain.model.AccessPointDetail
import com.clickbeans.wifidatabase.domain.model.PasswordCandidate
import com.clickbeans.wifidatabase.domain.repository.AccessPointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val loading: Boolean = true,
    val detail: AccessPointDetail? = null,
    val error: String? = null,
    val unverified: Boolean = false,
    val showUnlikely: Boolean = false,
    val copiedPasswordId: String? = null,
    val deleted: Boolean = false,
    val showDonationCard: Boolean = false,
    val elapsedRealtime: Long = SystemClock.elapsedRealtime(),
    val deleting: Boolean = false,
    val refreshing: Boolean = false,
)

sealed interface DetailEvent {
    data class Toast(val message: String) : DetailEvent
    data object AccessPointDeleted : DetailEvent
    data class CandidateDeleted(val passwordId: String) : DetailEvent
    data class PasswordPruned(val accessPointDeleted: Boolean) : DetailEvent
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accessPointRepository: AccessPointRepository,
    private val submissionMemory: SubmissionMemory,
    private val wifiSuggester: WifiSuggester,
    private val donationPrefs: DonationPrefs,
) : ViewModel() {

    private val apId: String = savedStateHandle["apId"] ?: ""

    var state by mutableStateOf(DetailUiState())
        private set

    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = _events.receiveAsFlow()
    private var loadJob: Job? = null
    private var hasResumed = false

    init {
        load()
    }

    fun isMine(candidate: PasswordCandidate): Boolean = candidate.mine

    fun isMyAccessPoint(): Boolean = state.detail?.mine == true

    fun onResume() {
        // init already loads on first entry; subsequent resumes need fresh permissions.
        if (hasResumed) load()
        hasResumed = true
    }

    fun tick() {
        state = state.copy(elapsedRealtime = SystemClock.elapsedRealtime())
    }

    fun canDelete(candidate: PasswordCandidate): Boolean = !state.loading && !state.refreshing && !state.deleting &&
        DeletionPolicy.canDelete(candidate.mine, candidate.deletionDeadlineElapsedRealtime, state.elapsedRealtime)

    fun canDeleteAccessPoint(): Boolean = state.detail?.let {
        !state.loading && !state.refreshing && !state.deleting &&
            DeletionPolicy.canDelete(it.mine, it.deletionDeadlineElapsedRealtime, state.elapsedRealtime)
    } == true

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val requestStartedAt = SystemClock.elapsedRealtime()
            state = state.copy(loading = state.detail == null, refreshing = true, error = null, unverified = false)
            when (val result = accessPointRepository.detail(apId)) {
                is ApiResult.Success -> {
                    val removed = state.detail?.passwords.orEmpty().map { it.id }.toSet() -
                        result.data.passwords.map { it.id }.toSet()
                    removed.forEach { submissionMemory.forgetPassword(it) }
                    if (removed.isNotEmpty()) _events.send(DetailEvent.PasswordPruned(false))
                    state = state.copy(
                        copiedPasswordId = state.copiedPasswordId?.takeUnless { it in removed },
                        loading = false,
                        refreshing = false,
                        detail = AccessPointDetail(
                            id = result.data.id,
                            ssid = result.data.ssid,
                            bssid = result.data.bssid,
                            security = result.data.security,
                            latitude = result.data.latitude,
                            longitude = result.data.longitude,
                            mine = result.data.mine,
                            deleteUntil = result.data.deleteUntil,
                            deletionDeadlineElapsedRealtime = DeletionPolicy.deadlineElapsedRealtime(
                                result.data.canDelete, result.data.serverTime, result.data.deleteUntil, requestStartedAt,
                            ),
                            passwords = result.data.passwords.map {
                                PasswordCandidate(
                                    id = it.id,
                                    password = it.password,
                                    upVotes = it.upVotes,
                                    downVotes = it.downVotes,
                                    score = it.score,
                                    myVote = it.myVote,
                                    mine = it.mine,
                                    deleteUntil = it.deleteUntil,
                                    deletionDeadlineElapsedRealtime = DeletionPolicy.deadlineElapsedRealtime(
                                        it.canDelete, result.data.serverTime, it.deleteUntil, requestStartedAt,
                                    ),
                                )
                            },
                        ),
                        elapsedRealtime = SystemClock.elapsedRealtime(),
                    )
                }
                is ApiResult.Failure -> {
                    if (result.code == 404) {
                        clearDeletedAccessPoint()
                        _events.send(DetailEvent.AccessPointDeleted)
                        return@launch
                    }
                    // Retain the list on a failed permissions refresh, but fail closed.
                    val retained = state.detail?.let { detail ->
                        detail.copy(
                            deleteUntil = null,
                            deletionDeadlineElapsedRealtime = null,
                            passwords = detail.passwords.map {
                                it.copy(deleteUntil = null, deletionDeadlineElapsedRealtime = null)
                            },
                        )
                    }
                    state = state.copy(
                        loading = false,
                        refreshing = false,
                        detail = retained,
                        error = if (retained == null) result.message else null,
                        unverified = result.code == 403 && result.message == EMAIL_UNVERIFIED,
                    )
                    if (retained != null) _events.send(DetailEvent.Toast(result.message))
                }
            }
        }
    }

    fun toggleUnlikely() {
        state = state.copy(showUnlikely = !state.showUnlikely)
    }

    fun markCopied(passwordId: String) {
        state = state.copy(copiedPasswordId = passwordId)
    }

    fun addToPhone(candidate: PasswordCandidate) {
        val detail = state.detail ?: return
        val result = wifiSuggester.suggest(detail.ssid, candidate.password, detail.security)
        val message = when (result) {
            SuggestionResult.Saved ->
                "Added — approve the system prompt if one appears. Your phone will auto-connect in range."
            SuggestionResult.AlreadySaved ->
                "This network is already saved on your phone."
            SuggestionResult.Disallowed ->
                "Wi-Fi suggestions for WiFi Database are turned off in your system settings."
            SuggestionResult.Unsupported ->
                "This Android version can't save networks — use Copy and add it in Wi-Fi settings."
            is SuggestionResult.Failed -> {
                val reason = listOfNotNull(
                    result.statusCode?.let { "code ${result.statusCode}" },
                    result.detail,
                ).joinToString(": ")
                "Couldn't save the network ($reason) — use Copy and add it in Wi-Fi settings."
            }
        }
        viewModelScope.launch {
            _events.send(DetailEvent.Toast(message))
        }
    }

    fun vote(passwordId: String, value: Int) {
        viewModelScope.launch {
            when (val result = accessPointRepository.vote(passwordId, value)) {
                is ApiResult.Success -> {
                    // An earlier detail request must not restore the removed candidate.
                    loadJob?.cancel()
                    state = state.copy(loading = false, refreshing = false)
                    if (result.data.passwordDeleted) {
                        submissionMemory.forgetPassword(result.data.passwordId)
                        updateListAfterDelete(result.data.passwordId)
                        if (result.data.accessPointDeleted) clearDeletedAccessPoint()
                        _events.send(DetailEvent.PasswordPruned(result.data.accessPointDeleted))
                        if (!result.data.accessPointDeleted) load()
                    } else {
                        updateCandidate(result.data.passwordId) {
                            it.copy(
                                upVotes = result.data.upVotes,
                                downVotes = result.data.downVotes,
                                score = result.data.score,
                                myVote = result.data.myVote,
                            )
                        }
                    }
                }
                is ApiResult.Failure -> {
                    _events.send(DetailEvent.Toast(result.message))
                    if (result.code == 404) load()
                }
            }
        }
    }

    fun confirmWorked(passwordId: String) {
        vote(passwordId, 1)
        donationPrefs.onWorkedConfirm()
        if (DonationPolicy.shouldAsk(
                neverAsk = donationPrefs.neverAsk,
                askCount = donationPrefs.askCount,
                successfulConfirms = donationPrefs.successfulConfirms,
                confirmsSinceLastAsk = donationPrefs.confirmsSinceLastAsk,
            )
        ) {
            state = state.copy(showDonationCard = true)
        }
    }

    fun dismissDonationCard(never: Boolean) {
        donationPrefs.recordAsk(never)
        state = state.copy(showDonationCard = false)
    }

    fun retractVote(passwordId: String) {
        viewModelScope.launch {
            when (val result = accessPointRepository.retractVote(passwordId)) {
                is ApiResult.Success -> load()
                is ApiResult.Failure -> _events.send(DetailEvent.Toast(result.message))
            }
        }
    }

    fun report(passwordId: String?, reason: String) {
        viewModelScope.launch {
            val result = accessPointRepository.report(
                apId = if (passwordId == null) apId else null,
                passwordId = passwordId,
                reason = reason,
            )
            when (result) {
                is ApiResult.Success -> _events.send(DetailEvent.Toast("Report recorded — thanks"))
                is ApiResult.Failure -> _events.send(DetailEvent.Toast(result.message))
            }
        }
    }

    fun deleteCandidate(passwordId: String) {
        tick()
        val candidate = state.detail?.passwords?.find { it.id == passwordId } ?: return
        if (!canDelete(candidate)) return
        state = state.copy(deleting = true)
        viewModelScope.launch {
            when (val result = accessPointRepository.deleteCandidate(passwordId)) {
                is ApiResult.Success -> {
                    submissionMemory.forgetPassword(passwordId)
                    updateListAfterDelete(passwordId)
                    _events.send(DetailEvent.CandidateDeleted(passwordId))
                    load()
                }
                is ApiResult.Failure -> {
                    _events.send(DetailEvent.Toast(result.message))
                    load()
                }
            }
            state = state.copy(deleting = false)
        }
    }

    fun deleteAccessPoint() {
        tick()
        if (!canDeleteAccessPoint()) return
        state = state.copy(deleting = true)
        viewModelScope.launch {
            when (val result = accessPointRepository.deleteAccessPoint(apId)) {
                is ApiResult.Success -> _events.send(DetailEvent.AccessPointDeleted)
                is ApiResult.Failure -> {
                    _events.send(DetailEvent.Toast(result.message))
                    load()
                }
            }
            state = state.copy(deleting = false)
        }
    }

    private fun clearDeletedAccessPoint() {
        state.detail?.passwords?.forEach { submissionMemory.forgetPassword(it.id) }
        submissionMemory.forgetAccessPoint(apId)
        state = state.copy(loading = false, refreshing = false, detail = null, copiedPasswordId = null,
            deleted = true, showDonationCard = false)
    }

    private fun updateListAfterDelete(passwordId: String) {
        val detail = state.detail ?: return
        state = state.copy(
            detail = detail.copy(passwords = detail.passwords.filterNot { it.id == passwordId }),
            copiedPasswordId = state.copiedPasswordId?.takeUnless { it == passwordId },
        )
    }

    private fun updateCandidate(passwordId: String, transform: (PasswordCandidate) -> PasswordCandidate) {
        val detail = state.detail ?: return
        state = state.copy(
            detail = detail.copy(
                passwords = detail.passwords.map { if (it.id == passwordId) transform(it) else it },
            ),
        )
    }

    private fun splitCandidates(): Pair<List<PasswordCandidate>, List<PasswordCandidate>> {
        val detail = state.detail ?: return Pair(emptyList(), emptyList())
        return detail.passwords.partition { it.score >= AppConfig.COLLAPSE_SCORE }
    }

    fun ranked(): List<PasswordCandidate> = splitCandidates().first
    fun unlikely(): List<PasswordCandidate> = splitCandidates().second
}
