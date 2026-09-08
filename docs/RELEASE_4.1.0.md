# Cyclone 4.1.0 — Session Contract Mobile

Mobile **`4.1.0`** (Android versionCode **80**, package `com.cyclone.mobile`). This tree keeps Cyclone One / `pc_companion` **`1.0.0`**. Device gateway and MCP adapters remain **`4.0.0`**.

Base: published mobile **`v4.0.4`** (`38f7628`, versionCode **75**). Stacked B1–B4 on that line; B5 is this release lane. Do not rewrite that history.

| Stage | PR | Branch / tip | Identity |
| --- | --- | --- | --- |
| B1 Sticky | #65 | `grok/mobile-4.1-s1-sticky` tip `178c820` | `4.1.0-alpha.1` / 76 |
| B2 Session Contract | #67 | `grok/mobile-4.1-s2-contract` tip `0eaef0f` | `4.1.0-alpha.2` / 77 |
| B3 Task glass | #69 | `grok/mobile-4.1-s3-glass` tip `ee077b3` | `4.1.0-alpha.3` / 78 |
| B4 Fast Path named VD | #71 | `grok/mobile-4.1-s4-fastpath-bg` tip `f1e0239` | `4.1.0-alpha.4` / 79 |
| B5 Release lane | this PR | `grok/mobile-4.1-s5-release` tip `d249f2a` + cut commit | **`4.1.0` / 80** |

Physical Pixel 8: **UNVERIFIED**. `publication_authorized=true` authorizes the same Full Release path as `v4.0.4` (`mobile-publish-v3910.yml` on `release/cyclone-mobile-v4.1.0`). This document is not device evidence. Protocol-fix tip `d249f2a` Mobile CI **SUCCESS**: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34172841433. Alpha identities on the way here (`4.1.0-alpha.1` … `alpha.4`, versionCodes 76–79) were not published 4.1 APKs. The release identity is **`4.1.0` / 80**.

## Session Contract Mobile

4.1 hardens and integrates 4.0.1–4.0.4. It does not redo those features.

4.0.1–4.0.4 already shipped task glass, one-button background / Shizuku setup, Layer 2 workspaces, and in-app Shizuku / profile setup. Physical Pixel acceptance for those claims is still **UNVERIFIED**. One / MCP did not absorb Layer 2, and dual-plane rules (named Shizuku VD vs Layer 2 display-0 workspaces) were easy to misuse. 4.1 freezes the contract, makes Phone control and glass honest, and runs Fast Path / skills on a named virtual display.

North star: the human owns display 0. Named Shizuku virtual displays run Ask / Fast Path / skills. Layer 2 time-slices display-0 app / profile workspaces under one mutate lock. Accessibility and the PC gateway stay sticky. Signed installs update cleanly over 4.0.4 when the same update-compatible signer is used.

The phone remains authority. `PhoneToolExecutor` is the only mutation engine. Every observe / act is bound to a Session Contract plane. Ordinary navigation uses accessibility Page Cards and a local fingerprint settle. Stable Fast Path sequences compile into deterministic skills bound to that `sessionId` + `displayId`. Cyclone One remains glass. The PC does not run a second Fast Path, skill compiler, or `PhoneToolExecutor`.

Three planes, no fourth:

| Plane | Identity | Display | Mutation model |
| --- | --- | --- | --- |
| Foreground human | `session_id=default-foreground` | `displayId=0` | Direct; human may hold input |
| Session Kernel VD | named `session_id` | `displayId>0` required | Isolated VD inject; never display 0 |
| Layer 2 workspace | `workspaceId` + `workspaceGeneration` | stays display 0 | Time-sliced global mutate lock |

Product still hot-gates **one** concurrent named-VD Ask task. Types and APIs already hold N≥2. Do not claim 20 concurrent virtual displays.

Control loop (named VD and default-foreground share the same settle; named VD never injects on display 0):

