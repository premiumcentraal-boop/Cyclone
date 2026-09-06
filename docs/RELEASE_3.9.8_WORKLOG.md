# Cyclone 3.9.8 implementation checkpoints

Baseline: `70d2b8aa9dab1e7df63d44c1b355c87e8ec55a97`, published Cyclone 3.9.7 / Android versionCode 61.
Implementation branch: `agent/398-runtime-and-task-ui`.

## Scope and checkpoints

1. Preserve this baseline and the 3.9.7 compiler-memory settings; inspect the supplied three-run review.
2. Provider: preserve Muse Contributor identity and consent; improve request compatibility and sanitized failure evidence. Verify official requirements. Live account/device success requires actual authorized credentials and hardware.
3. Runtime: standardize chronological action history, scope ordinary typing authorization to the user task, bound one-photo capture verification, prevent repeated shutter effects and irrelevant learned-route replay, and keep optional learning from changing task outcomes.
4. UX: Ask Cyclone composer with attachment/settings/dictation controls, no live conversation voice mode, swipe-down dismissal, ongoing task notification, live progress/preview with intervention, and an active-screen border. Reflect actual foreground/background capability.
5. Version 3.9.8 with a new versionCode; run tests, lint, product/security/version guards and release assembly. Preserve the published 3.9.7 artifact.

Each implementation checkpoint will be pushed to this branch with its validation state. A checkpoint is not a release or physical-device acceptance claim.

## Evidence available at start

- Supplied `Cyclone-3.9.7-lessons-and-agent-prompt.md` reviewed in full.
- Six attached visual references reviewed.
- 3.9.7 Mobile CI run `34022430771`: success.
- 3.9.7 development publisher run `34022430744`: success.
- The three raw device diagnostic files named by the supplied review were not included in this attachment set.
- No connected Android device or authorized provider account has been established in this session.

## Comparison with earlier work in this chat

The durable earlier work is commit `05f7d6ea88572d4d0170e93778d94b0c758cbc24` (standalone reliability/duo audit). The subsequent uncommitted live/background experiment described in chat is not present in this restored workspace. The 3.9.7 implementation at `49c41d1` is therefore the source baseline for live/background work; do not rebuild or overwrite it from chat snippets.

## Status

Baseline/review checkpoint only. Implementation and 3.9.8 validation are in progress.

## Runtime checkpoint (implementation, validation pending)

- Chronological action ledger makes newest outcomes visible to the planner.
- Provider failures retain HTTP status, safe message/code, request ID and exact selected model; Contributor routing remains explicit with fallback disabled.
- The canonical consolidator obeys local-only preference and distinguishes verification from Android acceptance; optional cloud requests/usage are recorded separately.
- Browser typing authorization comes from the real task and observed address field; named-site completion retains the requested browser.
- Single-photo effect ledger prevents a second shutter after an uncertain result and checks new camera MediaStore evidence when already authorized. Without media access it hands back for review rather than claiming success or retaking.
- Added history, typing and photo regression tests. Android dependencies are still being provisioned; this checkpoint is not a tested release.

## Design checkpoint 1 — Ask Cyclone

Dark charcoal composer, original orbit mark, quiet blue/mint accents, larger editorial empty state, model menu, settings, speech dictation and contextual screen/background menu. Existing submission/preflight/policy paths retained. The provider request guard now checks the preceding runtime checkpoint's explicit routing restrictions. Python guards pass; Android CI is enabled for this branch and will validate this checkpoint. Device visual acceptance remains unverified.
