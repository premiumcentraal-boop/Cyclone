# Cyclone Camera

Cyclone Camera is a UI-only Android beta exploring a rooted-device virtual camera workflow. Its
features follow the supplied CamSwap frontend specification, styled with the dense, beveled Y2K
console-chrome language from the supplied design system: carbon command bars, cool indigo plates,
amber utility controls, orange forward actions, hard bevels, and chamfered geometry.

## What this beta includes

- Home, System, Logs, and full-screen Settings surfaces
- OFF / FRONT / BACK camera mode selector and OFF / ARMED / INJECTING / ERROR states
- Local video picker and inline RTMP/RTSP stream source form
- Loop control, ARM/DISARM action, and triple-tap quick-off gesture
- One-time setup, integrity, reboot, and engine-option presentation states
- Filterable sample logs with Android text sharing
- DataStore-backed engine settings
- `EngineApi` frontend contract with explicit `TODO()` defaults
- Deterministic in-memory fake engine for standalone builds and previews
- No camera, storage, network, package-query, or root permissions

## What it does not include

This release does **not** intercept Android camera APIs, install or communicate with a root module,
decode video, transmit a stream, alter sensors, reboot a device, hide its launcher icon, or expose a
camera device. Engine-facing controls are backed by the fake in-memory implementation.

## Build

From the repository root, use the existing pinned Gradle wrapper:

```powershell
.\apps\mobile\gradlew.bat -p .\apps\cyclone-camera :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `apps/cyclone-camera/app/build/outputs/apk/debug/app-debug.apk`.

## Release identity

- Application ID: `com.cyclone.camera`
- Version: `0.1.0-beta.1` (`versionCode` 1)
- Minimum Android: 13 (API 33)
- Target Android SDK: 34
- Beta APK signing: Android debug key; not suitable for production distribution