```text
compiled skill replay (goal + pageKey + sessionId + displayId)
→ miss: planner landing (open_app / intent) or Fast Path LLM / UI sub-agent
→ a11y Page Card (elementIndex)
→ one screen-changing phone tool (or same-page form batch)
→ settle 300ms → fingerprint → if Unchanged: +500ms, then +1000ms
→ still Unchanged: verified=false; do not click again
→ Goal Contracts / semantic witnesses decide task completion
```

Transport success is not task success. Vision is an escalate when the tree is empty or `perceptionMode=vision_escalate`, not the primary loop.

## Pairing with Cyclone One

This pairing note is the 4.1 contract. Do not skip it.

- **Full Layer 2 MCP** (`phone_workspace` list / register / switch / pause / release / arm / next, and `workspaceId` + `workspaceGeneration` on `phone_act.params`) requires Cyclone One **≥ 1.1.0**.
- A **4.0.4 phone + One 1.0.0** remains **foreground-capable**: USB / trust / default-foreground observe / act is still the 4.0.4 pairing contract.
- Do **not** require One 1.1 for mobile-only Ask on the device.
- This mobile **4.1.0** tree keeps `pc_companion` **1.0.0**. One / PC **1.1.0** is a separate A5 cut. Mixing One 1.0.0 glass with a 4.1.0 phone is OK for foreground; it is **not** a Layer 2 operator demo.
- Full Layer 2 MCP via One ≥ 1.1.0 is **UNVERIFIED**. One 1.1 is A5.

Android gateway omitted identity still binds default-foreground for One 1.0.0 pairing. It never invents a named VD or rewrites a named session onto display 0. MCP UI observe / act missing `session_id` is `SESSION_REQUIRED`.

## What shipped

Shipped in source / CI. Physical Pixel 8 remains **UNVERIFIED**. CI green is not device evidence.

### Sticky control plane (B1, PR #65)

- Phone control READY is honest after force-stop. `PhoneControlReadiness` READY = Android Accessibility setting enabled **and** the Cyclone service bound. Setting on + service dead after force-stop → **Repair** (not Ready). Setting off → **Enable**, even if a stale bound flag remains. One tap opens Android Accessibility settings. Settings, setup wizard, background readiness, and PC Gateway status share that snapshot.
- `GatewayReadyDoctor` emits one next action (`TURN_ON_GATEWAY` → `FIX_USB_BRIDGE` → `REPAIR_PHONE_CONTROL` / `ENABLE_PHONE_CONTROL` → `CONFIRM_AI_TRUST` → `PAIR_AI_TRUST` → `WAIT_FOR_PC`). The control center and AI card show that action when not Ready. Transport-on is not Ready.
- 4.0.4 Shizuku / profile harden stays the source of truth. `OfficialHelperInstallPolicy` pins GitHub Shizuku **13.6.0** (SHA-256 + package `moe.shizuku.privileged.api`). Download refuses Play Store / `market://`. Installer Back stays available, including system Back. Profile create stays `create-user --managed`; no Shelter / Island shopping copy in the normal flow.

### Dual-plane Session Contract (B2, PR #67)

- Canonical [`SESSION_CONTRACT.md`](SESSION_CONTRACT.md) is frozen for Mobile B2 + One A2 / A3: Foreground vs Session Kernel VD vs Layer 2. No fourth plane.
- Fail-closed mix matrix: MCP UI missing `session_id` → `SESSION_REQUIRED`. Named VD missing / `0` display, or default-foreground + nonzero display → `SESSION_DISPLAY_MISMATCH`. Named VD mixed with `workspaceId`, or Layer 2 ids mixed with `displayId>0` → `PLANE_MISMATCH`. `workspaceId` XOR `workspaceGeneration` → `WORKSPACE_GENERATION_REQUIRED`. Layer 2 mutate with a stale generation → `MUTATE_LOCK` / `WORKSPACE_GENERATION_STALE`. No silent display-0 rewrite.
- Gateway / MCP responses include `plane` `{kind, label, sessionId, displayId, workspaceId, workspaceGeneration}` so One glass labels without guessing.
- Named-VD Ask hot-gate stays **1**. Isolation proofs remain `V4FoundationTest` / `GatewaySessionBindingTest` / `WorkspaceDisplayPolicyTest`. Raising 1→2 was skipped as scope creep.

