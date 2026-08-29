# Cyclone V4 Infrastructure Roadmap

**Status:** build order for V4  
**Date:** 30 August 2026  
**Base:** Cyclone 3.5.x + Infrastructure V3 (do not rip V3 out)

This is the implementation sequence. Capability stages in `AUTONOMY_ROADMAP.md` still describe long-horizon maturity. V4 is how we get from “V3 services exist” to “overlay + page card + skill replay”.

Do not start a later slice before the exit of the current slice is green in tests. Physical VERIFIED is called out per slice.

## Slice 0 — Agent contract pack (docs + skill file)

**Owner:** integration / MCP docs  
**Paths:** `docs/agent-system/V4_*.md`, `tools/codex-phone-mcp/SKILL.md`, overlay handoff

Exit:

- New agents read V4 bible after the fast playbook when the task is overlay/page-card/skill/MCP compact.
- `SKILL.md` states the four-tool loop and forbids inventing selectors.

This slice is documentation only. No `versionName` bump.

## Slice 1 — Page card survives compact

**Owner:** Lane E (MCP) + Lane A (observe)  
**Paths:** `tools/codex-phone-mcp/**/compact.py`, observe semantic fixtures, compact tests

Work:

- Compact default is a page card: `pageText`, `pageSummary`, goal-ranked controls, counts, optional cursor.
- Replace the “exactly 12 controls” product assumption.
- Classify silent drops as `AGENT_CONTEXT_TRUNCATION`; `phone_locate` / `ui_search` still find the control.

Exit: JVM/Python tests on golden fixtures. No emulator required.

## Slice 2 — Self-verifying act

**Owner:** Lane D + Lane E + Lane A  
**Paths:** gateway action, MCP `phone_act`, `PhoneToolExecutor` verification

Work:

- Return after page card + delta + `pageChanged` + generation.
- Reject stale elementId.
- Reject free-form coordinate taps from MCP unless explicit vision fallback.
- Unify `device_id` vs legacy serial for observe/screenshot.

Exit: contract tests. Physical smoke: Settings → Apps → Home after-state changes.

## Slice 3 — Skill compile into the existing store

**Owner:** Lane B  
**Paths:** `automation/**`, `brain/memory`, routine capsules, MCP `phone_skill_*`

Work:

- Verified path of 2+ steps → disabled draft capsule.
- Unverified path does not promote.
- Sensitive params stripped. Slots only.
- `phone_skill_run` hits AutomationStore, not a side JSON pile.
- Confidence up on that edge only; failure quarantines that edge.

Exit: unit tests + one Pixel teach or overlay path becomes a draft visible in Automations.

## Slice 4 — Overlay chrome

**Owner:** Lane C + Accessibility  
**Paths:** `apps/mobile/.../ui/**`, overlay controller from Accessibility

Work:

- One overlay, states IDLE / ANALYSIS / WORKING / LIVE / GATE / DONE.
- Copy deck from the bible. Driving banner while acting.
- Overlay buttons only change Cyclone state.
- GATE before pay/send/delete/grant via PolicyGovernor.

Exit: Pixel demo of the food-order vertical **or** honest skip with fixture-driven overlay state tests plus physical note.

Do not restyle Home. Do not add a seventh tab.

## Slice 5 — Golden corpus + locate

**Owner:** Lane A + Teach  
**Paths:** Page Awareness Sandbox export, `vault-lab/golden-pages/` (or agreed fixtures path)

Work:

- 12 golden pages from the 2.9.3 set, captured on Pixel 8.
- `phone_locate(goal)` = readiness + page card + ranked hits.
- Failures become new folders, not chat stories.

Exit: locate finds the expected control on each golden page in tests.

## Slice 6 — App cards + manager/executor wiring

**Owner:** Lane B + Lane C  
**Paths:** `brain/app_cards/`, Adaptive Brain planner used by gateway/MCP

Work:

- Package-keyed markdown cards, token-capped, injected when that app is foreground.
- MCP uses Brain-first: verified skill → card + first hop → compact plan.
- No CodeAct.

Exit: second run of a known Settings path makes zero model calls when a verified skill exists (measured in a test double if no live model).

## Slice 7 — Placeholder redaction round-trip

**Owner:** gateway + policy  
**Paths:** existing redaction seams, `phone.type`

Work:

- Deterministic detectors (email, phone, Luhn, IBAN) emit stable placeholders in observe.
- Typing a placeholder substitutes the real value on-device.
- No 154 MB NER required for v1.

Exit: tests that a card number never appears in compact observe and that type-by-placeholder does not persist plaintext in Brain.

## Slice 8 — Teacher / worker fleet

**Owner:** Lane D + Companion  
**Paths:** Device Gateway inventory, Companion wall, skill sync

Work:

- One `device_id` contract.
- Teacher may write draft/verified. Workers execute `verified` only.
- Doctor per device. OEM restricted-settings wizard in Settings, not a wiki.

Exit: two attached devices, one skill version, worker cannot promote.

Physical farm claims need two real phones. Do not mark VERIFIED from mocks.

## Slice 9 — Optional hands backend

**Owner:** Lane A  
**Paths:** DeviceBackend / executor adapter

Work:

- Optional Mobilerun/Portal HTTP adapter behind `PhoneToolExecutor`.
- No AGPL source in the APK.
- Default remains Cyclone Accessibility.

Exit: adapter is off by default; tests prove policy still wraps every mutation.

## Slice 10 — Later gates (not V4 blockers)

- AndroidWorld homework run, published only with harness SHA.
- Skill import/export with provenance.
- Cloud-phone worker backend.
- AppFunctions consumer **if** platform APIs are public on devices we support.

## Versioning

- Docs-only slices: no APK, no `versionName` change.
- Slices 1–3 may ship inside 3.5.x / 3.6 betas (`versionCode` up per APK).
- Overlay + first vertical is the marketing moment for **4.0.0**, and only when slices 1–4 are green on Pixel.
- Do not advertise V4 in the UI until that APK exists.

## Anti-goals for every slice

- Second Accessibility service, second Brain, second executor.
- Dumping full trees into Codex “just in case.”
- Promoting emulator paths into production Brain.
- Waiting on Hermes.
