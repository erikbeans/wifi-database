package com.clickbeans.wifidatabase.ui.detail

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
import com.clickbeans.wifidatabase.domain.model.AccessPointDetail
import com.clickbeans.wifidatabase.domain.model.PasswordCandidate
import com.clickbeans.wifidatabase.domain.repository.AccessPointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
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
)

sealed interface DetailEvent {
    data class Toast(val message: String) : DetailEvent
    data object AccessPointDeleted : DetailEvent
    data class CandidateDeleted(val passwordId: String) : DetailEvent
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

    init {
        load()
    }

    fun isMine(candidate: PasswordCandidate): Boolean =
        candidate.mine || submissionMemory.isMyPassword(candidate.id)

    fun isMyAccessPoint(): Boolean =
        state.detail?.mine == true ||
            state.detail?.let { submissionMemory.isMyAccessPoint(it.id) } == true

    fun load() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, unverified = false)
            when (val result = accessPointRepository.detail(apId)) {
                is ApiResult.Success -> state = state.copy(
                    loading = false,
                    detail = AccessPointDetail(
                        id = result.data.id,
                        ssid = result.data.ssid,
                        bssid = result.data.bssid,
                        security = result.data.security,
                        latitude = result.data.latitude,
                        longitude = result.data.longitude,
                        mine = result.data.mine,
                        passwords = result.data.passwords.map {
                            PasswordCandidate(
                                id = it.id,
                                password = it.password,
                                upVotes = it.upVotes,
                                downVotes = it.downVotes,
                                score = it.score,
                                myVote = it.myVote,
                                mine = it.mine,
                            )
                        },
                    ),
                )
                is ApiResult.Failure -> state = state.copy(
                    loading = false,
                    error = result.message,
                    unverified = result.code == 403 && result.message == EMAIL_UNVERIFIED,
                )
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
                is ApiResult.Success -> updateCandidate(result.data.passwordId) {
                    it.copy(
                        upVotes = result.data.upVotes,
                        downVotes = result.data.downVotes,
                        score = result.data.score,
                        myVote = result.data.myVote,
                    )
                }
                is ApiResult.Failure -> _events.send(DetailEvent.Toast(result.message))
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
        viewModelScope.launch {
            when (val result = accessPointRepository.deleteCandidate(passwordId)) {
                is ApiResult.Success -> {
                    submissionMemory.forgetPassword(passwordId)
                    updateListAfterDelete(passwordId)
                    _events.send(DetailEvent.CandidateDeleted(passwordId))
                }
                is ApiResult.Failure -> _events.send(DetailEvent.Toast(result.message))
            }
        }
    }

    fun deleteAccessPoint() {
        viewModelScope.launch {
            when (val result = accessPointRepository.deleteAccessPoint(apId)) {
                is ApiResult.Success -> _events.send(DetailEvent.AccessPointDeleted)
                is ApiResult.Failure -> _events.send(DetailEvent.Toast(result.message))
            }
        }
    }

    private fun updateListAfterDelete(passwordId: String) {
        val detail = state.detail ?: return
        state = state.copy(
            detail = detail.copy(passwords = detail.passwords.filterNot { it.id == passwordId }),
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
