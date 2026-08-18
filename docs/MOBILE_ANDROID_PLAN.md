# Cyclone Android Mobile Node — Build & Verification Plan

Target: **non-root Android 14+**. Branch: `feature/android-mobile-v0`.

## Status legend

- **BUILT**: source implementation exists.
- **VERIFIED**: acceptance test has run successfully with evidence.
- **NEXT**: planned gate; do not claim success yet.

| Capability | Built | Verified | Next evidence |
|---|---:|---:|---|
| Android 14+ application shell | ✅ | ⬜ | CI assembleDebug |
| Accessibility service | ✅ | ⬜ | enable on physical phone |
| UI tree | ✅ | ⬜ | capture real app hierarchy |
| semantic click / text | ✅ | ⬜ | click a harmless control |
| gestures / scroll / back / home | ✅ | ⬜ | physical-device action test |
| screenshot | ✅ | ⬜ | PNG from Accessibility API |
| notification listener | ✅ | ⬜ | real notification event |
| calendar matcher | ✅ | ⬜ | controlled overlap/free tests |
| shift routine scaffold | ✅ | ⬜ | capture Teamwork/Picnic format |
| safe dry-run default | ✅ | ⬜ | verify no click while disabled |
| WebSocket mobile bridge | ✅ | ⬜ | implement/connect Core endpoint |
| human controller lock | ✅ | ⬜ | reject agent action while HUMAN |
| APK build workflow | ✅ | ⬜ | GitHub Actions succeeds + artifact |
| AI visual fallback | ⬜ | ⬜ | add only after screenshot path works |
| Hermes event-driven takeover/resume | ⬜ | ⬜ | Core-side task checkpoint integration |
| learned/local skills | ⬜ | ⬜ | stable Teamwork flow first |
| 24/7 reliability | ⬜ | ⬜ | soak test + battery measurements |

## Step-by-step gates

### Gate 1 — Buildable APK
CI compiles `apps/mobile` with Java 17, SDK 35 and Gradle 8.9. Upload the debug APK artifact. No device capability is called verified until installed.

### Gate 2 — Physical phone control
Install on Android 14+, enable Accessibility, then verify `observe`, screenshot, click-by-text, swipe/scroll, Back and Home. Record failures by OEM/device.

### Gate 3 — Event-driven observation
Grant notification access and Calendar. Verify a posted notification arrives without polling. Test free/busy decisions with known calendar events.

### Gate 4 — Work-app vertical slice
Keep auto-claim OFF. Capture a real Teamwork/Picnic notification and accessibility hierarchy. Identify package name, deep-link behavior, shift time format, claim-button selector and success state. Replace generic assumptions with those facts.

### Gate 5 — Safe claim state machine
Implement explicit states: notification → shift details → eligible → claim available → confirmation → claimed. Add duplicate-event and duplicate-claim protection. Verify in dry-run first, then opt-in real action only after successful state recognition.

### Gate 6 — Cyclone Core integration
Add an authenticated mobile WebSocket endpoint to Cyclone Core and map typed commands/events. The phone should never expose an unauthenticated public control port. Add the Human Intervention Protocol so Hermes suspends with zero model calls during takeover.

### Gate 7 — Reliability
Restart app/services, reboot phone, network disconnect/reconnect, app update, screen lock/unlock, stale notification, duplicate notification and 24-hour soak tests. Measure idle RAM/battery rather than estimate.

## Current honest position

The repository now contains the first APK source and CI builder, but no physical-device claim is verified yet. The next objective evidence is a successful CI APK artifact, followed by installation on an Android 14+ phone.
