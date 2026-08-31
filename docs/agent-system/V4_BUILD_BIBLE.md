# Cyclone V4 Build Bible

**Status:** canonical steering for V4 infrastructure work  
**Date:** 30 August 2026  
**Running product under this bible:** Cyclone 3.6.0  
**Does not replace:** root `AGENTS.md` invariants, Infrastructure V3 ownership, or `PhoneToolExecutor`

Read this after `AGENTS.md` and `FAST_WORK_AND_TOKEN_PLAYBOOK.md` when the task is V4 overlay, page-card, skill compile, MCP compact, or fleet replay.

3.6 is the running product. V4 is the next infrastructure layer on top of V3/3.6, not a rewrite.

## One sentence

Stay in the conversation. Let Cyclone finish the job. Compile the verified path into a skill that the next phone can run without the model debating the screen.

## Product name for V4

Cyclone is a **phone skill OS**:

- Gemini-style overlay on the current app (Analysis → Confirm → Working → Live view → Gate).
- Any PC AI talks through four MCP tools.
- Verified skills live in the existing AutomationStore / Brain memory seam.
- One teacher device writes skills. Worker phones only run `verified`.

We do not compete with Mobilerun on raw AndroidWorld taps. We compete on **week-2 replay** and **user-owned skills**.

## Invariants (unchanged)

- Package `com.cyclone.mobile`. Launcher `.MainActivity`.
- Surfaces: Home, Teach, AI, Automations, Brain, Settings.
- One mutation engine: `PhoneToolExecutor`.
- Semantic first. Vision last.
- Observation-scoped element IDs. Re-observe after page-changing acts.
- Transport success is not action success. After-state required.
- Brain is hints until verified.
- No generic shell/root tools for the model.
- No secrets in learning stores.
- Policy stays on the phone. PC cannot auto-approve pay/send/delete/grant.
- CI green ≠ physical VERIFIED.
- Do not copy AGPL Portal source into the APK. Optional HTTP adapter only.

## V4 stack

```text
host app (Messages, Settings, shop, …)
        ↑ overlay chrome only (stop / take control / confirm)
Cyclone overlay  ANALYSIS | WORKING | LIVE | GATE | DONE
        ↓
page card + locate(goal)
        ↓
verified skill? --yes--> PhoneToolExecutor --> after-state
        no
Brain / App Graph first hop --> compact plan --> act --> after-state
        ↓
2+ verified steps --> draft skill (disabled) --> human review --> verified
        ↓
teacher Pixel writes     worker phones run verified only
```

Overlay buttons never tap the host app. They only change Cyclone state. Host taps go through `PhoneToolExecutor`.

## Contracts V4 must freeze

### Page card (compact default)

Every compact observe / locate payload includes:

- `package`, `activity`, `pageKey`, title
- `pageText` (`cyclone-page-text-v1`)
- `pageSummary` (`cyclone-page-summary-v1`)
- goal-ranked interactive candidates
- last transition
- up to five verified next-hop hints
- raw / semantic / agent counts
- optional `nextCursor` for more controls

`pageText` and `pageSummary` must survive MCP `compact_observation()`. Dropping them is a regression.

### Act envelope

`phone_act` returns:

```text
ok, pageChanged,
before.pageKey, after.pageKey,
after page card,
delta (appeared / disappeared / focused),
errorClass,
generation (observation id that the elementId belonged to)
```

Stale `elementId` / generation mismatch fails closed.

### Skill capsule

```text
id, app, goal
when: pageKey + preconditions
steps: When → Then → Check
selectors: ranked + confidence
verifiers: after pageKey / text / gone-control
params: slots only (never raw secrets)
evidence: last verified traces
status: draft | review | verified | quarantined
```

Write only through the Brain memory seam. Failures lower that edge only. Duplicate pages merge.

### MCP default surface

1. `phone_status`
2. `phone_locate` (or `phone_observe` with `goal`)
3. `phone_act`
4. `phone_skill_run` / `phone_skill_save`

Everything else is advanced. Official agent instructions live at `tools/codex-phone-mcp/SKILL.md`.

## Overlay copy (do not improvise)

| Slot | String |
|---|---|
| Analysis title | Analysis |
| Working title | Task automation |
| Working body | I'm on it. I'll let you know when this is ready to complete. You can leave this screen. |
| Status | Working on this task |
| Primary | View progress |
| Confirm | Do this |
| Commerce | Order this from |
| Live left | Stop task |
| Live right | Take control |
| Composer | Ask Cyclone |
| Gate | Cyclone needs you to confirm before finishing this. |
| Done | Saved as a draft skill. Review it in Automations before it can run alone. |
| Legal | Supervise closely. Interrupt when needed. Select apps only. Compatibility varies. |

Never say: “Let me think about the best approach,” “I noticed several possible buttons,” or “I have placed the order” unless GATE passed.

Visual spec: `docs/design/mobile-v32/SUPER_APP_OVERLAY_HANDOFF.md`.

## Lab method

1. Capture golden pages on the Pixel 8 via Teach → Page Awareness Sandbox.
2. Contract-test page card, act envelope, skill compile against those fixtures.
3. Optional AVD smoke.
4. Physical Pixel acceptance only for VERIFIED.

Emulator runs must not promote skills into production Brain.

## Metrics that mean V4 is real

| Metric | Target |
|---|---|
| Skill hit rate on repeated goals | ≥70% zero-model runs |
| Locate-not-lost | labelled control never silently dropped from the card |
| Replay on a second phone | ≥90% of `verified` skills on the same app version |
| Pairing | new phone to doctor READY in <10 minutes |
| Accidental pay/send | zero without GATE |

## Definition of done for a V4 patch

- Invariants hold.
- Focused tests cover the contract you touched.
- Overlay copy matches the deck if you touched chrome.
- Physical status explicit.
- Handoff lists SHA, files, tests, skipped gates.
- No `4.0.0` versionName until slices 1–4 are green on Pixel.
