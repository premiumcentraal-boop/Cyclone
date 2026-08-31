# Current Cyclone State

**Product:** Cyclone **3.6.0** (Android `versionCode` 43)  
**Working branch:** `release/beta/cyclone-3.6.0`  
**Release:** https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v3.6.0  
**Pixel 8 UI slices:** UNVERIFIED

This file describes the running 3.6 product. V4 contracts live in `V4_BUILD_BIBLE.md` and are partially already in 3.6 code (page card, act envelope, skill compile drafts). They are not a rewrite of Infrastructure V3.

If you cloned `main` and this file still says 3.5.1, you are on the stale default branch. See `docs/WORKING_LINE.md`.

## Mobile product identity

- Android package: `com.cyclone.mobile`.
- One launcher: `.MainActivity`.
- Minimum Android API: 34; target/compile SDK 35.
- APK: arm64-v8a only, v2-signed, not `testOnly`, pinned Cyclone release keystore.
- Surfaces: Home, Teach, AI, Automations, Brain, Settings.
- Settings is reached from the profile/avatar.
- Full PC + Codex Gateway lives inside the AI experience, not a second launcher.
- User-visible release text comes from `BuildConfig.VERSION_NAME` through `CycloneRelease`.

Teamwork Sniper is a **different package**. Do not treat its APK as a Cyclone Mobile upgrade.

## What 3.6 added on top of 3.5.x

- V4 page-card compact observation (`pageText` / `pageSummary` must survive MCP compact).
- V4 act envelope (after-state, delta, generation; stale elementId fails closed).
- Skill compile writes **disabled drafts** into existing `AutomationStore` (not a second store).
- Agent OS hardening around policy / memory / recovery seams from Infrastructure V3.
- Sideloadable release APK + NSIS preinstall `taskkill` for locked Companion sidecars.

V4 overlay chrome (ANALYSIS → WORKING → LIVE → GATE → DONE) and the four-tool MCP default loop are the V3.7 / V4 slices still ahead. Do not claim 4.0.0 in the UI.

## Device control / perception

`CycloneAccessibilityService` + canonical `PhoneToolExecutor` is the only mutation engine.

Do not create a second engine for AI, automation, teaching, overlay buttons, or the PC gateway. Overlay chrome buttons change Cyclone state only; host taps go through the executor.

## Page awareness

Internal V293 names are compatibility identifiers, not the marketing version.

`observe.semantic` exposes privacy-sanitized `pageText` (`cyclone-page-text-v1`) and `pageSummary` (`cyclone-page-summary-v1`). Compact observation that drops those fields is a regression (`AGENT_CONTEXT_TRUNCATION`).

Screenshot capture over the gateway is still-image evidence. Live USB view uses pinned scrcpy H.264 in PC Companion. Do not add a second MediaProjection architecture without an ADR.

## Learning and skills

App Graph, Adaptive Brain, Follow Me and Routine Teaching are the teaching paths. Gateway/MCP must call into them.

Skill capsules use status `draft | review | verified | quarantined`. Drafts are disabled until human review in Automations. Worker phones may run `verified` only.

## Gateways

- Android: `LocalServerSocket("cyclone_gateway")` / localabstract. Off by default. Random session token. No LAN listener on the phone.
- PC Device Gateway: ADB select + forward, loopback bearer HTTP, witnesses, evidence, debug bundles.
- Android session token ≠ PC HTTP bearer token.
- Android remains the action and verification authority. A fresh PC frame is not semantic verification.

## Codex / any-PC MCP

`tools/codex-phone-mcp` is a constrained STDIO client. Default loop (V4):

1. `phone_status`
2. `phone_locate` / compact observe with goal
3. `phone_act` (after-state required)
4. `phone_skill_run` / `phone_skill_save`

The model does not get generic shell or root tools. Official instructions: `tools/codex-phone-mcp/SKILL.md`.

## Infrastructure V3 (still in force)

Capability registry, PolicyGovernor, Module Supervisor, memory write seam, Context Ledger, Recovery/Safe Mode remain the authorities. V4 does not replace them. One policy authority, one memory write seam, Recovery-only promotion/rollback.

## Release / honest limits

- Combined workflow builds Windows + Android from one SHA.
- 3.6.0 publication is authorized in `release/version.toml`.
- Physical Pixel 8 install/UI path is the acceptance target and is still UNVERIFIED for V4 overlay slices.
- Virtual-device claims need a booted provider, not lifecycle mocks.
- Desktop/Core/Hermes/n8n remain in-tree as a legacy control plane. Phone autonomy must work without them.

## Organizational debt (do not “fix” by rewriting subsystems)

- `main` has not been fast-forwarded to 3.6.
- Historical V2 documents are now stubs; full text is in git history.
- Some classes still use V292/V293 names.
- Some Compose/runtime files are expensive to edit.
- Do not resurrect archived plans as the current spec.
