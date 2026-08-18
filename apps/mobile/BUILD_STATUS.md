# Cyclone Mobile v0 Build Status

Branch: `feature/android-mobile-v0`

## Built

- [x] Android project source created
- [x] Android 14+ target configured (`minSdk 34`)
- [x] Accessibility control service implemented
- [x] Screenshot capture implemented
- [x] Notification listener implemented
- [x] Calendar matcher implemented
- [x] Safe work-shift routine scaffold implemented
- [x] Cyclone WebSocket bridge implemented
- [x] Human/agent control lock implemented
- [x] APK CI workflow implemented

## Verified

- [x] APK CI build verified — GitHub Actions run `32152412017` completed successfully
- [x] Debug APK artifact produced and downloaded from CI
- [ ] APK installed on Android 14+ device
- [ ] Physical Android 14+ device verified
- [ ] Accessibility tree read from real phone
- [ ] Screenshot captured from real phone
- [ ] Remote semantic click performed on real phone
- [ ] Notification received from real work app
- [ ] Calendar conflict/free test passed on device
- [ ] Real Teamwork/Picnic notification/UI mapped
- [ ] End-to-end eligible-shift detection verified
- [ ] End-to-end shift claim verified
- [ ] Cyclone Core mobile WebSocket endpoint implemented/verified
- [ ] Hermes zero-token takeover/resume integrated
- [ ] 24-hour reliability and battery soak test

## Build evidence

The first CI attempt reached Kotlin compilation and exposed a real source error in the notification-access status check. That implementation was corrected to use `NotificationManagerCompat.getEnabledListenerPackages(...)`. The next build completed `assembleDebug` successfully and uploaded the `cyclone-mobile-debug-apk` artifact.

This file intentionally distinguishes code that exists from behavior verified on real hardware. Device-dependent functionality remains unchecked until it has actually run on an Android 14+ phone.
