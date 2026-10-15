package com.clickbeans.wifidatabase.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clickbeans.wifidatabase.data.api.ApiResult
import com.clickbeans.wifidatabase.data.location.LocationProvider
import com.clickbeans.wifidatabase.domain.model.NearbyAccessPoint
import com.clickbeans.wifidatabase.domain.repository.AccessPointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val pins: List<NearbyAccessPoint> = emptyList(),
    val selected: NearbyAccessPoint? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val accessPointRepository: AccessPointRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    var state by mutableStateOf(MapUiState())
        private set

    private var fetchJob: Job? = null

    suspend fun lastKnownLocation(): Pair<Double, Double>? {
        val location = locationProvider.awaitFix(timeoutMillis = 4000)
        return location?.let { it.latitude to it.longitude }
    }

    fun onViewportChanged(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            delay(300)
            fetch(minLat, minLon, maxLat, maxLon)
        }
    }

    fun select(ap: NearbyAccessPoint) {
        state = state.copy(selected = ap)
    }

    fun dismissSelection() {
        state = state.copy(selected = null)
    }

    private suspend fun fetch(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        state = state.copy(loading = true, error = null)
        when (val result = accessPointRepository.inBbox(minLat, minLon, maxLat, maxLon)) {
            is ApiResult.Success -> {
                state = state.copy(
                    loading = false,
                    pins = result.data.map { dto ->
                        NearbyAccessPoint(
                            id = dto.id,
                            ssid = dto.ssid,
                            bssid = dto.bssid,
                            security = dto.security,
                            latitude = dto.latitude,
                            longitude = dto.longitude,
                            distanceM = dto.distanceM,
                            passwordCount = dto.passwordCount,
                            bestScore = dto.bestScore,
                        )
                    },
                )
            }
            is ApiResult.Failure -> {
                state = state.copy(loading = false, error = result.message)
            }
        }
    }
}
