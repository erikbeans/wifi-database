package com.clickbeans.wifidatabase.ui.nearby

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import com.clickbeans.wifidatabase.ui.RefreshAfterDetail
import com.clickbeans.wifidatabase.domain.model.NearbyRow
import com.clickbeans.wifidatabase.ui.shareRoute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(navController: NavController, viewModel: NearbyViewModel = hiltViewModel()) {
    NearbyScreen(navController, viewModel.state, viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NearbyScreen(
    navController: NavController,
    state: NearbyUiState,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current

    fun hasFineLocation(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(hasFineLocation()) }

    fun onPermissionStateMaybeChanged() {
        val grantedNow = hasFineLocation()
        hasPermission = grantedNow
        if (grantedNow) onRefresh()
    }

    RefreshAfterDetail { if (hasPermission) onRefresh() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        onPermissionStateMaybeChanged()
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // The navigation entry resumes on tab return as well as app foregrounding.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onPermissionStateMaybeChanged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Database") },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(
                onClick = { navController.navigate(shareRoute(null, null, null)) },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Share a password")
            }
        },
    ) { padding ->
        val wifiManager = context.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiOff = wifiManager?.isWifiEnabled != true
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { if (hasPermission) onRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        Modifier
                            .padding(16.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                }
                state.scanTimestampMillis?.let { timestamp ->
                    Text(
                        "Scan results as of ${formatTime(timestamp)} — pull down to rescan",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                state.error?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (wifiOff) {
                    Text(
                        "WiFi is turned off — enable it to see and share nearby networks.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (state.unverified) {
                    UnverifiedBanner(Modifier.padding(horizontal = 16.dp))
                }
                if (!hasPermission) {
                    PermissionRationale(
                        onRequest = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                            )
                        },
                    )
                } else {
                    NearbyList(
                        state = state,
                        onRefresh = onRefresh,
                        onViewPasswords = { row ->
                            row.accessPoint?.let { navController.navigate("detail/${it.id}") }
                        },
                        onShareScanned = { row ->
                            navController.navigate(
                                shareRoute(
                                    row.scanned?.ssid,
                                    row.scanned?.bssid,
                                    row.scanned?.security,
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyList(
    state: NearbyUiState,
    onRefresh: () -> Unit,
    onViewPasswords: (NearbyRow) -> Unit,
    onShareScanned: (NearbyRow) -> Unit,
) {
    if (state.rows.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Wifi, contentDescription = null)
            Spacer(Modifier.height(12.dp))
            Text(
                "No networks matched yet.\nPull down or tap Refresh to look again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
                Text("Refresh")
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
    ) {
        items(state.rows) { row ->
            ListItem(
                headlineContent = { Text(row.title) },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (row.subtitle != null) Text(row.subtitle)
                        row.distanceM?.let { Text("· %.0f m".format(it)) }
                    }
                },
                trailingContent = {
                    if (row.passwordAvailable) {
                        AssistChip(
                            onClick = { onViewPasswords(row) },
                            label = {
                                Text(
                                    row.bestScore?.let { "Password · score $it" } ?: "Password available"
                                )
                            },
                        )
                    } else {
                        Text(
                            "Not in database",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingContent = {
                    Icon(
                        if (row.passwordAvailable) Icons.Outlined.Wifi else Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = if (row.passwordAvailable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().then(
                    if (row.scanned != null) {
                        Modifier.clickable(onClickLabel = "Add password for ${row.title}") {
                            onShareScanned(row)
                        }
                    } else {
                        Modifier
                    }
                ),
            )
        }
    }
}

@Composable
private fun UnverifiedBanner(modifier: Modifier = Modifier) {
    Text(
        "Your email isn't verified yet — you can browse, but you need to verify " +
            "to reveal, share, vote, or report. Open the verification link from your inbox.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun PermissionRationale(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null)
        Spacer(Modifier.height(12.dp))
        Text(
            "WiFi Database needs your location permission to scan nearby networks " +
                "and find access points around you. Android requires location access " +
                "for WiFi scan results — your location is only used to power this app. " +
                "If you chose \"Approximate\" location, switch to \"Precise\" to make scans work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) {
            Text("Grant location permission")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text("Open app settings")
        }
    }
}

private fun formatTime(timestampMillis: Long): String = Instant.ofEpochMilli(timestampMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("h:mm a"))
