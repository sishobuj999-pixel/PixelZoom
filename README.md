# PixelZoom

Android image inspection app focused on extreme pixel zoom and file-level data inspection.

## Current implementation
- Open images from Android's photo picker
- Pinch-to-zoom and pan with a pixel grid at high magnification
- Tap a pixel to inspect X/Y, RGBA, HEX and per-channel binary values
- Inspect original image-file bytes in Binary, Hex and ASCII modes
- Copy the data preview or copy the original file bytes (up to 1 MB)
- Check the latest GitHub Release, download an APK and hand off installation to Android's package installer

## Build
Requires JDK 17, Android SDK 35 and Gradle 8.9.

```bash
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Releases and updates
Push a version tag such as `v1.0.1`. GitHub Actions builds an APK and attaches it to the release. The in-app updater checks the latest release for an APK asset.

Android requires user approval to install updates. The app does not silently install APKs. For reliable in-place updates, every release must be signed with the same signing key; the current workflow produces debug APKs for initial testing, so production release signing still needs a persistent keystore configured in GitHub Actions secrets.

## Notes
Binary/Hex/ASCII previews are capped to keep the UI responsive. The original image file bytes are not the same thing as decoded raw pixel-channel data.
