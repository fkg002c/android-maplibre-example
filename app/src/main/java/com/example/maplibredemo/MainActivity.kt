package com.example.maplibredemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.PI

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private var maplibreMap: MapLibreMap? = null

    private lateinit var locationEngine: LocationEngine
    private var hasCenteredCamera = false
    private var currentLocationMarker: Marker? = null
    private var accuracySource: GeoJsonSource? = null

    private val locationCallback = object : LocationEngineCallback<LocationEngineResult> {
        override fun onSuccess(result: LocationEngineResult) {
            result.lastLocation?.let { onLocationUpdate(it) }
        }

        override fun onFailure(exception: Exception) {
            if (!hasCenteredCamera) {
                statusText.text = getString(R.string.location_permission_denied)
                showOnMap(FALLBACK_LOCATION)
            }
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startLocationUpdates()
        } else {
            statusText.text = getString(R.string.location_permission_denied)
            showOnMap(FALLBACK_LOCATION)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        statusText = findViewById(R.id.statusText)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            maplibreMap = map
            map.setStyle(Style.Builder().fromUri(DEMO_STYLE_URL)) { style ->
                addAccuracyCircleLayer(style)
                requestLocationPermissionsAndFetch()
            }
        }
    }

    private fun addAccuracyCircleLayer(style: Style) {
        val source = GeoJsonSource(ACCURACY_SOURCE_ID, EMPTY_POINT_GEOJSON)
        accuracySource = source
        style.addSource(source)

        val layer = CircleLayer(ACCURACY_LAYER_ID, ACCURACY_SOURCE_ID).apply {
            setProperties(
                PropertyFactory.circleColor(ContextCompat.getColor(this@MainActivity, R.color.location_blue)),
                PropertyFactory.circleOpacity(0.3f),
                PropertyFactory.circleStrokeColor(ContextCompat.getColor(this@MainActivity, R.color.location_blue)),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeOpacity(1f)
            )
        }
        style.addLayer(layer)
    }

    private fun requestLocationPermissionsAndFetch() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            startLocationUpdates()
        } else {
            requestPermissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        statusText.text = getString(R.string.locating)

        locationEngine = LocationEngineDefault.getDefaultLocationEngine(this)
        locationEngine.getLastLocation(locationCallback)

        val request = LocationEngineRequest.Builder(UPDATE_INTERVAL_MS)
            .setFastestInterval(FASTEST_INTERVAL_MS)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .build()
        locationEngine.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun onLocationUpdate(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        statusText.visibility = View.GONE

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

    private fun updateCurrentLocationMarker(center: LatLng) {
        val map = maplibreMap ?: return
        currentLocationMarker?.let { map.removeMarker(it) }
        val icon = IconFactory.getInstance(this).fromBitmap(drawableToBitmap(R.drawable.ic_current_location))
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

    private var poisAdded = false

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

        val iconFactory = IconFactory.getInstance(this)
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
        val drawable = ContextCompat.getDrawable(this, resId)!!
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::locationEngine.isInitialized) {
            locationEngine.removeLocationUpdates(locationCallback)
        }
        mapView.onDestroy()
    }

    companion object {
        private const val DEMO_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
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