### Task glass + queue observability (B3, PR #69)

- Collapsed glass subtitle tracks the live Fast Path / compiled skill / Layer 2 slice tick (`TaskGlassStep` → `GlassStepKind.FAST_PATH` / `SKILL` / `LAYER2_SLICE`). Internal Brain bookkeeping is dropped. The subtitle is that live label, not a generic placeholder.
- View progress opens the exact plane: Session Kernel VD session frames (`session_id` named, `displayId>0`) vs the Layer 2 display-0 app (`workspaceId` + `workspaceGeneration`). Mixing those ids still fail-closes with `PLANE_MISMATCH`. View progress is not a fourth plane.
- Ghost overlay / teardown stays green: after cancel / failed start, `overlayWindowCount==0`. Repeated dismiss is idempotent.
- Ask bar raise from 4.0.1 is preserved (`COMPOSER_BOTTOM_GAP_DP=30`, the 18→30 dp / +12 dp raise). Collapsed glass is `notFocusable` + `notTouchModal`, so Instagram on display 0 still receives scroll / focus outside the small bottom panel.

### Fast Path / skills on named VD (B4, PR #71)

- Named-VD Fast Path: Chrome search harness on `session_id≠default-foreground` and `displayId>0`, budget **≤90s**. Landing is `FastPathLanding` `open_app` Chrome. Multi-turn nav isolation, settle 300ms + fingerprint ladder (+500 / +1000). Unchanged is not a second click. The source harness records `physicalPixel8=UNVERIFIED`; this cut did not capture a device timing run.
- Skill compile / replay is bound to that named `sessionId` + `displayId`. Miss (`CROSS_SESSION`, `DISPLAY_MISMATCH`, `DISPLAY_ZERO_WORKSPACE`, `SELECTOR_MISS`, `GATE_REQUIRED`) escalates to Fast Path LLM. Vision only on empty tree / `perceptionMode=vision_escalate`. Never inject on display 0 for a named VD.
- GATE / Take control / Continue are preserved on that same `sessionId` + `displayId`. `userPaused` and `GATE_REQUIRED` block mutate. Continue keeps the same named identity. Take control relinquishes inject (human display 0 is intentional take-over, not a silent named-VD rewrite).

4.0.1 collapsed task glass, 4.0.2 one-button background install, 4.0.3 Layer 2 workspaces + mutate lock, 4.0.4 in-app Shizuku 13.6.0 + profile flow, and V4 Fast Path / Session Kernel / Skill Compiler / GATE remain through the stack.

## Physical Pixel 8 checklist — UNVERIFIED

No item below was verified on a physical Pixel 8 for this cut. CI green is not device evidence. Leave every box unchecked until a human runs it on hardware. The Pixel may still be on **4.0.3** / versionCode **74** today. Latest published remains **4.0.4** / **75**.

