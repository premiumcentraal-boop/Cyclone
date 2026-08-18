# Cyclone + Mobilerun Integration Status

Branch: `feature/mobile-mobilerun-backend`  
PR: #5  
Target base: `feature/mobile-phone-toolbox-agent1`

## Gate 1 — Licensing / architecture

- [x] Inspect current Mobilerun Portal upstream
- [x] Verify current upstream license is AGPL-3.0-or-later
- [x] Avoid copying Portal source into Cyclone without an explicit licensing decision
- [x] Define Portal as an external backend behind Cyclone `phone.*`
- [x] Pin upstream reference used for integration research

## Gate 2 — Cyclone Portal compatibility backend

- [x] Add typed Portal client
- [x] Add authenticated bearer transport to Portal
- [x] Normalize `state_full` into Cyclone UI snapshots
- [x] Normalize package/activity/screen dimensions
- [x] Normalize UI hierarchy and semantic node state
- [x] Stable node IDs and screen fingerprints
- [x] Resource/text/content-description/class/role selectors
- [x] Ancestor/descendant and coordinate selectors
- [x] Lightweight fuzzy selector support
- [x] Semantic click through current-tree re-resolution
- [x] Tap / type / replace text / scroll / swipe
- [x] Back / Home
- [x] App launch and allowlisted deep links
- [x] Clipboard get/set
- [x] Screenshot metadata + opt-in base64
- [x] Deterministic local `wait_for` / `assert`
- [x] Typed unavailable errors for unsupported Portal HTTP capabilities

## Gate 3 — Cyclone reliability/safety above Portal

- [x] Serialized command execution
- [x] Command-ID idempotency
- [x] Rapid duplicate action suppression
- [x] Bounded selector retries
- [x] Optional post-action assertions
- [x] Before/after screen fingerprints
- [x] HUMAN input ownership blocks mutations
- [x] Return to AGENT requires fresh `phone.observe`
- [x] Portal token remains secret configuration, never committed
- [x] Mobile Gateway control endpoint requires Cyclone internal key
- [x] Gateway binds to loopback in Docker overlay

## Gate 4 — Automated verification

- [x] Mock Portal normalization test
- [x] Selector test
- [x] Semantic click-to-center test
- [x] Human takeover lock test
- [x] Fresh-observation-after-takeover test
- [x] Idempotency test
- [x] Screenshot metadata/base64 test
- [x] Dedicated GitHub Actions workflow
- [x] CI test suite PASS — run `32158850777`
- [x] Mobile Gateway import PASS — run `32158850777`

## Gate 5 — Real Android Portal smoke test

- [ ] Install/configure current Mobilerun Portal on Android 14+ phone
- [ ] Enable Accessibility in Portal
- [ ] Enable Portal local HTTP server
- [ ] Add Portal URL/token to local Cyclone `.env`
- [ ] `GET /health` reports Portal reachable
- [ ] `phone.observe` returns real phone hierarchy
- [ ] `phone.screenshot` captures real screen
- [ ] semantic `phone.click` works on harmless target
- [ ] `phone.type` works
- [ ] `phone.scroll` / `phone.swipe` work
- [ ] app launch works
- [ ] clipboard behavior verified on target OEM
- [ ] HUMAN ownership blocks real input
- [ ] return to AGENT forces fresh observe

## Gate 6 — Reverse WebSocket / event transport

- [ ] Implement authenticated Portal reverse-WebSocket endpoint
- [ ] Device registration by Portal device ID
- [ ] Request/response correlation
- [ ] Heartbeat/reconnect state
- [ ] Normalize `events/device` into Cyclone event bus
- [ ] App-entered event
- [ ] Notification event
- [ ] Lock/screen/network events where useful
- [ ] No LLM polling for device events

## Gate 7 — Native + Portal capability router

- [ ] Introduce `MobileBackend` interface in Cyclone Core
- [ ] Register Cyclone native backend
- [ ] Register Mobilerun Portal backend
- [ ] Route notifications/calendar to Cyclone native Android APIs
- [ ] Route rich accessibility tree to healthiest backend
- [ ] Route screenshots to healthiest backend
- [ ] Expose backend/capability health
- [ ] Fail over without changing `phone.*` semantics

## Gate 8 — Live phone computer / human takeover

- [ ] Integrate Portal reverse-connection WebRTC signaling
- [ ] Show live Android stream inside Cyclone Desktop
- [ ] Take Control switches controller to HUMAN
- [ ] Agent input technically blocked during takeover
- [ ] Return Control switches to AGENT
- [ ] Fresh phone state collected before Hermes resumes
- [ ] Zero-token waiting while human controls phone

## Gate 9 — Production hardening

- [ ] Android reboot recovery
- [ ] PC/Core restart recovery
- [ ] Wi-Fi/mobile network transition tests
- [ ] Portal disconnect/reconnect tests
- [ ] multi-device registration
- [ ] multi-OEM Android 14+ test matrix
- [ ] 24-hour soak test
- [ ] measured battery impact
- [ ] measured action latency
- [ ] privacy/audit review

## Current interpretation

The software integration is now CI-verified through Gate 4. It is **not yet physically verified on a real Mobilerun Portal phone**. Do not mark Gate 5+ complete through code inspection alone.
