package com.example.maplibredemo.composable

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun YandexMapLibreView(
    apiKey: String,
    modifier: Modifier = Modifier,
    onMapReady: (org.maplibre.android.maps.MapLibreMap) -> Unit = {}
) {
    val context = LocalContext.current

    remember { MapLibre.getInstance(context) }

    val mapView = remember { MapView(context) }

    val yandexStyleJson = remember(apiKey) {
        val baseUrl = "https://tiles.api-maps.yandex.ru/v1/tiles/"
        val params = "?x={x}&y={y}&z={z}&lang=ru_RU&l=map&projection=web_mercator&apikey=$apiKey"
        val fullUrl = baseUrl + params

        """
    {
      "version": 8,
      "sources": {
        "yandex-raster-source": {
          "type": "raster",
          "tiles": ["$fullUrl"],
          "tileSize": 256,
          "attribution": "© Яндекс"
        }
      },
      "layers": [
        {
          "id": "yandex-raster-layer",
          "type": "raster",
          "source": "yandex-raster-source",
          "minzoom": 0,
          "maxzoom": 19
        }
      ]
    }
    """.trimIndent()
    }

    DisposableEffect(mapView) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            FrameLayout(context).apply {
                addView(mapView)
            }
        },
        modifier = modifier,
        update = {
            mapView.getMapAsync { map ->
                if (map.style == null) {
                    map.setStyle(Style.Builder().fromJson(yandexStyleJson)) { style ->
                        map.setTileCacheEnabled(true)
                        onMapReady(map)
                    }
                }
            }
        }
    )
}
