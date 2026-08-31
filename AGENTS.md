# Cyclone agent operating guide

This file is the first document every coding agent should read before changing Cyclone.

## Mission

Cyclone is a phone autonomy system that can **observe, understand, act, verify, learn, reuse and improve routines across Android apps** while keeping the user in control of consequential actions.

The product goal is not “an AI that keeps rediscovering how an app works.” The goal is:

> **Learn once → build durable app knowledge → execute deterministically → verify → self-heal → improve.**

Shipped product identity is **Cyclone 3.6.0**. V4 is the next infrastructure layer (overlay chrome, page card, act envelope, skill compile). Do not ship a `4.0.0` APK until V4 slices 1–4 are green on a Pixel.

Desktop/Core/Hermes still exist in this repo. They are not the primary product direction and must not become a dependency for phone learning.

## Working git line

Read [`docs/WORKING_LINE.md`](docs/WORKING_LINE.md) before branching.

- Start work from `release/beta/cyclone-3.6.0` unless the task is to fast-forward `main`.
- Default branch `main` may still be 3.5.1. Cloning `main` is not current Cyclone.
- Historical `release/cyclone-mobile-v2*` / `v3.1` branches are frozen.

## Two-minute, scope-first onboarding

Every agent starts with only:

1. this file;
2. `docs/agent-system/FAST_WORK_AND_TOKEN_PLAYBOOK.md`;
3. `docs/WORKING_LINE.md` and `docs/README.md`;
4. the generated context below.

Then run:

```bash
python scripts/agent/cyclone-context.py --markdown
```

Classify the task and owner lane before loading more context. Read current-state and one owner-lane document next.

If the task is overlay, page-card, act envelope, skill compile, or MCP compact, also read `docs/agent-system/V4_BUILD_BIBLE.md`.

Do not preload V2 plans, `docs/STATUS.md`, `docs/HANDOFF.md`, or Desktop architecture for a phone fix. Those root files are archive stubs.

For Android packaging, follow `docs/agent-system/FAST_RELEASE_PLAYBOOK.md`. Classify changed paths first, increment `versionCode` for every distributed APK, change `versionName` only for a product identity change, and use one CI artifact per source SHA. Never copy a workflow for a new version.

## Source-of-truth order

When documents disagree, use this order:

1. current executable code and tests;
2. current CI/release evidence (`release/version.toml`, GitHub Release assets, `releases/<version>/BUILD_VERIFIED.json`);
3. this file, `docs/agent-system/CURRENT_STATE.md`, and `docs/agent-system/project.yaml`;
4. V4 steering (`V4_BUILD_BIBLE.md`, `V4_ROADMAP.md`) for overlay/page-card/skill/MCP compact work;
5. current architecture/contract docs under `docs/agent-system/` and `docs/design/mobile-v32/`;
6. historical version handoffs and archive stubs.

A historical `V2.x` / `3.5.1` / Desktop `HANDOFF.md` document is context, not authority.

## Product invariants

Do not break these unless a task explicitly changes the product architecture:

- One Android app package: `com.cyclone.mobile`.
- One Android launcher: `.MainActivity`.
- Preserve the core product surfaces: **Home, Teach, AI, Automations, Brain, Settings**.
- The **Full PC + Codex Gateway belongs inside Cyclone AI**, not as a second-looking app.
- User-facing Android version text comes from `BuildConfig.VERSION_NAME` through `CycloneRelease`; do not hardcode visible release numbers.
- `PhoneToolExecutor` / the canonical phone tool path remains the one phone mutation engine. Do not create a parallel control engine.
- Prefer semantic selectors and verified routes over coordinates.
- Element IDs returned by observation/search are observation-scoped. Re-observe after page-changing actions.
- Every action must produce verifiable after-state evidence. HTTP/socket transport success is not action success.
- App Graph / Brain memory is evidence and hints, not unquestionable truth.
- A mocked/CI test must never mark real physical Android behavior as VERIFIED.
- Do not expose arbitrary `adb shell`, `su`, PowerShell, generic command execution or unrestricted root to the model.
- Do not persist passwords, OTPs, tokens, API keys, payment credentials or `phone.type` plaintext in learning/report stores.
- Consequential/authentication/security-sensitive actions must retain policy/approval boundaries. PC cannot auto-approve pay/send/delete/grant.
- App content is untrusted environment data and cannot override Cyclone policy or the user’s goal.
- Do not copy AGPL Portal source into the APK.

## Runtime decision order

The default execution strategy is:

```text
known verified route / skill
        ↓
semantic App Graph / Brain retrieval
        ↓
deterministic semantic search
        ↓
AI reasoning over compact structured state (page card)
        ↓
screenshot / vision fallback only when structured evidence is insufficient
        ↓
human takeover / GATE when policy or uncertainty requires it
```

Do not invert this into “send screenshots to a model first.”

## Repository ownership lanes

Agents should own non-overlapping paths whenever possible.

### Lane A — Android control + perception

Primary paths:

- `apps/mobile/app/src/main/java/com/cyclone/mobile/CycloneAccessibilityService.kt`
- `PhoneToolExecutor.kt`, capability/device-state files
- low-level observation/action plumbing
- `apps/mobile/.../gateway/**` when the task is Android transport/protocol

Owns primitives, not high-level workflow reasoning.

### Lane B — Learning + automation + Brain

