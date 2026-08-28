# Cyclone 3.5 hard-launch report

## Release verdict

**INTEGRATION CANDIDATE — PUBLICATION NOT YET AUTHORIZED.**

This report is the release authority. A green unit test, local build or pushed branch does not by
itself make Cyclone 3.5 a hard launch. Publication is permitted only after the physical, virtual,
fleet, packaging and CI evidence below is complete and `release/version.toml` records
`publication_authorized = true`.

## Source identity

- Frozen baseline: `e0149ab0638c77fa3d99d9d383f1d912fcbca25e`.
- Research branch head: `0aa6d5d85031c4d85aa047b541aeb9f9d0f8000e`.
- Fleet/virtualization branch head: `18887d84e320d65a7fc81b2525bf557e164bec51`.
- AI/Teach/MCP branch head: `5b34c27fe557c1fdefd0c0a2f8fd24b218f6f9d0`.
- Integration source SHA: pending final commit.
- Product/mobile/PC/Python version: `3.5.0`; Android `versionCode 36`.

## Delivered product scope

- One Cyclone Android app and canonical `PhoneToolExecutor`/`phone.*` path.
- Unified Device Wall and backend capability model for physical and virtual Android endpoints.
- Durable grouping/selection, typed fleet batches, reconnect-aware offline inventory and isolated
  device diagnostics.
- Official Android Emulator lifecycle provider with loopback-only endpoints and fail-closed health.
- Teach evidence/selector/verifier quality gates and bounded, resumable agent execution.
- Explicit-target governed MCP tools with no generic shell, ADB, Docker or PowerShell execution.
- Clean-room VMOS research and licensing ledger; no blocked VMOS code or binary ships.

## Automated evidence

- Device Gateway: **120 passed** after integration and verification-authority regressions.
- PC Companion: **38 passed**; production web build passed.
- Codex phone MCP: **46 passed**.
- Cyclone agent MCP: **36 passed**.
- Release version coherence: passed for Android, Python, Node lockfile, Cargo and Tauri.
- Android full unit tests/APK: pending release-host run.
- Windows NSIS installer: pending release-host run.

## Physical Pixel 8 acceptance

Connected target: Pixel 8 / Android 16, serial suffix `0061G`. Discovery is proven; installation,
pair/trust, live view, typed actions, semantic verification, reconnect, screenshot and taught-routine
replay are pending the final APK acceptance run. No unexecuted item is labelled physically verified.

## Virtual and fleet acceptance

The provider contract and lifecycle tests are unit verified. An actual create/start/register/control/
restart/delete run is pending release-host AVD provisioning. Mixed physical + virtual and two-device
batch evidence therefore remain pending. Unsupported clone and snapshot/restore capabilities are
not advertised.

## Packaging, signing and distribution

- Android artifact: pending; debug signing expected unless the release host supplies protected keys.
- Windows artifact: pending; signing status unverified.
- Same-SHA checksums/provenance: pending final artifacts.
- GitHub CI/release: pending final source push; the workflow refuses publication while release
  metadata has not explicitly authorized it.

## Setup and troubleshooting

Install matching Mobile and PC Companion builds from the same release. Connect Android by USB,
authorize debugging, keep the phone unlocked, and complete **Allow this PC** trust. The four-letter
flow is transition/recovery-only and read-only. Virtual phones require the official Android SDK,
Emulator and configured system image; provider health explains missing prerequisites.

If discovery fails, verify USB authorization and that the expected ADB endpoint is present. If live
view fails while control works, use **Retry live view** and save a debug bundle; media and semantic
control are intentionally separate health planes. If a virtual provider is unavailable, do not
expose or forward ADB publicly—install the documented local prerequisite and retry provider health.
A differently signed Android build may require uninstalling the previous APK, which removes local
app data.

## Known limitations and licensing

- Protected/DRM surfaces and the Android lock screen can be blank by platform design.
- Android debug signing and unsigned Windows packaging are not production signing claims.
- ReDroid is experimental on this host because WSL binder support has not been proven.
- VMOS Edge Desktop (GPL-3.0), the noncommercial VMOS AOSP tree and packages without a clear
  compatible license remain research-only. Full evidence and exact versions are in
  `docs/research/vmos/LICENSE_LEDGER.md`.
