# Cyclone Mobile

The current product interface follows the Cyclone V3.2 calm mobile redesign. Read
[`../../docs/design/mobile-v32/README.md`](../../docs/design/mobile-v32/README.md) before changing
navigation, routine creation, visual tokens or user-facing automation language.

Cyclone Mobile is the first non-root Android device node for Cyclone. It targets Android 14+ (`minSdk 34`) and deliberately separates **implemented** from **verified on a real device**.

## Implemented in v0

- AccessibilityService UI-tree observation
- semantic click and text entry
- tap/swipe/scroll/back/home
- Accessibility screenshot capture
- NotificationListenerService events
- Calendar conflict checks
- configurable work-app shift routine scaffold
- dry-run by default; explicit opt-in is required before any real claim click
- WebSocket bridge to Cyclone Core
- human-vs-agent controller lock
- in-app build and verification checklist

## Not yet claimed as verified

The APK must still be built by CI, installed on an Android 14+ phone, and tested against the real Teamwork/Picnic app. The current shift parser only understands same-day `HH:MM-HH:MM` text and must be replaced/refined using real notification/UI evidence.

## Build

From `apps/mobile` with Java 17 and Android SDK 35 installed:

```bash
gradle :app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions also uploads `cyclone-mobile-debug-apk` on the feature branch and pull requests.

## First device acceptance sequence

1. Install the debug APK on Android 14 or newer.
2. Open Cyclone Mobile.
3. Enable Cyclone Accessibility Service.
4. Grant Notification access.
5. Grant Calendar permission.
6. Verify observe, screenshot, semantic click, scroll, back and home.
7. Configure the Cyclone Core WebSocket endpoint and token once Core exposes the mobile endpoint.
8. Capture one real work-app notification and UI tree before enabling auto-claim.
9. Keep auto-claim disabled until the actual app state machine has been verified end-to-end.