- [ ] Install **4.1.0** / versionCode **80** over **4.0.4** / **75** with the update-compatible signer (`LEGACY_UPDATE_COMPATIBLE_DEV_KEY`). If the signer does not match, documented wipe — do not claim an in-place update succeeded. — **UNVERIFIED**
- [ ] Pixel 8 USB connected; Shizuku authorized (Android 15+ required for background workspace); Accessibility and notification listener granted as required — **UNVERIFIED**
- [ ] B1 sticky READY: force-stop Cyclone → reopen → Phone control is not false-Ready → one-tap Enable / Repair → READY matches system Accessibility — **UNVERIFIED**
- [ ] B1 gateway next-action: PC Gateway off / a11y off / no AI trust each shows a clear next action; transport-on is not Ready — **UNVERIFIED**
- [ ] B1 helper without Play Store: background tasks install official Shizuku 13.6.0 in-app; cancel / fail still has Back — **UNVERIFIED**
- [ ] B2 fail-closed mix: MCP missing `session_id` → `SESSION_REQUIRED`; named missing / `0` display and default-foreground + nonzero display → `SESSION_DISPLAY_MISMATCH`; named + workspace ids → `PLANE_MISMATCH`; workspace XOR generation → `WORKSPACE_GENERATION_REQUIRED`; Layer 2 stale generation fail-closes. Responses include `plane` metadata. No silent display-0 rewrite — **UNVERIFIED**
- [ ] One **1.0.0** default-foreground pairing still works (USB / trust / `session_id=default-foreground` observe / act) — **UNVERIFIED**
- [ ] B3 glass subtitle tracks a real Fast Path tick, then a compiled skill, then a Layer 2 slice (not a generic placeholder) — **UNVERIFIED**
- [ ] B3 View progress opens the exact plane (named VD frames vs Layer 2 app on display 0) — **UNVERIFIED**
- [ ] B3 ghost teardown: cancel / failed start leaves `overlayWindowCount==0`; no ghost unresponsive bar — **UNVERIFIED**
- [ ] B3 Instagram scroll: Instagram on display 0 scrolls, changes tabs, and returns Home while a background task runs; Cyclone does not steal focus or tap Instagram. Ask bar sits +12 dp higher than 4.0.0 — **UNVERIFIED**
- [ ] B4 Chrome search ≤90s on a mid model with `session_id≠default-foreground` and `displayId>0` — **UNVERIFIED**
- [ ] B4 compiled Chrome skill replay on that named VD (same `sessionId` + `displayId`) — **UNVERIFIED**
- [ ] B4 skill miss (`CROSS_SESSION` / `DISPLAY_MISMATCH` / `DISPLAY_ZERO_WORKSPACE` / `SELECTOR_MISS` / `GATE_REQUIRED`) escalates to Fast Path LLM; no display-0 inject — **UNVERIFIED**
- [ ] GATE still blocks pay / send / delete / permission / authentication-sensitive actions — **UNVERIFIED**
- [ ] Take control pauses named-VD inject (human display 0 is intentional take-over) — **UNVERIFIED**
- [ ] Continue with Cyclone resumes the same named VD (`sessionId` + `displayId`) — **UNVERIFIED**
- [ ] Named VD Ask hot-gate remains **1** — **UNVERIFIED**
- [ ] Full Layer 2 MCP via One **≥ 1.1.0** (`phone_workspace` list / register / switch / pause / release / arm / next and `workspaceId` + `workspaceGeneration` on `phone_act.params`) — **UNVERIFIED** (One 1.1 is A5)

Background work still requires Android 15+, authorized Shizuku, Accessibility, and an app that supports isolated displays. Apps already on display 0, unsupported OEM task layouts, secure surfaces, unsupported text input and cross-app transitions fail closed or require handoff. Process death revokes execution and does not restore autonomous authority.

## Upgrade from 4.0.4

| Surface | From | To |
| --- | --- | --- |
| Mobile APK | 4.0.4 / versionCode 75 (`v4.0.4` @ `38f7628`) | **4.1.0** / versionCode **80** |
| Cyclone One (Windows glass) | **1.0.0** (cut with `v4.0.0`; pairing contract unchanged for foreground) | still **1.0.0** on this mobile tree; One **1.1.0** is a separate A5 cut |
| Device gateway / MCP packages | **4.0.0** | **4.0.0** (unchanged on this mobile tree) |
| `pc_companion` | **1.0.0** | **1.0.0** |

Breaking / must-change:

- MCP clients that mix named VD identity with Layer 2 ids, or Layer 2 ids with `displayId>0`, now fail closed (`PLANE_MISMATCH`). Missing `session_id` on MCP UI observe / act remains `SESSION_REQUIRED`.
- Full Layer 2 operator demos over MCP require One **≥ 1.1.0**. One 1.0.0 glass + 4.1.0 phone is foreground-capable only.

Retained from 4.0.4 / V4:

