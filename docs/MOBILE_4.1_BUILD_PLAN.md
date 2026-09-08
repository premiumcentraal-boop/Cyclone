# Cyclone Mobile 4.1 — Session Contract Mobile (phone sprint)

**Status:** B1 DONE, B2 DONE, B3 DONE, B4 DONE (source/CI; Pixel **UNVERIFIED**). B5 DONE (source/docs; Pixel **UNVERIFIED**).  
**Codename:** Session Contract Mobile  
**Base:** published **`v4.0.4`** (`38f7628`, versionCode **75**) — NOT stale `main` (4.0.1)  
**Device today:** Pixel may still be on **4.0.3** / 74. No B1/B2/B3/B4 adb/assemble smoke was run.  
**Out:** Mobile **4.1.0** / versionCode **80** signed with update-compatible dev key (or documented wipe)  
**Grok:** each stage on Agent PC with **subagents required**

## Why this sprint exists

4.0.1–4.0.4 already shipped task glass, one-button BG/Shizuku setup, Layer 2 workspaces, and in-app Shizuku/profile setup — but **physical Pixel acceptance is UNVERIFIED**, One/MCP did not absorb Layer 2, and dual-plane rules (VD vs Layer 2) are easy to misuse. 4.1 hardens and integrates; it does **not** redo 4.0.x features.

## North star

Human owns display 0. Named Shizuku VDs run Ask/Fast Path/skills. Layer 2 time-slices display-0 app/profile workspaces under one mutate lock. Sticky a11y + gateway. Signed installs that update cleanly.

## Preflight (human / orchestrator — not a Grok stage)

1. Install published **4.0.4** signed APK on Pixel 8 (update-compatible with 4.0.3 signer).
2. Confirm One 1.0.0 still pairs (USB / trust / default-foreground observe).
3. Optionally uninstall legacy **Cyclone PC Companion 3.8.1**.
4. Do not start B1 coding until preflight OK (or explicitly waive device).

## Stages (strict order)

### B1 — Sticky control plane + 4.0.4 Pixel harden
**Status:** DONE in source/CI. Pixel acceptance **UNVERIFIED**.  
Branch: `grok/mobile-4.1-s1-sticky` from `v4.0.4`.  
Goal: permissions/connect stop being a footgun; verify what 4.0.4 claimed.
Deliverables:
1. Accessibility rebound / one-tap repair after force-stop; in-app Phone control READY matches system. — **DONE in source**. Pixel **UNVERIFIED**.
2. PC Gateway Ready UX aligned with One doctor (clear next action). — **DONE in source**. Pixel **UNVERIFIED**.
3. Regression guards around 4.0.4 Shizuku in-app install + profile flow (no Play Store dead-end on Android 16). — **DONE in source**. Pixel **UNVERIFIED**.
4. Pixel checklist doc filled as VERIFIED/UNVERIFIED honestly after smoke. — **DONE as UNVERIFIED** (no assemble/adb this session).
5. Docs `docs/MOBILE_4.1_STAGE1_STICKY.md` + identity **4.1.0-alpha.1** / versionCode **76**. — **DONE**.
Acceptance: force-stop → reopen → one-tap or auto repair to READY; 4.0.4 setup paths smoke-noted. Pixel still **UNVERIFIED**.  
Out of scope: new workspace types; One installer.

### B2 — Dual-plane Session Contract
**Status:** DONE in source/CI. Pixel acceptance **UNVERIFIED**.  
Branch: `grok/mobile-4.1-s2-contract` from B1 (`178c820`).  
Goal: freeze rules shared with One A2/A3.
Deliverables:
1. Canonical `docs/SESSION_CONTRACT.md`: Foreground vs Session Kernel VD vs Layer 2. — **DONE in source**. Pixel **UNVERIFIED**.
2. Fail closed when `session_id` / `display_id` / `workspaceId`+`generation` are mixed illegally. — **DONE in source**. Pixel **UNVERIFIED**.
3. MCP/gateway mobile-side responses include plane metadata for One glass. — **DONE in source**. Pixel **UNVERIFIED**.
4. Optional: raise hot-gate from 1→**2** named VD sessions only if tests prove isolation (not 20). — **DONE as skipped** (stays 1; types/APIs already hold N≥2; isolation proofs remain `V4FoundationTest` / `GatewaySessionBindingTest` / `WorkspaceDisplayPolicyTest`). Pixel **UNVERIFIED**.
5. Tests + docs + alpha.2 / versionCode 77. — **DONE in source**. Pixel **UNVERIFIED**.
Acceptance: unit tests for mismatch matrix; no silent display-0 rewrite; Layer 2 still single mutate lock. Pixel still **UNVERIFIED**.  
Out of scope: true parallel input; Magisk; B3 glass.

