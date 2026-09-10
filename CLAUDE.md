# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A minimal Android demo showing MapLibre GL Native usage on Android: displaying a map (styled with Yandex
raster tiles), tracking the device's live location with a marker + accuracy circle, and dropping a few
static points-of-interest markers around the user. Kotlin throughout.

The repo is two parallel single-Activity apps sharing the same feature set, one plain-View and one
Compose:

- `appRegular` — `com.example.maplibredemo`, XML layout (`activity_main.xml`) + view lookups.
- `appComposable` — `com.example.maplibredemo.composable`, Jetpack Compose (`setContent` +
  `AndroidView`-wrapped `MapView`).

Each module's `MainActivity.kt` implements the full flow independently — there is no shared library
module. When changing behavior, check whether the equivalent change is needed in both modules.

## Commands

Windows shell — use `gradlew.bat`, not `./gradlew`.

```
gradlew.bat assembleDebug                 # build both modules' debug APKs
gradlew.bat :appRegular:assembleDebug     # build a single module
gradlew.bat :appComposable:assembleDebug
gradlew.bat installDebug                  # build and install both on connected device/emulator
gradlew.bat build                         # full build (assemble + check)
gradlew.bat clean
```

There are no test source sets (no `src/test` or `src/androidTest` in either module) and no lint/ktlint
config in this repo, so there is nothing to run beyond the build itself.

## Architecture

Both `MainActivity.kt` files (`appRegular/src/main/java/com/example/maplibredemo/MainActivity.kt` and
`appComposable/src/main/java/com/example/maplibredemo/composable/MainActivity.kt`) follow the same key
flow — appRegular does it directly in the Activity, appComposable delegates to a `MapLibreSession` helper
class driven from a `@Composable MapScreen`:

1. `MapLibre.getInstance(this)` then `mapView.getMapAsync` builds the style programmatically via
   `buildYandexRasterStyle()`: a `Style.Builder()` with a `RasterSource`/`RasterLayer` pair whose tiles
   come from `YANDEX_TILE_URL_TEMPLATE` — an **unofficial** Yandex raster tile endpoint
   (`vec01.maps.yandex.net/tiles?...`). Yandex does not publish a licensed public tile API for
   third-party embedding; this is fine for a demo but should be swapped for a licensed source (or the
   official Yandex MapKit SDK) before shipping outside a demo. There is no bundled/offline style.
2. On style load: adds a `GeoJsonSource`/`CircleLayer` pair (`current-location-accuracy-*`) used to
   render the location accuracy circle, then kicks off the location permission flow.
3. Location permissions are requested via `ActivityResultContracts.RequestMultiplePermissions`
   (fine + coarse). On grant, `LocationEngineDefault` (MapLibre's location engine) streams updates;
   on denial or engine failure, the map falls back to `FALLBACK_LOCATION` (Paris) and shows a status
   message (`R.string.location_permission_denied`).
4. Each location update repositions the "you are here" marker, redraws the accuracy circle (radius
   computed in screen pixels via `metersToPixelsAtZoom`, interpolated across zoom levels with a
   MapLibre `Expression`), and on the *first* fix only, animates the camera to center on the user and
   adds four static demo POI markers (`addPointsOfInterest`) offset from that location.
5. `MapView` lifecycle calls (`onStart`/`onResume`/`onPause`/`onStop`/`onSaveInstanceState`/
   `onLowMemory`/`onDestroy`) are forwarded — from the Activity in appRegular, from a
   `LifecycleEventObserver` in appComposable — and location updates are unregistered on destroy —
   required boilerplate for MapLibre's `MapView`, not optional per-app config.

- Markers are built from vector drawables converted to bitmaps at runtime (`drawableToBitmap` +
  `IconFactory`), not from XML marker resources.
- All map/location config constants (Yandex tile URL template, tile source/layer IDs, fallback
  coordinates, accuracy layer IDs, update intervals) are companion-object constants at the bottom of each
  `MainActivity`/`MapLibreSession`.

## Dependencies / config

- Dependency versions are centralized in `gradle/libs.versions.toml` (Gradle version catalog). The root
  `build.gradle` and both `appRegular/build.gradle` / `appComposable/build.gradle` reference entries via
  `libs.*` (e.g. `alias(libs.plugins.android.application)`, `libs.maplibre.android.sdk`) rather than
  hardcoded coordinate/version strings. Add or bump dependencies there, not inline in the build files.
- Both modules: dependency of note is `org.maplibre.gl:android-sdk` (MapLibre Android SDK).
  `compileSdk`/`targetSdk` 37, `minSdk` 24. `appComposable` additionally pulls in the Compose BOM +
  `activity-compose` and applies the `kotlin-compose` plugin; `appRegular` pulls in `appcompat` +
  `constraintlayout` for its XML layout instead.
- Android Gradle Plugin, applied only in the root `build.gradle` (`apply false`) and used by both app
  modules.
- No shared architecture components (ViewModel/Navigation/etc.) in either module — each is intentionally
  a plain single-screen sample, one View-based and one Compose-based, to demonstrate both integration
  styles.