- Take control / Continue (intentional human take-over onto display 0; not a silent inject fallback).
- GATE confirm for pay / send / delete / permission / authentication-sensitive actions.
- One hot named-VD Ask workspace; follow-ups still require explicit start after releasing the previous workspace.
- Exact-session progress frames; no foreground screenshot fallback for a missing workspace frame.
- Phone remains authority. PC pairing is still optional for standalone Android Ask.
- In-app official Shizuku 13.6.0, Back-always helper, and managed-profile create without Shelter shopping.

Install / pairing notes:

- Android `versionCode` **80** is greater than **75**; in-place upgrade from 4.0.4 is the expected mobile path **if** the same update-compatible dev signer (`LEGACY_UPDATE_COMPATIBLE_DEV_KEY`) is used. If the signer does not match, documented wipe — do not claim an in-place update succeeded.
- Publication reuses the exact green Mobile CI APK (no second Android assembly). `mobile-publish-v3910.yml` signs with the historical update-compatible dev signer. `mobile-release.yml` (rotated-key secrets) is the blocked fallback.
- `publication_authorized=true` so Full Release can sign the exact green Mobile CI APK with the historical update-compatible dev signer. Physical Pixel 8 remains **UNVERIFIED**.
- Loopback gateway only. Do not expose generic shell / root / ADB as model tools.

Operator commands live in [`MOBILE_4.1_STAGE5_RELEASE.md`](MOBILE_4.1_STAGE5_RELEASE.md).

## Limits / non-goals

- No Magisk, auto-root, or a second mutation engine on Windows.
- No 20 concurrent virtual displays, 20 concurrent VLMs, or 20 hot LLM agents. Extra session tiles are inventory.
- One hot named-VD Ask task. Hot-gate stays 1.
- Secrets (passwords, OTPs, API keys, payment data, raw typed secret values) are not persisted in Brain, playbooks, compiled skills, or diagnostics.
- ColorOS / OxygenOS may ignore `OWN_DISPLAY_GROUP` or steal focus. Fail closed rather than acting on display 0.
- Virtual display + Accessibility `windowsOnAllDisplays` is OEM-dependent. An empty workspace tree is `perceptionMode=vision_escalate`, not permission to inject on the human display.
- One 1.0.0 mixed with a 4.1.0 phone is not a Layer 2 operator demo.
- `publication_authorized=true` on the cut commit. Physical remains **UNVERIFIED**.
- Do not claim tag `v4.1.0` exists.

## How this is published

Do not treat these notes as a published GitHub Release. Tag **`v4.1.0`** only after the stack merges in order **#65 → #67 → #69 → #71**, then the B5 release PR on `grok/mobile-4.1-s5-release`. Operator commands live in [`MOBILE_4.1_STAGE5_RELEASE.md`](MOBILE_4.1_STAGE5_RELEASE.md).

Intended cut:

1. Merge Sticky, Session Contract, Task glass, and Fast Path named VD (PRs #65, #67, #69, #71) without rewriting their history.
2. Land B5 (this notes file + the version / CI / packaging work owned by the other B5 agents).
3. Require Mobile CI (unit tests, lint, APK assembly) and repository product / security / version guards on the merged commit.
4. Create immutable tag `v4.1.0` from that commit. Pair the Android APK (`4.1.0` / versionCode 80) with the existing Cyclone One `1.0.0` installer for foreground pairing. Do not wait on One 1.1.0 (A5) to cut mobile 4.1.0.
5. Authorize publication (`publication_authorized=true`) and push `release/cyclone-mobile-v4.1.0` so `mobile-publish-v3910.yml` reuses the exact green Mobile CI APK and the historical 4.0.4-compatible signer. Do not publish from a local Gradle tree. Physical Pixel 8 remains **UNVERIFIED**.

Alpha identities on the way here (`4.1.0-alpha.1` … `alpha.4`, versionCodes 76–79) were not published 4.1 APKs. The release identity is **`4.1.0` / 80**, with `pc_companion` still **`1.0.0`**.
