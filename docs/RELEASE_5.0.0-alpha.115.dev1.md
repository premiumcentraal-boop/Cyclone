# Cyclone V5 alpha.115 dev1: profile source and identifiers

Android developer candidate based on Cyclone 5.0.0-alpha.114.dev1.

- **Mobile:** `5.0.0-alpha.115.dev1` (version code 268).
- **Other components:** unchanged from alpha.114.dev1.
- **Publication:** owner-authorized developer alpha; physical device acceptance remains unverified.
- **Physical device:** UNVERIFIED.

## Profile source labels

Profiles now show **Rooted** when Cyclone has a matching Cyclone Cloak profile configuration for at least one app in that profile. Other Cyclone profiles show **Native**. The lookup uses Cyclone's per-profile, per-Android-user, per-package connector config and verifies the stored tuple before showing the label.

The Rooted label means a Cloak profile reference is configured in Cyclone. Cyclone does not receive the connector's live root/module health or enabled state, so the label does not claim that root is currently granted.

## Profile identifier details

The profile list and profile details have an info action that opens a dedicated identifier page. It shows the Cyclone profile ID, Android user ID, recorded parent Android user ID, and any configured Cloak profile IDs by app, with copy buttons for available values.

Cyclone does not read or store Android ID, IMEI, or device serial values. They are shown as unavailable. Cyclone receives only Cloak's selected profile reference; the generated device properties remain in Cyclone Cloak and are not exposed by its connector config.

## Verification

- `:app:testDebugUnitTest`: PASS (all mobile unit tests).
- `:app:assembleRelease`: PASS. The local artifact is an unsigned release candidate at `apps/mobile/app/build/outputs/apk/release/app-release-unsigned.apk`.
- APK package/version inspection: PASS (`com.cyclone.mobile`, version code 268, version name `5.0.0-alpha.115.dev1`).
- Signature: unsigned, as configured for local release candidates; a signing-key-backed release artifact was not produced.
- Physical-device verification: UNVERIFIED (no device is attached to ADB).
