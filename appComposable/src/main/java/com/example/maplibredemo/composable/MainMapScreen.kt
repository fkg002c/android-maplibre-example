package com.example.maplibredemo.composable

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineDefault
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.PI
import kotlin.math.cos

@Composable
fun MainMapScreen() {
    val context = LocalContext.current
    val apiKey = BuildConfig.YANDEX_MAPS_API_KEY
    val cacheManager = remember { MapCacheManager(context) }
    var downloadProgress by remember { mutableStateOf("") }

    val initialStatusMessage = stringResource(R.string.locating)
    val statusMessage = remember { mutableStateOf<String?>(initialStatusMessage) }
    val locationSession = remember { MapLocationSession(context, statusMessage) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> locationSession.onPermissionResult(grants) }
    locationSession.permissionLauncher = permissionLauncher

    DisposableEffect(locationSession) {
        onDispose { locationSession.stopLocationUpdates() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            YandexMapLibreView(
                apiKey = apiKey,
                modifier = Modifier.fillMaxSize(),
                onMapReady = { map -> locationSession.onMapReady(map) }
            )

            statusMessage.value?.let { message ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color(0x88000000))
                        .padding(8.dp)
                ) {
                    Text(
                        text = message,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (downloadProgress.isNotEmpty()) {
                    Text(text = downloadProgress)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Button(onClick = {
                    val nnBounds = LatLngBounds.Builder()
                        .include(LatLng(56.40, 43.84))
                        .include(LatLng(55.20, 44.24))
                        .build()

                    downloadProgress = "The cache download started..."

                    cacheManager.cacheRegion(
                        apiKey = apiKey,
                        regionName = "Nizhny Novgorod",
                        bounds = nnBounds,
                        minZoom = 11.0,
                        maxZoom = 13.0,
                        onProgress = { percent ->
                            downloadProgress = "Downloaded: ${String.format("%.1f", percent)}%"
                        },
                        onSuccess = {
                            downloadProgress = "The region saved in cache successfully"
                        },
                        onError = { error ->
                            downloadProgress = "Cache error: $error"
                        }
                    )
                }) {
                    Text("caching")
                }
            }
        }
    }
}

/**
 * Drives location search, live location tracking, camera framing, and the static POI markers on
 * top of the map/style/tile-cache that [YandexMapLibreView] already sets up. Mirrors the legacy
 * `MapLibreSession` behavior from [MapScreen], adapted to a plain [Context] since map + style
 * creation stay owned by [YandexMapLibreView].
 */
private class MapLocationSession(
    private val context: Context,
    private val statusMessage: MutableState<String?>
) {
    lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var maplibreMap: MapLibreMap? = null
    private var locationEngine: LocationEngine? = null
    private var hasCenteredCamera = false
    private var currentLocationMarker: Marker? = null
    private var accuracySource: GeoJsonSource? = null
    private var poisAdded = false

    private val locationCallback = object : LocationEngineCallback<LocationEngineResult> {
        override fun onSuccess(result: LocationEngineResult) {
            result.lastLocation?.let { onLocationUpdate(it) }
        }

        override fun onFailure(exception: Exception) {
            if (!hasCenteredCamera) {
                statusMessage.value = context.getString(R.string.location_permission_denied)
                showOnMap(FALLBACK_LOCATION)
            }
        }
    }

    fun onMapReady(map: MapLibreMap) {
        maplibreMap = map
        map.style?.let { addAccuracyCircleLayer(it) }
        requestLocationPermissionsAndFetch()
    }

    fun onPermissionResult(grants: Map<String, Boolean>) {
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startLocationUpdates()
        } else {
            statusMessage.value = context.getString(R.string.location_permission_denied)
            showOnMap(FALLBACK_LOCATION)
        }
    }

    fun stopLocationUpdates() {
        locationEngine?.removeLocationUpdates(locationCallback)
    }

    private fun requestLocationPermissionsAndFetch() {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            startLocationUpdates()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        statusMessage.value = context.getString(R.string.locating)

        val engine = LocationEngineDefault.getDefaultLocationEngine(context)
        locationEngine = engine
        engine.getLastLocation(locationCallback)

        val request = LocationEngineRequest.Builder(UPDATE_INTERVAL_MS)
            .setFastestInterval(FASTEST_INTERVAL_MS)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .build()
        engine.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun onLocationUpdate(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        statusMessage.value = null

        updateCurrentLocationMarker(latLng)
        updateAccuracyCircle(latLng, location.accuracy)

        if (!hasCenteredCamera) {
            hasCenteredCamera = true
            showOnMap(latLng)
        }
    }

    private fun showOnMap(center: LatLng) {
        val map = maplibreMap ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 11.5), 1200)
        map.style?.let { addPointsOfInterest(map, center) }
    }

    private fun addAccuracyCircleLayer(style: org.maplibre.android.maps.Style) {
        val source = GeoJsonSource(ACCURACY_SOURCE_ID, EMPTY_POINT_GEOJSON)
        accuracySource = source
        style.addSource(source)

        val layer = CircleLayer(ACCURACY_LAYER_ID, ACCURACY_SOURCE_ID).apply {
            setProperties(
                PropertyFactory.circleColor(ContextCompat.getColor(context, R.color.location_blue)),
                PropertyFactory.circleOpacity(0.3f),
                PropertyFactory.circleStrokeColor(ContextCompat.getColor(context, R.color.location_blue)),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeOpacity(1f)
            )
        }
        style.addLayer(layer)
    }

    private fun updateCurrentLocationMarker(center: LatLng) {
        val map = maplibreMap ?: return
        currentLocationMarker?.let { map.removeMarker(it) }
        val icon = IconFactory.getInstance(context).fromBitmap(drawableToBitmap(R.drawable.ic_current_location))
        currentLocationMarker = map.addMarker(
            MarkerOptions()
                .position(center)
                .title("You are here")
                .icon(icon)
        )
    }

    private fun updateAccuracyCircle(center: LatLng, accuracyMeters: Float) {
        val source = accuracySource ?: return
        source.setGeoJson(pointGeoJson(center.longitude, center.latitude))

        val layer = maplibreMap?.style?.getLayer(ACCURACY_LAYER_ID) as? CircleLayer ?: return
        val radiusAtZoom0 = metersToPixelsAtZoom(accuracyMeters.toDouble(), 0.0, center.latitude)
        val radiusAtZoom20 = metersToPixelsAtZoom(accuracyMeters.toDouble(), 20.0, center.latitude)
        layer.setProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.exponential(2f),
                    Expression.zoom(),
                    Expression.stop(0, radiusAtZoom0),
                    Expression.stop(20, radiusAtZoom20)
                )
            )
        )
    }

    private fun metersToPixelsAtZoom(meters: Double, zoom: Double, latitude: Double): Double {
        val metersPerPixel = 156543.03392 * cos(latitude * PI / 180.0) / Math.pow(2.0, zoom)
        return meters / metersPerPixel
    }

    private fun pointGeoJson(longitude: Double, latitude: Double): String {
        return "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[$longitude,$latitude]}}"
    }

    private fun addPointsOfInterest(map: MapLibreMap, center: LatLng) {
        if (poisAdded) return
        poisAdded = true

        val offset = 0.02
        val pois = listOf(
            Poi("Cafe", "A nearby cafe", offset, offset, R.drawable.ic_poi_restaurant),
            Poi("Park", "A green space", -offset, offset, R.drawable.ic_poi_park),
            Poi("Museum", "A local museum", -offset, -offset, R.drawable.ic_poi_museum),
            Poi("Shop", "A convenience store", offset, -offset, R.drawable.ic_poi_shop)
        )

        val iconFactory = IconFactory.getInstance(context)
        pois.forEach { poi ->
            val icon = iconFactory.fromBitmap(drawableToBitmap(poi.iconRes))
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(center.latitude + poi.dLat, center.longitude + poi.dLng))
                    .title(poi.title)
                    .snippet(poi.snippet)
                    .icon(icon)
            )
        }
    }

    private fun drawableToBitmap(resId: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, resId)!!
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private data class Poi(
        val title: String,
        val snippet: String,
        val dLat: Double,
        val dLng: Double,
        val iconRes: Int
    )

    companion object {
        private val FALLBACK_LOCATION = LatLng(48.8566, 2.3522)
        private const val ACCURACY_SOURCE_ID = "current-location-accuracy-source"
        private const val ACCURACY_LAYER_ID = "current-location-accuracy-layer"
        private const val EMPTY_POINT_GEOJSON =
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[0,0]}}"

        // Update cadence similar to a typical foreground navigation/maps app.
        private const val UPDATE_INTERVAL_MS = 2000L
        private const val FASTEST_INTERVAL_MS = 1000L
    }
}
