# Cyclone Mobile Build Status

Base branch: `feature/android-mobile-v0`  
Agent 1 branch: `feature/mobile-phone-toolbox-agent1`

## Base Android node — built

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

## Agent 1 universal phone toolbox — built

- [x] Typed `phone.*` request/result/error protocol
- [x] `PhoneToolRegistry` / `PhoneToolExecutor` abstraction
- [x] Normalized flat UI observation model
- [x] Stable node IDs, paths, parent/child relationships and window metadata
- [x] Screen fingerprints
- [x] Resource ID / text / partial text / content-description selectors
- [x] Class/role selectors
- [x] Ancestor/descendant selectors
- [x] Coordinate and relative-position selectors
- [x] Lightweight fuzzy text selector
- [x] Semantic click with gesture fallback
- [x] Long press, tap, type/replace text, scroll, swipe, Back, Home
- [x] Screenshot metadata and crop support
- [x] App launch
- [x] Retained notification list + notification `contentIntent` open
- [x] Clipboard read/write
- [x] Share + allowlisted URI intents
- [x] Local `phone.wait_for` and `phone.assert`
- [x] Bounded retries and post-action assertions
- [x] Command-ID idempotency cache and rapid duplicate suppression
- [x] Capability registry (`AVAILABLE`, `MISSING_PERMISSION`, `UNSUPPORTED_ON_DEVICE`, `TEMPORARILY_UNAVAILABLE`)
- [x] Hardened HUMAN/AGENT controller ownership
- [x] Forced fresh observation after human takeover
- [x] Bounded privacy-conscious command audit records
- [x] Selector unit tests
- [x] `PHONE_TOOL_PROTOCOL.md`
- [x] physical-device acceptance checklist

## CI verified

- [x] Base APK CI build verified
- [x] Agent 1 `testDebugUnitTest` verified — run `32155030855`
- [x] Agent 1 `assembleDebug` verified — run `32155030855`
- [x] Agent 1 APK artifact produced — artifact `9331378907`
- [x] Agent 1 artifact downloaded from CI

The first Agent 1 CI attempt found an obsolete `clickText` call in the Picnic/Teamwork scaffold after the Accessibility API was generalized. It was migrated to the selector API, and the next CI run passed both unit tests and APK assembly. This is real CI evidence rather than code-inspection-only verification.

## Physical-device verification still required

- [ ] APK installed on Android 14+ device
- [ ] normalized Accessibility snapshot read from real phone
- [ ] screenshot captured from real phone
- [ ] selector-based click performed on real phone
- [ ] long press / swipe / scroll / type verified
- [ ] app launch verified
- [ ] notification list/open verified
- [ ] clipboard tools verified
- [ ] capability registry compared against actual permission state
- [ ] HUMAN controller blocks all mutating agent actions
- [ ] return to AGENT requires fresh `phone.observe`
- [ ] stale-UI recovery verified
- [ ] 24-hour reliability and battery soak test

## Higher layers intentionally not owned by Agent 1

- [ ] general Automation Studio/workflow editor — Agent 2
- [ ] skill recording/runner — Agent 2
- [ ] Hermes phone tool adapter and AI planner — Agent 3
- [ ] AI screenshot/vision fallback orchestration — Agent 3
- [ ] Hermes zero-token human takeover/resume — Agent 3/Core

This file deliberately separates source/CI verification from hardware behavior. No device-dependent capability should be checked until it actually runs on an Android 14+ phone.