### B3 — Task glass + queue observability
**Status:** DONE in source/CI. Pixel acceptance **UNVERIFIED**.  
Branch: `grok/mobile-4.1-s3-glass` from B2 (`0eaef0f`).  
Goal: finish the Gemini-style feel on top of 4.0.1 glass + 4.0.3 queue.
Deliverables:
1. Glass subtitle tracks real Fast Path / skill / Layer 2 slice step. — **DONE in source**. Pixel **UNVERIFIED**.
2. View progress always opens the **exact** plane (VD session frames vs Layer 2 app). — **DONE in source**. Pixel **UNVERIFIED**.
3. Ghost overlay / teardown regressions remain green (`overlayWindowCount==0` after cancel). — **DONE in source**. Pixel **UNVERIFIED**.
4. Ask bar raise / non-blocking human Instagram scroll preserved. — **DONE in source**. Pixel **UNVERIFIED**.
5. Tests + docs + alpha.3 / versionCode 78. — **DONE in source**. Pixel **UNVERIFIED**.
Acceptance: scripted overlay teardown tests; Pixel Instagram-scroll scenario **UNVERIFIED**.  
Out of scope: rewriting setup installer; B4 Fast Path on named VD.

### B4 — Fast Path / skills on owned VD
**Status:** DONE in source/CI. Pixel acceptance **UNVERIFIED**.  
Branch: `grok/mobile-4.1-s4-fastpath-bg` from B3 (`ee077b3`).  
Goal: ClosePaw-quality loop on a **named** workspace, not only default-foreground.
Deliverables:
1. Acceptance: Chrome search ≤90s on mid model with `session_id≠default-foreground` and `displayId>0`. — **DONE in source**. Pixel **UNVERIFIED**.
2. Skill compile/replay bound to that session/display; miss escalates correctly. — **DONE in source**. Pixel **UNVERIFIED**.
3. GATE / Take control / Continue preserved. — **DONE in source**. Pixel **UNVERIFIED**.
4. Evidence folder + docs + alpha.4 / versionCode 79. — **DONE in source**. Pixel **UNVERIFIED**.
Acceptance: recorded timing artifact on Pixel or honest UNVERIFIED with CI harness. Pixel still **UNVERIFIED**.  
Out of scope: vision-first primary loop. Do not start B5.

### B5 — Mobile 4.1.0 release
**Status:** DONE in source/docs. Pixel acceptance **UNVERIFIED**. Tag `v4.1.0` is operator-after-CI — this session does not create it.  
Branch: `grok/mobile-4.1-s5-release` from B4 (`f1e0239` / PR #71).  
Goal: signed **4.1.0** that installs over 4.0.4.
Deliverables:
1. Identity 4.1.0 / versionCode **80**; notes `docs/RELEASE_4.1.md`. — **DONE in source/docs**. Pixel **UNVERIFIED**. Tag not cut.
2. Reuse green Mobile CI APK + update-compatible signer (continuity vs 4.0.4). — **DONE in source/docs**. Pixel **UNVERIFIED**. Tag not cut.
3. Pairing note: requires One ≥ **1.1.0** for full Layer 2 MCP (4.0.4/4.1.0 phone + One 1.0.0 remains foreground-capable). — **DONE in source/docs**. Pixel **UNVERIFIED**.
4. Physical checklist. — **DONE as UNVERIFIED** (no assemble/adb this session). Tag not cut.
Acceptance: GitHub release is operator-after-CI; install over 4.0.4 documented (signer continuity vs wipe). Pixel still **UNVERIFIED**.  
Out of scope: rewrite B1–B4; Magisk; invent device results; claim tag `v4.1.0` exists before Full Release creates it; dispatch `pc-companion-release.yml` or invent a new keystore; cut One/PC A5.

## Coordination

- B1 ∥ A1 allowed.
- B2 contract doc is shared input to One A2/A3.
- Do not require One 1.1 for mobile-only Ask on device; do require One 1.1 for PC Layer 2 operator demos.
