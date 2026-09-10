package com.example.maplibredemo.composable

import android.content.Context
import androidx.compose.runtime.remember
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLng
import org.json.JSONObject

class MapCacheManager(private val context: Context) {

    private val offlineManager: OfflineManager by lazy {
        OfflineManager.getInstance(context)
    }

    fun cacheRegion(
        apiKey: String,
        regionName: String,
        bounds: LatLngBounds,
        minZoom: Double = 10.0,
        maxZoom: Double = 14.0,
        onProgress: (Double) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val baseUrl = "https://tiles.api-maps.yandex.ru/v1/tiles/"
        val params = "?x={x}&y={y}&z={z}&lang=ru_RU&l=map&projection=web_mercator&apikey=$apiKey"
        val fullUrl = baseUrl + params
        val yandexStyleJson: String? =
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
        val definition: OfflineRegionDefinition = OfflineTilePyramidRegionDefinition(
            yandexStyleJson,
            bounds,
            minZoom,
            maxZoom,
            context.resources.displayMetrics.density
        )

        val metadataJson = JSONObject().apply {
            put("region_name", regionName)
        }
        val metadata = metadataJson.toString().toByteArray(Charsets.UTF_8)

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)

                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: org.maplibre.android.offline.OfflineRegionStatus) {
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                onSuccess()
                            } else {
                                val progress = if (status.requiredResourceCount > 0) {
                                    (status.completedResourceCount.toDouble() / status.requiredResourceCount.toDouble()) * 100
                                } else 0.0
                                onProgress(progress)
                            }
                        }

                        override fun onError(error: org.maplibre.android.offline.OfflineRegionError) {
                            onError(error.message ?: "Unknown download error")
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            onError("Offline tile limit exceeded: $limit")
                        }
                    })
                }

                override fun onError(error: String) {
                    onError(error)
                }
            }
        )
    }
}
