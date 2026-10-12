package com.erikbeans.wifidatabase.ui.nearby

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.erikbeans.wifidatabase.data.api.ApiResult
import com.erikbeans.wifidatabase.data.location.LocationProvider
import com.erikbeans.wifidatabase.data.wifi.ScanSnapshot
import com.erikbeans.wifidatabase.data.wifi.WifiScanner
import com.erikbeans.wifidatabase.domain.NearbyMatcher
import com.erikbeans.wifidatabase.domain.model.NearbyAccessPoint
import com.erikbeans.wifidatabase.domain.model.NearbyRow
import com.erikbeans.wifidatabase.domain.repository.AccessPointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NearbyUiState(
    val loading: Boolean = false,
    val rows: List<NearbyRow> = emptyList(),
    val scanTimestampMillis: Long? = null,
    val error: String? = null,
    val unverified: Boolean = false,
)

@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val accessPointRepository: AccessPointRepository,
    private val wifiScanner: WifiScanner,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    var state by mutableStateOf(NearbyUiState())
        private set

    private var latestScan: ScanSnapshot? = null
    private var latestDatabase: List<NearbyAccessPoint> = emptyList()

    init {
        viewModelScope.launch {
            wifiScanner.scans.collect { snapshot ->
                latestScan = snapshot
                rebuild()
            }
        }
    }

    fun refresh() {
        if (state.loading) return
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, unverified = false)
            val location = locationProvider.awaitFix()
            if (location == null) {
                state = state.copy(loading = false, error = "Couldn't get a GPS fix — try again outside.")
                return@launch
            }
            wifiScanner.startScan()
            when (val result = accessPointRepository.near(location.latitude, location.longitude, null)) {
                is ApiResult.Success -> {
                    latestDatabase = result.data.map { it.toNearbyAccessPoint() }
                    if (latestScan == null) {
                        latestScan = wifiScanner.currentResults()
                    }
                    rebuild()
                    state = state.copy(loading = false)
                }
                is ApiResult.Failure -> {
                    state = state.copy(
                        loading = false,
                        error = result.message,
                        unverified = result.code == 403 && result.message == com.erikbeans.wifidatabase.data.api.EMAIL_UNVERIFIED,
                    )
                }
            }
        }
    }

    private fun rebuild() {
        val scan = latestScan
        state = state.copy(
            rows = NearbyMatcher.merge(scan?.networks.orEmpty(), latestDatabase),
            scanTimestampMillis = scan?.timestampMillis,
        )
    }
}

private fun com.erikbeans.wifidatabase.data.api.AccessPointSummaryDto.toNearbyAccessPoint() = NearbyAccessPoint(
    id = id,
    ssid = ssid,
    bssid = bssid,
    security = security,
    latitude = latitude,
    longitude = longitude,
    distanceM = distanceM,
    passwordCount = passwordCount,
    bestScore = bestScore,
)
