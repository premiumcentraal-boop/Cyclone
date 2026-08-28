# Cyclone 3.5.1 release report

## Candidate scope

Cyclone 3.5.1 is the human-control and stability release for the PC Companion. It replaces the
left navigation rail with a compact top bar, moves Connections and Settings into the top-right
profile menu, adds health notifications, separates the active phone from device context and
controls, and sizes the phone to its real display aspect ratio.

The focused phone accepts direct mouse input: click sends a normalized tap; hold-drag-release sends
a bounded normalized swipe through the authenticated manual desktop contract and the canonical
Android `PhoneToolExecutor`. The controller provides Back, Home, and four directional swipes.

## Stability changes

- Fleet heartbeat and topology refreshes no longer tear down a mounted focused stream.
- Live display keeps the existing bounded reconnect/fallback behavior and truthful diagnostics.
- Data/blob fallback previews remain valid while HTTP snapshots still receive cache busting.
- Letterbox padding is excluded from tap and swipe coordinate mapping.

## Candidate identity

- Product/components: `3.5.1`
- Android: `versionName 3.5.1`, `versionCode 37`
- Release channel: stable
- Publication: authorized for the exact tested candidate; GitHub must still build and verify both artifacts

## Acceptance gates

- [x] PC focused tests and production web build
- [x] Gateway manual-control focused tests
- [x] Android gateway contract focused test
- [x] Browser visual QA at 1440×920-equivalent desktop layout
- [x] Browser mouse drag translated to a swipe with visible success feedback
- [x] Full PC, Gateway, MCP, Android, and release metadata suites
- [x] Pixel 8 install and harmless tap/swipe/control acceptance
- [ ] Windows NSIS installer built by CI from the exact release SHA
- [ ] GitHub release assets, hashes, provenance, and target SHA verified
