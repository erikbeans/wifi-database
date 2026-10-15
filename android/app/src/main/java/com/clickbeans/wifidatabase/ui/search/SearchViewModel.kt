package com.clickbeans.wifidatabase.ui.search

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

data class SearchUiState(
    val query: String = "",
    val nearMe: Boolean = false,
    val loading: Boolean = false,
    val results: List<NearbyAccessPoint> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val accessPointRepository: AccessPointRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    var state by mutableStateOf(SearchUiState())
        private set

    private var searchJob: Job? = null

    fun refresh() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (state.nearMe) performNearMe()
            else if (state.query.isNotBlank()) performSearch(state.query)
        }
    }

    fun updateQuery(value: String) {
        state = state.copy(query = value, nearMe = false)
        searchJob?.cancel()
        if (value.isBlank()) {
            state = state.copy(results = emptyList(), error = null)
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            performSearch(value)
        }
    }

    fun toggleNearMe() {
        val target = !state.nearMe
        state = state.copy(nearMe = target, query = if (target) "" else state.query)
        if (target) {
            viewModelScope.launch { performNearMe() }
        } else {
            state = state.copy(results = emptyList())
        }
    }

    private suspend fun performNearMe() {
        state = state.copy(loading = true, error = null)
        val location = locationProvider.awaitFix()
        if (location == null) {
            state = state.copy(loading = false, error = "Couldn't get a GPS fix — try again outside.")
            return
        }
        when (val result = accessPointRepository.near(location.latitude, location.longitude, null)) {
            is ApiResult.Success -> {
                state = state.copy(
                    loading = false,
                    results = result.data.map { dto ->
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
            is ApiResult.Failure -> state = state.copy(loading = false, error = result.message)
        }
    }

    private suspend fun performSearch(query: String) {
        state = state.copy(loading = true, error = null)
        when (val result = accessPointRepository.search(query)) {
            is ApiResult.Success -> {
                state = state.copy(
                    loading = false,
                    results = result.data.map { dto ->
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
            is ApiResult.Failure -> state = state.copy(loading = false, error = result.message)
        }
    }
}
