# Cyclone Camera — Phase 2+ implementation plan (canonical)

> Path correction: camera work lives at `apps/cyclone-camera/` (package `com.cyclone.camera`),
> NOT `shared/camswap-ui/`. Branch: `worktree/cyclone-camera` (UI beta merged, ff from 9957eea).
> Contract: `engine/CONTRACT.md` (locked v1.0). Full phased plan: C:\Users\Agent\shared\cyclone-camera-detailed-plan.md

## Pixel 8 build environment notes (this machine)

- Android SDK root: `C:\Users\Agent\AppData\Local\Android` (platforms/android-35, build-tools 34/35 present).
- `apps/cyclone-camera/local.properties` (gitignored) points there; recreate it on fresh clones.
- Build: `cd apps/cyclone-camera && ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
- Gradle builds must run in background (foreground terminal cap is 600s; cold builds exceed it).

## Phase 2 — Engine core (CC-Engine Core), steps E1–E6

- E1: Fork Yaahua/android_virtual_cam (GPL-3.0), strip UI + manual DCIM/Camera1 file-drop;
  engine builds as standalone Gradle module; Zygisk hook module loads on Pixel 8 test image.
  Gate: module loads, no bootloop, camera apps see injected frames from raw file.
- E2: MediaCodec decode loop (H.264 → H.265), hardware decoder on Tensor G3, native FPS pacing.
  Gate: 30-min run, <2% frame drops.
- E3: File source with loop seam smoothing. Gate: seam invisible 10×5-min consecutive loops.
- E4: RTMP ingest → RTSP → HLS; reconnect backoff; auto-disarm on stream death.
  Gate: 2-hr run survives 3 forced reconnects.
- E5: Camera1 + Camera2 injection parity behind one frame-provider interface.
  Gate: 5 target apps on both hook paths.
- E6: Real EngineApi implementation wiring engine-service to the locked contract.
  Gate: contract test suite (owned by Config Bridge) 100%.

Decision gate day 6: if fork strip leaves >40% dead code → pivot Zygisk-native, descope v1 to file-source.

## Phase 3 — Installer + anti-detect (CC-Anti-Detect → CC-Module Installer)

- Anti-Detect ships pinned module zips in `module/zips/` + manifest (name, version, sha256,
  install order, verify command): Zygisk Next, Shamiko/ReZygisk, Play Integrity Fix
  (JingMatrix/LSPosed_mod lineage), Tricky Store, Hide My Applist.
- Installer implements `runSetup()`: precheck → stage+sha256 → su install → configure
  (DenyList, scoping from `scopedApps`) → reboot prompt → verify → Ready/Partial/Failed.
  Idempotent at every step; clean uninstall path required pre-release.
- Edge cases locked in CONTRACT.md (interrupted reboot resumes at verify; Partial = per-module status).

## Phase 4 — Integration & gate (CC-CI Reliability)

- Swap fake engine for real impl in `apps/cyclone-camera` — UI code must not change.
- CI: extend `mobile-ci.yml` ONLY (no new workflow): camera build lane, contract tests,
  sanitization grep gate, debug APK. versionCode discipline per repo release playbook.
- Pixel 8 smoke checklist (each item needs evidence): arm/disarm round-trip · front swap in
  video-call app · back swap in camera app · file loop ×10 no seams · RTMP 2-hr/3 reconnects ·
  RTSP 30-min · integrity pills after fresh setup · panic triple-tap from every state ·
  auto-disarm on lock <2s · setup reboot → verify → ready · clean uninstall.
- Release via existing lane; rollback = previous APK (pinned modules survive app downgrade).

## Invariants (from @premium, binding)

- `com.cyclone.camera` stays a separate package; `com.cyclone.mobile` AGENTS.md invariants inviolable.
- One CI lane: `.github/workflows/mobile-ci.yml` — no second workflow file.
- No root/stealth/detection wording in any UI-facing string, ever.
