# Cyclone Mobile 4.1 Stage B3 — Task glass + queue observability

**Identity:** mobile `4.1.0-alpha.3` / versionCode `78`  
**Base:** B2 `4.1.0-alpha.2` (`0eaef0f`, versionCode 77) on B1 `4.1.0-alpha.1` (`178c820`, versionCode 76) on published `v4.0.4` (`38f7628`, versionCode 75)  
**Branch:** `grok/mobile-4.1-s3-glass`  
**Worktree:** `Cyclone-mobile-4.1-s3-glass`  
**Physical Pixel 8:** **UNVERIFIED** — orchestrator mandate: no local assemble, no gradle test, no adb smoke; the device may still be on **4.0.3**. CI green is not device evidence.

Stage B3 finishes Gemini-style task glass on top of 4.0.1 glass + 4.0.3 queue. It does **not** run Fast Path on a named VD, raise VD concurrency, add Magisk, rewrite Cyclone One / PC, or publish 4.1.0.

## Why B3 exists

4.0.1 shipped collapsed task glass (icon, title, View progress, Ask) and 4.0.3 shipped Layer 2 time-sliced workspaces plus the queue. B1 made Phone control READY honest after force-stop. B2 froze the three-plane Session Contract and fail-closed illegal mixes.

The remaining glass gap was observability, not a new plane:

1. The collapsed subtitle was generic ("I'm on it…") instead of the live Fast Path / compiled skill / Layer 2 slice tick.
2. View progress always opened Session Kernel VD frames, so a Layer 2 workspace looked like a named display.
3. Ghost overlay / teardown still had to stay green (`overlayWindowCount==0` after cancel).
4. The Ask bar +12 dp raise and non-focusable / non-touch-modal glass had to keep human Instagram scroll on display 0.

B3 exists so the human can read the real step and open the exact plane without blocking display 0.

## What shipped in source

Identity is `4.1.0-alpha.3` / versionCode **78** on the B2 `0eaef0f` tree. Kotlin production glass / queue observability lives on this branch beside the JVM policy tests. This docs session did not assemble or run those tests.

1. **Real glass subtitle.** `TaskGlassStep` maps executor ticks onto `GlassStepKind.FAST_PATH` / `SKILL` / `LAYER2_SLICE`. Internal Brain bookkeeping is dropped. Collapsed glass `WorkspaceTaskUi.subtitle` is that live label, not a placeholder.

2. **Exact-plane View progress.** `ViewProgressRouter` opens Session Kernel VD session frames (`session_id` named, `displayId>0`) vs the Layer 2 display-0 app (`workspaceId`+`workspaceGeneration`). Mixing those ids still fail-closes with `PLANE_MISMATCH`. View progress is not a fourth plane.

3. **Ghost overlay teardown.** `OverlayWindowRegistry` owns every attached window until synchronous removal. After cancel / failed start, `OverlayChromeRuntime.overlayWindowCount()==0` (`OverlayTeardownContract`). Repeated dismiss is idempotent.

4. **Ask bar raise + human scroll.** `OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP=30` is the 18→30 dp (+12 dp) raise from 4.0.1. Collapsed glass is `notFocusable` + `notTouchModal` (`OverlayChromeWindowPolicy.glass()`), so Instagram on display 0 still receives scroll/focus outside the small bottom panel.

5. **B1 sticky + B2 dual-plane preserved.** Foreground / Session Kernel VD / Layer 2 remain the only planes. Product hot-gate for named VD Ask stays **1**. Do not invent a fourth plane.

`pc_companion` stays **1.0.0**. `device_gateway`, `mcp`, and `python_version` stay **4.0.0**. `channel=development`. `publication_authorized=false` — this is an alpha line, not a published 4.1.0.

## Pixel checklist

Every row is **UNVERIFIED**. This session did not assemble, sideload, or adb-smoke a Pixel. The phone may still be on 4.0.3 / versionCode 74. Latest published remains 4.0.4 / 75.

| Check | Result |
| --- | --- |
| Install `4.1.0-alpha.3` / versionCode 78 over 4.1.0-alpha.2 / 4.0.4 (or 4.0.3) with the update-compatible signer | **UNVERIFIED** |
| Collapsed glass subtitle tracks a real Fast Path tick, then a compiled skill, then a Layer 2 slice (not a generic placeholder) | **UNVERIFIED** |
| View progress on a named VD opens that session's frames (`displayId>0`); View progress on Layer 2 opens the workspace app on display 0 | **UNVERIFIED** |
| Cancel / failed start leaves `overlayWindowCount==0`; no ghost unresponsive bar | **UNVERIFIED** |
| Ask bar sits +12 dp higher than 4.0.0; glass is notFocusable + notTouchModal | **UNVERIFIED** |
| Instagram-scroll scenario: Instagram on display 0 scrolls, changes tabs, and returns Home while a background task runs; Cyclone does not steal focus or tap Instagram | **UNVERIFIED** |
| Named VD Ask hot-gate remains 1; B2 mix rules still fail closed | **UNVERIFIED** |

## Out of scope (B4+)

- B4 Fast Path / skills on a named VD (do not implement here)
- B5 signed `4.1.0` / `docs/RELEASE_4.1.md`
- Magisk / auto-root
- Raising the product hot-gate 1→2
- Inventing a fourth plane
- Cyclone One / PC rewrite
- Editing `docs/SESSION_CONTRACT.md` rules (frozen in B2)

## Handoff to B4

B3 remains **DONE in source/CI** with Pixel **UNVERIFIED**. Do not start B4 in this worktree.

B4 (`grok/mobile-4.1-s4-fastpath-bg`, identity `4.1.0-alpha.4` / versionCode 79) must close the Fast Path / skill loop on a **named** workspace, not only default-foreground:

- Chrome search ≤90s with `session_id≠default-foreground` and `displayId>0`
- Skill compile/replay bound to that session/display; miss escalates correctly
- GATE / Take control / Continue preserved

Do not implement those here.

B4 now lives on `grok/mobile-4.1-s4-fastpath-bg` — see [`docs/MOBILE_4.1_STAGE4_FASTPATH_BG.md`](MOBILE_4.1_STAGE4_FASTPATH_BG.md). This B3 document does not include named-VD Fast Path.

## Tests

JVM (source; CI owns execution):

- `TaskGlassStepTest` — Fast Path / skill / Layer 2 slice subtitles; internal Brain ticks dropped
- `TaskGlassObservabilityTest` — VD subtitle/plane vs Layer 2 slice; named VD blocks Layer 2 glass publish
- `ViewProgressRouterTest` — VD session frames vs Layer 2 app; `PLANE_MISMATCH` when ids mix
- `OverlayTeardownTest` — `overlayWindowCount==0` after cancel / failed attach; registry clear is idempotent
- `OverlayAskBarPolicyTest` — `COMPOSER_BOTTOM_GAP_DP=30` (+12 dp) and glass `notFocusable`+`notTouchModal`
- Existing: `BackgroundGlassPolicyTest` — glass lifecycle/progress, registry teardown, +12 dp delta
- Existing: `OverlayChromeWindowPolicyTest` — glass/compact/halo notFocusable + notTouchModal; expanded composer remains focusable

**Not executed in this agent session** — no gradle, no pytest, no assemble, no adb. When CI later runs, treat green as source/CI evidence only. It does not fill the Pixel table.
