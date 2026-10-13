package com.erikbeans.wifidatabase.ui.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.erikbeans.wifidatabase.data.api.AccessPointCreateRequest
import com.erikbeans.wifidatabase.data.api.ApiResult
import com.erikbeans.wifidatabase.data.api.EMAIL_UNVERIFIED
import com.erikbeans.wifidatabase.data.local.SubmissionMemory
import com.erikbeans.wifidatabase.data.location.LocationProvider
import com.erikbeans.wifidatabase.data.wifi.WifiScanner
import com.erikbeans.wifidatabase.domain.model.ScannedNetwork
import com.erikbeans.wifidatabase.domain.repository.AccessPointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShareUiState(
    val networks: List<ScannedNetwork> = emptyList(),
    val selected: ScannedNetwork? = null,
    val dropdownOpen: Boolean = false,
    val password: String = "",
    val showPassword: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val consentConfirmed: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val unverified: Boolean = false,
)

sealed interface ShareEvent {
    data object Shared : ShareEvent
    data class DuplicateUpvote(val apId: String) : ShareEvent
}

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val accessPointRepository: AccessPointRepository,
    private val locationProvider: LocationProvider,
    private val wifiScanner: WifiScanner,
    private val submissionMemory: SubmissionMemory,
) : ViewModel() {

    var state by mutableStateOf(ShareUiState())
        private set

    private val _events = Channel<ShareEvent>(Channel.BUFFERED)
    val events: Flow<ShareEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            wifiScanner.scans.collect { snapshot ->
                applyScan(snapshot.networks)
            }
        }
        applyScan(wifiScanner.currentResults().networks)
        viewModelScope.launch {
            val location = locationProvider.awaitFix()
            state = state.copy(latitude = location?.latitude, longitude = location?.longitude)
        }
    }

    fun preselect(ssid: String) {
        if (ssid.isBlank()) return
        state.networks.firstOrNull { it.ssid == ssid }?.let { network ->
            state = state.copy(selected = network)
        }
    }

    private fun applyScan(networks: List<ScannedNetwork>) {
        val selected = state.selected?.let { sel ->
            networks.firstOrNull { it.ssid == sel.ssid } ?: sel
        }
        state = state.copy(networks = networks, selected = selected)
    }

    fun selectNetwork(network: ScannedNetwork) {
        state = state.copy(selected = network, dropdownOpen = false)
    }

    fun openDropdown() {
        if (state.networks.isNotEmpty()) {
            state = state.copy(dropdownOpen = true)
        }
    }

    fun dismissDropdown() {
        state = state.copy(dropdownOpen = false)
    }

    fun rescan() {
        val started = wifiScanner.startScan()
        applyScan(wifiScanner.currentResults().networks)
        if (!started) {
            state = state.copy(error = "Scan throttled by Android — wait a moment and try again.")
        }
    }

    fun updatePassword(value: String) {
        state = state.copy(password = value)
    }

    fun toggleShowPassword() {
        state = state.copy(showPassword = !state.showPassword)
    }

    fun toggleConsent() {
        state = state.copy(consentConfirmed = !state.consentConfirmed)
    }

    fun submit() {
        val current = state
        val selected = current.selected ?: return
        if (current.submitting || current.password.isBlank()) return
        val latitude = current.latitude
        val longitude = current.longitude
        if (latitude == null || longitude == null) {
            state = current.copy(error = "Waiting for a GPS fix — stand by a moment and try again.")
            viewModelScope.launch {
                val location = locationProvider.awaitFix()
                state = state.copy(
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    error = if (location != null) null else state.error,
                )
            }
            return
        }
        viewModelScope.launch {
            state = state.copy(submitting = true, error = null, unverified = false)
            val request = AccessPointCreateRequest(
                ssid = selected.ssid,
                password = current.password,
                bssid = selected.bssid,
                security = selected.security,
                latitude = latitude,
                longitude = longitude,
                consentConfirmed = current.consentConfirmed,
            )
            when (val result = accessPointRepository.create(request)) {
                is ApiResult.Success -> {
                    val payload = result.data
                    if (payload.statusCode == 201) {
                        submissionMemory.markMyAccessPoint(payload.detail.id)
                    }
                    if (payload.statusCode == 201 || payload.duplicatePassword == false) {
                        payload.detail.passwords
                            .firstOrNull { it.password == current.password }
                            ?.let { submissionMemory.markMyPassword(it.id) }
                    }
                    if (payload.duplicatePassword == true) {
                        _events.send(ShareEvent.DuplicateUpvote(payload.detail.id))
                    } else {
                        _events.send(ShareEvent.Shared)
                    }
                }
                is ApiResult.Failure -> {
                    state = state.copy(
                        submitting = false,
                        error = result.message,
                        unverified = result.code == 403 && result.message == EMAIL_UNVERIFIED,
                    )
                }
            }
        }
    }
}
