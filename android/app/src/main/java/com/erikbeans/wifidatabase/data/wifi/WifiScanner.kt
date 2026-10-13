package com.erikbeans.wifidatabase.data.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.erikbeans.wifidatabase.domain.model.ScannedNetwork
import com.erikbeans.wifidatabase.domain.model.SecurityTypes
import com.erikbeans.wifidatabase.domain.model.dedupeBySsid
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ScanSnapshot(
    val networks: List<ScannedNetwork>,
    val timestampMillis: Long,
)

@Singleton
class WifiScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun startScan(): Boolean {
        if (!hasLocationPermission() || wifiManager == null) return false
        return try {
            wifiManager.startScan()
        } catch (_: SecurityException) {
            false
        }
    }

    fun currentResults(): ScanSnapshot {
        val results = if (hasLocationPermission()) {
            try {
                wifiManager?.scanResults.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return SnapshotFactory.from(results)
    }

    val scans: Flow<ScanSnapshot> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
                    if (success) {
                        trySend(currentResults())
                    }
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}

internal object SnapshotFactory {
    fun from(results: List<ScanResult>): ScanSnapshot = ScanSnapshot(
        networks = results
            .filter { it.SSID.isNotBlank() }
            .map {
                ScannedNetwork(
                    ssid = it.SSID,
                    bssid = it.BSSID?.lowercase(),
                    security = SecurityTypes.fromCapabilities(it.capabilities),
                )
            }
            .dedupeBySsid(),
        timestampMillis = System.currentTimeMillis(),
    )
}
