# Cyclone Mobile — Universal Phone Toolbox Acceptance

Target: Android 14+ non-root device.

The boxes below distinguish source/CI verification from physical-device verification. Do not check a device item without observed evidence.

## Build / unit verification

- [x] `testDebugUnitTest` passes — GitHub Actions run `32155030855`
- [x] `assembleDebug` passes — GitHub Actions run `32155030855`
- [x] APK artifact produced — artifact `9331378907`, `cyclone-mobile-debug-apk`

## Device capabilities

- [ ] `phone.capabilities` reports Accessibility accurately
- [ ] notification permission state accurately reported
- [ ] Calendar permission state accurately reported
- [ ] screenshot capability accurately reported
- [ ] battery-optimization status accurately reported

## Observe

- [ ] `phone.observe` returns current package
- [ ] screen dimensions are correct
- [ ] active windows are represented
- [ ] node IDs are present
- [ ] parent/child relationships are valid
- [ ] resource IDs are captured where Android exposes them
- [ ] text/content descriptions are captured
- [ ] node state booleans are accurate
- [ ] repeated unchanged observations have the same screen fingerprint
- [ ] meaningful UI change produces a different fingerprint

## Selectors

Selector resolution has unit-test coverage, but Android-device behavior remains unchecked below.

- [ ] resource-ID selector on device
- [ ] exact text selector on device
- [ ] partial text selector on device
- [ ] content-description selector on device
- [ ] class/role selector on device
- [ ] ancestor selector on device
- [ ] descendant selector on device
- [ ] coordinate hit selector on device
- [ ] relative selector on device
- [ ] fuzzy text selector on device

## Actions

Use harmless screens/settings for initial tests.

- [ ] click selected element
- [ ] click falls back safely when child text node is not itself clickable
- [ ] long press
- [ ] type / replace text
- [ ] scroll forward
- [ ] scroll backward
- [ ] coordinate tap
- [ ] swipe
- [ ] Back
- [ ] Home
- [ ] open installed app
- [ ] open retained notification
- [ ] clipboard write
- [ ] clipboard read where Android permits it
- [ ] allowlisted URI launch
- [ ] share intent

## Screenshot

- [ ] full screenshot captured
- [ ] screenshot dimensions correct
- [ ] cropped screenshot captured
- [ ] base64 returned only when explicitly requested
- [ ] secure/unavailable screenshot returns typed failure rather than fake success

## Reliability

- [ ] `phone.wait_for` succeeds when UI state appears
- [ ] `phone.wait_for` times out with typed error
- [ ] `phone.assert` success
- [ ] `phone.assert` failure
- [ ] bounded retry works
- [ ] duplicate action suppressed
- [ ] same command ID returns idempotent cached result rather than running twice
- [ ] before/after fingerprints recorded
- [ ] stale target does not cause an unintended click

## Human takeover

- [ ] switch controller to HUMAN
- [ ] observe remains available
- [ ] click rejected with `HUMAN_HAS_CONTROL`
- [ ] tap rejected
- [ ] swipe rejected
- [ ] app launch rejected
- [ ] return controller to AGENT
- [ ] immediate action rejected with `FRESH_OBSERVATION_REQUIRED`
- [ ] run `phone.observe`
- [ ] action becomes available again
- [ ] an action queued before takeover does not execute after takeover

## Notification state

- [ ] posted notification appears in `phone.get_notifications`
- [ ] notification key is stable for retained notification
- [ ] title/text/package/post time returned
- [ ] action titles returned where present
- [ ] removed notification disappears from retained state

## Privacy / audit

- [ ] command audit records command ID, tool, duration, result, fingerprints, error code on device
- [ ] audit does not store screenshot bytes
- [ ] audit does not store bearer token
- [ ] logs are bounded

## Compatibility samples

Record device/OEM/Android version and failures:

| Device | Android | Observe | Screenshot | Click | Swipe | Notification | Notes |
|---|---:|---:|---:|---:|---:|---:|---|
| pending | 14+ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | |

## Agent 1 implementation status

CI/build definition of done is satisfied: unit tests pass, the APK compiles, the protocol documentation exists, and requested primitives have typed result/error contracts. Physical-device verification remains a separate gate and intentionally stays unchecked until run on hardware.
