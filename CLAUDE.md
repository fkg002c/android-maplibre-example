# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A minimal single-Activity Android demo app showing MapLibre GL Native usage on Android: displaying a map,
tracking the device's live location with a marker + accuracy circle, and dropping a few static
points-of-interest markers around the user. Kotlin, no XML fragments/multiple screens — everything lives
in `MainActivity`.

## Commands

Windows shell — use `gradlew.bat`, not `./gradlew`.

```
gradlew.bat assembleDebug          # build debug APK
gradlew.bat installDebug           # build and install on connected device/emulator
gradlew.bat build                  # full build (assemble + check)
gradlew.bat clean
```

There are no test source sets (no `app/src/test` or `app/src/androidTest`) and no lint/ktlint config in
this repo, so there is nothing to run beyond the build itself.

## Architecture

- `app/src/main/java/com/example/maplibredemo/MainActivity.kt` is the entire app. Key flow:
  1. `MapLibre.getInstance(this)` then `mapView.getMapAsync` loads the style from `DEMO_STYLE_URL`
     (a remote OpenFreeMap style JSON URL — the map style is fetched over the network, not bundled).
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
     `onLowMemory`/`onDestroy`) are forwarded from the Activity, and location updates are unregistered in
     `onDestroy` — required boilerplate for MapLibre's `MapView`, not optional per-app config.
- Markers are built from vector drawables converted to bitmaps at runtime (`drawableToBitmap` +
  `IconFactory`), not from XML marker resources.
- All map/location config constants (style URL, fallback coordinates, layer/source IDs, update
  intervals) are companion-object constants at the bottom of `MainActivity`.

## Dependencies / config

- Dependency versions are centralized in `gradle/libs.versions.toml` (Gradle version catalog). Both
  `build.gradle` (root) and `app/build.gradle` reference entries via `libs.*` (e.g.
  `alias(libs.plugins.android.application)`, `libs.maplibre.android.sdk`) rather than hardcoded
  coordinate/version strings. Add or bump dependencies there, not inline in the build files.
- `app/build.gradle`: single dependency of note is `org.maplibre.gl:android-sdk` (MapLibre Android SDK).
  `compileSdk`/`targetSdk` 37, `minSdk` 24.
- Android Gradle Plugin 9.3.0, applied only in the root `build.gradle` (`apply false`) and used by
  `app/build.gradle`.
- No Compose, no additional architecture components (ViewModel/Navigation/etc.) — this is intentionally
  a plain View-based single-screen sample.
