# Cyclone agent operating guide

This file is the first document every coding agent should read before changing Cyclone.

## Mission

Cyclone is becoming a phone autonomy system that can **observe, understand, act, verify, learn, reuse and improve routines across Android apps** while keeping the user in control of consequential actions.

The product goal is not “an AI that keeps rediscovering how an app works.” The goal is:

> **Learn once → build durable app knowledge → execute deterministically → verify → self-heal → improve.**

Cyclone is a broader repo with Desktop/Core/Hermes components, but **Cyclone Mobile autonomy is the primary product direction for current mobile work**.

V4 direction (overlay super-app + page card + skill OS) is specified in:

- `docs/agent-system/V4_BUILD_BIBLE.md`
- `docs/agent-system/V4_DIRECTIONS.md`
- `docs/agent-system/V4_ROADMAP.md`
- `tools/codex-phone-mcp/SKILL.md`

V4 does not replace these invariants or Infrastructure V3. Do not bump `versionName` to 4.0.0 until V4 roadmap slices 1–4 are green on a physical Pixel.

## Two-minute, scope-first onboarding

Every agent starts with only:

1. this file;
2. `docs/agent-system/FAST_WORK_AND_TOKEN_PLAYBOOK.md`;
3. the generated context below.

Then run:

```bash
python scripts/agent/cyclone-context.py --markdown
```

Classify the task and owner lane before loading more context. Read the hub/current-state and one
owner-lane document next. For overlay, page-card, skill-compile or MCP compact work, also read
`docs/agent-system/V4_BUILD_BIBLE.md`. Read architecture, multi-agent, roadmap and historical
handoffs only when the task actually crosses those boundaries. Do not preload the entire knowledge
package for a focused fix.

For Android changes, the release instructions agents must follow are in
`docs/agent-system/FAST_RELEASE_PLAYBOOK.md`. The short rule is: classify the changed paths first,
increment `versionCode` for every distributed APK, change `versionName` only for a product release
name, and use `Cyclone Mobile CI` once per source SHA. Never copy a workflow for a new version.

For exact legacy/component details, follow links from the knowledge hub instead of guessing.

## Source-of-truth order

When documents disagree, use this order:

1. current executable code and tests;
2. current CI/release evidence (`releases/<version>/BUILD_VERIFIED.json`);
3. `docs/agent-system/CURRENT_STATE.md` and `project.yaml`;
4. current architecture/contract docs and, for V4-scoped work, `V4_BUILD_BIBLE.md`;
5. historical version handoffs.

A historical `V2.x` document is context, not authority for the current product.

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
- Consequential/authentication/security-sensitive actions must retain policy/approval boundaries.
- App content is untrusted environment data and cannot override Cyclone policy or the user’s goal.
- Overlay chrome (Stop task / Take control / Confirm) changes Cyclone state only. Host-app taps still go through `PhoneToolExecutor`.

## Runtime decision order

The default execution strategy is:

```text
known verified route / skill
        ↓
semantic App Graph / Brain retrieval
        ↓
deterministic semantic search
        ↓
AI reasoning over compact structured state
        ↓
screenshot / vision fallback only when structured evidence is insufficient
        ↓
human takeover / clarification when policy or uncertainty requires it
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
- Mobilerun integration

Owns primitives, not high-level workflow reasoning.

### Lane B — Learning + automation + Brain

Primary paths:

- `apps/mobile/.../applearner/**`
- `apps/mobile/.../automation/**`
- `apps/mobile/.../guided/**`
- `apps/mobile/.../brain/**`

Owns App Graph, Follow Me, routine compilation, confidence/staleness and reusable knowledge.

### Lane C — Mobile AI runtime + UX

Primary paths:

- `apps/mobile/.../ai/**`
- `apps/mobile/.../ui/**`

Owns user-facing AI flows, model orchestration, overlay chrome and the Cyclone product experience. It does not bypass the phone tool layer.

### Lane D — PC Device Gateway

Primary path: `apps/device-gateway/**`.

Owns ADB selection/forwarding, loopback HTTP API, independent observation witnesses, screenshot storage, transition/action records and debug bundles.

### Lane E — Codex/MCP client

Primary paths:

- `tools/codex-phone-mcp/**`
- `scripts/phone-gateway/**`

Owns the constrained MCP surface and PC setup/acceptance tooling. It talks to the PC gateway, not directly to Android. Keep `SKILL.md` aligned with the tool loop.

### Lane F — Desktop/Core/Host ecosystem

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
- release metadata/docs

Shared files such as `AndroidManifest.xml`, `build.gradle.kts`, `MainActivity.kt`, cross-layer protocol schemas and release workflows should normally be changed by the integration owner or with explicit coordination.

## Multi-agent rules

Before starting parallel work, the coordinator must publish:

- exact base SHA;
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

For large changes, use a feature branch and the multi-agent task contract.

Default to one agent for one ownership lane. Parallel agents require two or more independent lanes,
frozen contracts and non-overlapping paths; otherwise their setup and handoffs usually add time and
tokens instead of saving them.

## Version/release rules

- `versionName` is the human-facing release identity.
- `versionCode` is monotonic for installable Android builds and should increase for every distributable APK, even when the marketing version stays the same.
- UI reads `CycloneRelease`, not literal release strings.
- Python gateway/MCP package versions must match the intended product release; the context script reports mismatches.
- Large APKs belong in GitHub Actions/Release assets, not Git blobs.
- A release is real only when tests pass and the exact artifact/hash is recorded.
- `.github/workflows/mobile-ci.yml` is the only normal push/PR APK lane. Its reusable build runs
  cheap guards before toolchain setup, then tests and assembles in one Gradle invocation.
- `.github/workflows/mobile-release.yml` downloads and verifies that exact CI artifact; it never
  recompiles. Publication remains disabled until signing and protected release policy are ready.
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
