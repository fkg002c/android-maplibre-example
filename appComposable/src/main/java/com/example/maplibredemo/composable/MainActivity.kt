package com.example.maplibredemo.composable

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineDefault
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import kotlin.math.PI
import kotlin.math.cos
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

private const val TILE_MAX_REQUESTS_PER_HOST = 4

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // Yandex's Tiles API rate-limits (HTTP 429) unthrottled request bursts, which is
        // exactly what MapLibre's default prefetch fires off during the initial camera
        // animation. A capped per-host concurrency spreads those requests out so fewer get
        // rejected, while still letting prefetch supply low-res placeholder tiles. This must
        // come after MapLibre.getInstance() — HttpRequestImpl's static init requires it.
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = TILE_MAX_REQUESTS_PER_HOST })
                .build()
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    //MapScreen(activity = this)
                    MainMapScreen()
                }
            }
        }
    }
}

@Composable
private fun MapScreen(activity: ComponentActivity) {
    val statusMessage = remember { mutableStateOf<String?>(activity.getString(R.string.locating)) }
    val session = remember { MapLibreSession(activity, statusMessage) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> session.onPermissionResult(grants) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event -> session.onLifecycleEvent(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).also { mapView -> session.bind(mapView, permissionLauncher) }
            }
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
}

private class MapLibreSession(
    private val activity: ComponentActivity,
    private val statusMessage: MutableState<String?>
) {
    private var mapView: MapView? = null
    private var maplibreMap: MapLibreMap? = null
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
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
                statusMessage.value = activity.getString(R.string.location_permission_denied)
                showOnMap(FALLBACK_LOCATION)
            }
        }
    }

    fun bind(view: MapView, launcher: ActivityResultLauncher<Array<String>>) {
        mapView = view
        permissionLauncher = launcher
        view.onCreate(Bundle())
        view.getMapAsync { map ->
            maplibreMap = map
            map.setStyle(buildYandexRasterStyle()) { style ->
                addAccuracyCircleLayer(style)
                requestLocationPermissionsAndFetch()
            }
        }
    }

    private fun buildYandexRasterStyle(): Style.Builder {
        val tileUrl = "$YANDEX_TILE_URL_BASE?l=map&lang=$YANDEX_TILE_LANG" +
            "&x={x}&y={y}&z={z}&projection=web_mercator&apikey=${BuildConfig.YANDEX_MAPS_API_KEY}"
        val tileSet = TileSet(TILE_JSON_VERSION, tileUrl).apply {
            minZoom = 0f
            maxZoom = 19f
        }
        val rasterSource = RasterSource(YANDEX_SOURCE_ID, tileSet, YANDEX_TILE_SIZE)
        val rasterLayer = RasterLayer(YANDEX_LAYER_ID, YANDEX_SOURCE_ID)
        return Style.Builder()
            .withSource(rasterSource)
            .withLayer(rasterLayer)
    }

    fun onPermissionResult(grants: Map<String, Boolean>) {
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startLocationUpdates()
        } else {
            statusMessage.value = activity.getString(R.string.location_permission_denied)
            showOnMap(FALLBACK_LOCATION)
        }
    }

    fun onLifecycleEvent(event: Lifecycle.Event) {
        val view = mapView ?: return
        when (event) {
            Lifecycle.Event.ON_START -> view.onStart()
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            Lifecycle.Event.ON_STOP -> view.onStop()
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> {}
        }
    }

    private fun requestLocationPermissionsAndFetch() {
        val fineGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_COARSE_LOCATION
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
        statusMessage.value = activity.getString(R.string.locating)

        val engine = LocationEngineDefault.getDefaultLocationEngine(activity)
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

    private fun addAccuracyCircleLayer(style: Style) {
        val source = GeoJsonSource(ACCURACY_SOURCE_ID, EMPTY_POINT_GEOJSON)
        accuracySource = source
        style.addSource(source)

        val layer = CircleLayer(ACCURACY_LAYER_ID, ACCURACY_SOURCE_ID).apply {
            setProperties(
                PropertyFactory.circleColor(ContextCompat.getColor(activity, R.color.location_blue)),
                PropertyFactory.circleOpacity(0.3f),
                PropertyFactory.circleStrokeColor(ContextCompat.getColor(activity, R.color.location_blue)),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeOpacity(1f)
            )
        }
        style.addLayer(layer)
    }

    private fun updateCurrentLocationMarker(center: LatLng) {
        val map = maplibreMap ?: return
        currentLocationMarker?.let { map.removeMarker(it) }
        val icon = IconFactory.getInstance(activity).fromBitmap(drawableToBitmap(R.drawable.ic_current_location))
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

        val iconFactory = IconFactory.getInstance(activity)
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
        val drawable = ContextCompat.getDrawable(activity, resId)!!
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun destroy() {
        locationEngine?.removeLocationUpdates(locationCallback)
        mapView?.onDestroy()
    }

    private data class Poi(
        val title: String,
        val snippet: String,
        val dLat: Double,
        val dLng: Double,
        val iconRes: Int
    )

    companion object {
        // Official Yandex Tiles API: https://yandex.ru/maps-api/docs/tiles-api/quickstart.html
        // Requires an API key for the "Tiles API" package, supplied via local.properties as
        // YANDEX_MAPS_API_KEY and exposed here through BuildConfig (see build.gradle).
        private const val YANDEX_TILE_URL_BASE = "https://tiles.api-maps.yandex.ru/v1/tiles/"
        private const val YANDEX_TILE_LANG = "en_US"
        private const val TILE_JSON_VERSION = "2.1.0"
        private const val YANDEX_SOURCE_ID = "yandex-raster-source"
        private const val YANDEX_LAYER_ID = "yandex-raster-layer"
        private const val YANDEX_TILE_SIZE = 256
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