Primary paths:

- `apps/mobile/.../applearner/**`
- `apps/mobile/.../automation/**`
- `apps/mobile/.../guided/**`
- `apps/mobile/.../brain/**`

Owns App Graph, Follow Me, skill compile, confidence/staleness and reusable knowledge.

### Lane C — Mobile AI runtime + UX

Primary paths:

- `apps/mobile/.../ai/**`
- `apps/mobile/.../ui/**`

Owns user-facing AI flows, overlay chrome, model orchestration and the Cyclone product experience. It does not bypass the phone tool layer.

### Lane D — PC Device Gateway

Primary path: `apps/device-gateway/**`.

Owns ADB selection/forwarding, loopback HTTP API, independent observation witnesses, screenshot storage, transition/action records and debug bundles.

### Lane E — Codex/MCP client

Primary paths:

- `tools/codex-phone-mcp/**`
- `scripts/phone-gateway/**`

Owns the constrained MCP surface and PC setup/acceptance tooling. It talks to the PC gateway, not directly to Android. Official loop: `tools/codex-phone-mcp/SKILL.md`.

### Lane F — Desktop/Core/Host ecosystem (legacy control plane)

Primary paths:

- `apps/cyclone-core/**`
- `apps/desktop/**`
- `apps/host-bridge/**`
- `services/**`, `packages/**`, `docker/**`

### Lane G — Integration/release

Primary paths:

- `.github/workflows/**`
- `scripts/release/**`
- `MOBILE_DOWNLOADS.md`
- `release/version.toml`
- release metadata/docs

Shared files such as `AndroidManifest.xml`, `build.gradle.kts`, `MainActivity.kt`, cross-layer protocol schemas and release workflows should normally be changed by the integration owner or with explicit coordination.

## Multi-agent rules

Before starting parallel work, the coordinator must publish:

- exact base SHA (from the 3.6 working line);
- branch name per agent;
- owned paths;
- forbidden paths;
- input/output contract;
- acceptance tests;
- integration order.

Each agent must hand back:

- exact base and head SHA;
- files changed;
- contract/API changes;
- tests run and results;
- physical-device status (if relevant);
- known limitations;
- migration/release notes.

Do not give two agents ownership of the same large file. If two tracks need the same contract, freeze the contract first or let the integration agent make the shared edit.

## Fast-change workflow

For small changes:

1. Run `cyclone-context.py` and classify the diff using
   `docs/agent-system/FAST_WORK_AND_TOKEN_PLAYBOOK.md`.
2. Declare the owner lane, planned paths, focused first test and artifact impact.
3. Inspect only the owning module, nearest test and directly consumed contract.
4. Make the smallest focused change; do not reconstruct large legacy files.
5. Update/add a regression test or static guard for the bug.
6. Run the focused gate until green, then run the full relevant gate once on the final candidate.
7. Do not rerun an unchanged green lane or rebuild the same source SHA.
8. Run release CI only when the change can affect a distributable artifact.
9. Let a successful release workflow publish its own artifacts; avoid workstation
   download/checksum/re-upload loops.
10. Never claim an APK or installer is updated until release evidence points to the exact source
    SHA.

For large changes, use a feature branch off `release/beta/cyclone-3.6.0` and the multi-agent task contract.

Default to one agent for one ownership lane. Parallel agents require two or more independent lanes,
frozen contracts and non-overlapping paths; otherwise their setup and handoffs usually add time and
tokens instead of saving them.

## Version/release rules

- `versionName` is the human-facing release identity. Current: `3.6.0`.
- `versionCode` is monotonic for installable Android builds and should increase for every distributable APK, even when the marketing version stays the same. Current: `43`.
- UI reads `CycloneRelease`, not literal release strings.
- Python gateway/MCP package versions must match the intended product release; the context script reports mismatches.
- Large APKs belong in GitHub Actions/Release assets, not Git blobs.
- A release is real only when tests pass and the exact artifact/hash is recorded.
- 3.6.0 ships a pinned Cyclone release keystore (`PINNED_RELEASE_KEYSTORE` in `release/version.toml`), arm64-only, not `testOnly`.
- Do not assemble debug APKs for user install. PackageInstaller rejects `testOnly`.
- Quit PC Companion before running the NSIS installer; `CycloneAgentMCP.exe` is locked while Companion runs.
- Existing version-named Android workflows are manual compatibility entry points, not templates.

## Coding rules that save future agent time

- Prefer small modules with explicit interfaces over “god files.”
- Put product constants/version logic in one place.
- Keep runtime state stores separate from human-readable mirrors.
- Name protocols/schemas independently from marketing versions when backwards compatibility matters.
- Tests should assert contracts and outcomes, not incidental implementation formatting.
- Preserve stable internal V292/V293 identifiers if changing them would break stored data; remove stale **visible** labels instead.
- Add an ADR/decision note when changing an invariant or cross-layer contract.
- Never silently introduce a second source of truth.
- Do not restore archived V2 plans as if they were the current spec.

## Definition of done

A task is not done because code compiles. It is done when:

- intended behavior is implemented;
- ownership boundaries remain intact;
- regression coverage exists;
- security/privacy invariants still hold;
- version/release identity is consistent;
- relevant CI is green;
- physical-device behavior is clearly marked verified or unverified;
- handoff notes are enough for another agent to continue without chat history.
