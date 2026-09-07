# Cyclone Mobile 4.1 Stage B4 — Fast Path / skills on named VD

**Identity:** mobile `4.1.0-alpha.4` / versionCode `79`  
**Base:** B3 `4.1.0-alpha.3` (`ee077b3`, versionCode 78) on B2 `4.1.0-alpha.2` (`0eaef0f`, versionCode 77) on B1 `4.1.0-alpha.1` (`178c820`, versionCode 76) on published `v4.0.4` (`38f7628`, versionCode 75)  
**Branch:** `grok/mobile-4.1-s4-fastpath-bg`  
**Worktree:** `Cyclone-mobile-4.1-s4-fastpath-bg`  
**Physical Pixel 8:** **UNVERIFIED** — orchestrator mandate: no local assemble, no gradle test, no adb smoke; the device may still be on **4.0.3**. CI green is not device evidence.

Stage B4 closes the ClosePaw-quality Fast Path / skill loop on a **named** workspace, not only default-foreground. It does **not** raise VD concurrency, add Magisk, rewrite Cyclone One / PC, invent a fourth plane, or publish 4.1.0.

## Why B4 exists

B1 made Phone control READY honest after force-stop. B2 froze the three-plane Session Contract and fail-closed illegal mixes. B3 made glass subtitle / View progress / ghost teardown honest on that contract.

The remaining Fast Path gap was named-workspace execution, not a new plane:

1. Chrome search / Fast Path was still a default-foreground story (`session_id=default-foreground`, display 0), not a named Session Kernel VD.
2. Compiled Chrome skills had to bind to that session/display; miss (`CROSS_SESSION`, `DISPLAY_MISMATCH`, `DISPLAY_ZERO_WORKSPACE`, `SELECTOR_MISS`, `GATE_REQUIRED`) had to escalate to Fast Path LLM, never inject on display 0.
3. GATE / Take control / Continue had to keep the same named `sessionId`+`displayId`; Take control relinquishes inject (human display 0 is intentional take-over, not a silent named-VD rewrite).
4. B1 sticky + B2 mix rules + B3 glass, Instagram scroll on display 0, and hot-gate **1** had to stay.

B4 exists so ClosePaw-quality loop runs on a named workspace (`session_id≠default-foreground`, `displayId>0`).

## What shipped in source

Identity is `4.1.0-alpha.4` / versionCode **79** on the B3 `ee077b3` tree. Kotlin production Fast Path harness / named-VD skills / GATE policy lives on this branch beside the JVM acceptance tests. This docs session did not assemble or run those tests.

1. **NamedWorkspaceFastPath acceptance harness.** Chrome search on `session_id=named-vd` (`≠ default-foreground`), `displayId=7` (`>0`), budget `90_000ms`. Landing is `FastPathLanding` `open_app` Chrome. Multi-turn nav isolation, settle 300ms + fingerprint ladder (+500/+1000); Unchanged is not a second click. Source timing artifact schema records `physicalPixel8=UNVERIFIED` — this session did not capture a device run.

2. **NamedWorkspaceChromeSkill compile/replay.** Bound to that session/display. Miss (`CROSS_SESSION`, `DISPLAY_MISMATCH`, `DISPLAY_ZERO_WORKSPACE`, `SELECTOR_MISS`, `GATE_REQUIRED`) escalates to Fast Path LLM. Vision only on empty tree / `perceptionMode=vision_escalate`. Never inject on display 0.

3. **NamedWorkspaceControlPolicy.** Preserves GATE / Take control / Continue: `userPaused` and `GATE_REQUIRED` block mutate. Continue keeps the same `sessionId`+`displayId`. Take control relinquishes inject (human display 0 is intentional take-over, not a silent named-VD rewrite).

4. **B1 sticky + B2 contract + B3 glass preserved.** Foreground / Session Kernel VD / Layer 2 remain the only planes. Product hot-gate for named VD Ask stays **1**. `PhoneToolExecutor` is the only mutator. Do not invent a fourth plane.

