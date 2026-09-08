package com.example.maplibredemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private var maplibreMap: MapLibreMap? = null

    private val locationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchLocation()
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
            map.setStyle(Style.Builder().fromUri(DEMO_STYLE_URL)) {
                requestLocationPermissionsAndFetch()
            }
        }
    }

    private fun requestLocationPermissionsAndFetch() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            fetchLocation()
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
    private fun fetchLocation() {
        statusText.text = getString(R.string.locating)

        val lastKnown = locationManager.getProviders(true)
            .mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }

        if (lastKnown != null) {
            onLocationReady(lastKnown)
            return
        }

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            statusText.text = getString(R.string.location_permission_denied)
            showOnMap(FALLBACK_LOCATION)
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                onLocationReady(location)
            }
        }
        locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
    }

    private fun onLocationReady(location: Location) {
        statusText.visibility = View.GONE
        showOnMap(LatLng(location.latitude, location.longitude))
    }

    private fun showOnMap(center: LatLng) {
        val map = maplibreMap ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 11.5), 1200)
        map.style?.let { addPointsOfInterest(map, center) }
    }

    private fun addPointsOfInterest(map: MapLibreMap, center: LatLng) {
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
        mapView.onDestroy()
    }

    companion object {
        private const val DEMO_STYLE_URL = "https://demotiles.maplibre.org/style.json"
        private val FALLBACK_LOCATION = LatLng(48.8566, 2.3522)
    }
}
