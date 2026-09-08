# MapLibre Android Example

A minimal Android sample app demonstrating [MapLibre GL Native](https://maplibre.org/) on Android:
displaying a map, tracking the device's live location, and showing a few points of interest.

## Features

- Renders a map using a remote [OpenFreeMap](https://openfreemap.org/) "Liberty" style
- Requests location permissions and centers the camera on the device's current location
- Shows a "you are here" marker plus a live accuracy circle that scales with zoom level
- Falls back to a default location (Paris) if permission is denied or location is unavailable
- Drops four static points-of-interest markers (cafe, park, museum, shop) around the user's location

## Requirements

- Android Studio (or the Android SDK command-line tools)
- Android SDK 24+ (compiled against SDK 37)
- Internet access at runtime (the map style is fetched remotely, not bundled)

## Building and running

On Windows, use `gradlew.bat`:

```
gradlew.bat assembleDebug     # build a debug APK
gradlew.bat installDebug      # build and install on a connected device/emulator
```

On macOS/Linux, use `./gradlew` with the same task names.

Alternatively, open the project in Android Studio and run the `app` configuration.

## Project structure

The entire app lives in a single Activity:

```
app/src/main/java/com/example/maplibredemo/MainActivity.kt
```

See [CLAUDE.md](./CLAUDE.md) for a more detailed walkthrough of the code architecture.

## License

No license specified.