`pc_companion` stays **1.0.0**. `device_gateway`, `mcp`, and `python_version` stay **4.0.0**. `channel=development`. `publication_authorized=false` — this is an alpha line, not a published 4.1.0.

## Pixel checklist

Every row is **UNVERIFIED**. This session did not assemble, sideload, or adb-smoke a Pixel. The phone may still be on 4.0.3 / versionCode 74. Latest published remains 4.0.4 / 75. Chrome ≤90s on a mid-model Pixel is a checklist item, not a recorded run.

| Check | Result |
| --- | --- |
| Install `4.1.0-alpha.4` / versionCode 79 over 4.1.0-alpha.3 / 78 or 4.0.4 with the update-compatible signer | **UNVERIFIED** |
| Chrome search ≤90s on a mid model with `session_id≠default-foreground` and `displayId>0` | **UNVERIFIED** |
| Compiled Chrome skill replay on that named VD (same `sessionId`+`displayId`) | **UNVERIFIED** |
| Skill miss (`CROSS_SESSION` / `DISPLAY_MISMATCH` / `DISPLAY_ZERO_WORKSPACE` / `SELECTOR_MISS` / `GATE_REQUIRED`) escalates to Fast Path LLM; no display-0 inject | **UNVERIFIED** |
| GATE still blocks pay/send | **UNVERIFIED** |
| Take control pauses named-VD inject (human display 0 is intentional take-over) | **UNVERIFIED** |
| Continue with Cyclone resumes the same named VD (`sessionId`+`displayId`) | **UNVERIFIED** |
| Instagram-scroll scenario: Instagram on display 0 still scrolls while a named-VD task runs | **UNVERIFIED** |
| Named VD Ask hot-gate remains 1; B2 mix rules still fail closed | **UNVERIFIED** |

## Out of scope (B5+)

- B5 signed `4.1.0` / `docs/RELEASE_4.1.md`
- Magisk / auto-root
- Vision-first primary loop
- Raising the product hot-gate 1→2
- Inventing a fourth plane
- Cyclone One / PC rewrite
- Editing `docs/SESSION_CONTRACT.md` rules (frozen in B2)

## Handoff to B5

B4 remains **DONE in source/CI** with Pixel **UNVERIFIED**. Do not start B5 in this worktree.

B5 (`grok/mobile-4.1-s5-release`, identity `4.1.0` / final versionCode) must cut the signed mobile release:

- Notes `docs/RELEASE_4.1.md`
- Reuse green Mobile CI APK + update-compatible signer
- Pairing note: One ≥ **1.1.0** for full Layer 2 MCP (4.0.4 phone + One 1.0.0 remains foreground-only)

Do not implement those here.

## Tests

JVM (source; CI owns execution):

- `NamedWorkspaceFastPathTest` — Chrome search harness: `session_id=named-vd`, `displayId=7`, budget 90_000ms, `FastPathLanding` open_app Chrome, nav isolation, settle 300ms + ladder, Unchanged is not a second click, artifact `physicalPixel8=UNVERIFIED`
- `NamedWorkspaceChromeSkillTest` — compile/replay bound to that session/display; miss (`CROSS_SESSION`, `DISPLAY_MISMATCH`, `DISPLAY_ZERO_WORKSPACE`, `SELECTOR_MISS`, `GATE_REQUIRED`) escalates to Fast Path LLM; vision only on empty tree / `vision_escalate`; never inject on display 0
- `NamedWorkspaceControlPolicyTest` — `userPaused` and `GATE_REQUIRED` block mutate; Continue keeps same `sessionId`+`displayId`; Take control relinquishes inject
- `NamedWorkspaceHandoffPreserveTest` — B1 sticky + B2 contract + B3 glass preserved; hot-gate stays 1; `PhoneToolExecutor` only mutator
- Existing Fast Path / skill / GATE / glass tests remain

**Not executed in this agent session** — no gradle, no pytest, no assemble, no adb. When CI later runs, treat green as source/CI evidence only. It does not fill the Pixel table.
