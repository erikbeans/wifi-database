package com.clickbeans.wifidatabase.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.clickbeans.wifidatabase.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun LocationPickerMap(
    latitude: Double,
    longitude: Double,
    onPinMoved: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mapView by remember { mutableStateOf<MapView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onPause()
            mapView?.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(18.5)
                controller.setCenter(GeoPoint(latitude, longitude))
                overlays.add(
                    Marker(this).apply {
                        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_wifi_map_pin)
                        position = GeoPoint(latitude, longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        isDraggable = true
                        setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                            override fun onMarkerDrag(marker: Marker) {}

                            override fun onMarkerDragEnd(marker: Marker) {
                                onPinMoved(marker.position.latitude, marker.position.longitude)
                            }

                            override fun onMarkerDragStart(marker: Marker) {}
                        })
                    },
                )
                onResume()
            }.also { created -> mapView = created }
        },
        update = { view ->
            val marker = view.overlays.filterIsInstance<Marker>().firstOrNull()
            if (marker != null) {
                val position = marker.position
                if (position.latitude != latitude || position.longitude != longitude) {
                    marker.position = GeoPoint(latitude, longitude)
                    view.invalidate()
                }
            }
        },
    )
}
