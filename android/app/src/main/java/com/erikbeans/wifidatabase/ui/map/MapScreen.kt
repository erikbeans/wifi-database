package com.erikbeans.wifidatabase.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.navigation.NavController
import com.erikbeans.wifidatabase.domain.model.NearbyAccessPoint
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(navController: NavController, viewModel: MapViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var markers by remember { mutableStateOf(listOf<Marker>()) }
    var myLocation by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    DisposableEffect(Unit) {
        onDispose {
            myLocation?.disableMyLocation()
            mapView?.onPause()
            mapView?.onDetach()
        }
    }

    LaunchedEffect(viewModel.state.pins) {
        val view = mapView ?: return@LaunchedEffect
        markers.forEach { view.overlays.remove(it) }
        val newMarkers = viewModel.state.pins.map { ap ->
            Marker(view).apply {
                id = ap.id
                position = GeoPoint(ap.latitude, ap.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = ap.ssid
                setOnMarkerClickListener { marker, _ ->
                    viewModel.state.pins.firstOrNull { it.id == marker.id }?.let(viewModel::select)
                    true
                }
            }
        }
        newMarkers.forEach { view.overlays.add(it) }
        markers = newMarkers
        view.invalidate()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val view = mapView
        if (view == null) {
            CircularProgressIndicator(Modifier.padding(32.dp))
        } else {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                factory = { ctx ->
                    buildMapView(ctx) { minLat, minLon, maxLat, maxLon ->
                        viewModel.onViewportChanged(minLat, minLon, maxLat, maxLon)
                    }.also { created ->
                        mapView = created
                        if (hasFineLocation(context)) {
                            val overlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), created)
                            overlay.enableMyLocation()
                            created.overlays.add(overlay)
                            myLocation = overlay
                        }
                        created.controller.setZoom(16.5)
                        created.controller.setCenter(GeoPoint(37.5, -122.2))
                        created.onResume()
                    }
                },
                update = { },
            )
            LaunchedEffect(view) {
                delay(600)
                val bounds = view.boundingBox
                viewModel.onViewportChanged(
                    bounds.latSouth, bounds.lonWest, bounds.latNorth, bounds.lonEast,
                )
            }
            if (viewModel.state.error != null) {
                Text(
                    viewModel.state.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(4.dp),
                )
            }
        }
    }

    viewModel.state.selected?.let { selected ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissSelection) {
            ListItem(
                headlineContent = { Text(selected.ssid) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            selected.security,
                            "${selected.passwordCount} password${if (selected.passwordCount == 1) "" else "s"}",
                        ).joinToString(" · ")
                    )
                },
            )
            Button(
                onClick = {
                    viewModel.dismissSelection()
                    navController.navigate("detail/${selected.id}")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Text("View password")
            }
        }
    }
}

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun buildMapView(
    context: Context,
    onViewport: (Double, Double, Double, Double) -> Unit,
): MapView = MapView(context).apply {
    setTileSource(TileSourceFactory.MAPNIK)
    setMultiTouchControls(true)
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    addMapListener(object : MapListener {
        override fun onScroll(event: ScrollEvent?): Boolean {
            val bounds = boundingBox
            onViewport(bounds.latSouth, bounds.lonWest, bounds.latNorth, bounds.lonEast)
            return false
        }

        override fun onZoom(event: ZoomEvent?): Boolean {
            val bounds = boundingBox
            onViewport(bounds.latSouth, bounds.lonWest, bounds.latNorth, bounds.lonEast)
            return false
        }
    })
}
