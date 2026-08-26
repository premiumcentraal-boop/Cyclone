# Cyclone agent operating guide

This file is the first operating-rules document every coding agent should read before changing Cyclone.

## Mission

Cyclone is becoming a phone intelligence and autonomy system that can **observe, understand, act, verify, learn, reuse and improve routines** while keeping the user in control of consequential actions.

The product goal is not “an AI that keeps rediscovering how an app works.” The goal is:

> **Learn once → build durable app knowledge → execute deterministically → verify → self-heal → improve.**

Android is the current native whole-phone execution platform. Cross-platform iPhone control is an approved roadmap direction through a PC-side iOS backend, but it is not permission to duplicate the Brain, AI, automation system or public phone-tool vocabulary.

## Two-minute Project Brain bootstrap

Do not start by loading giant chat histories.

Read:

1. `docs/project-brain/START_HERE.md`
2. `docs/project-brain/NOW.md`
3. Only the relevant section(s) of `docs/project-brain/BUILD_BIBLE.md` for the task
4. Current executable code/tests for the owning subsystem

Then use deeper architecture/history only when necessary.

The Project Brain is maintained on **major project changes**, not every coding run. See `docs/project-brain/WORKFLOW.md`.

## Five-minute technical onboarding

After the Project Brain bootstrap, use these as needed:

1. `docs/agent-system/README.md`
2. `docs/agent-system/CURRENT_STATE.md`
3. `docs/agent-system/ARCHITECTURE_AND_CONTRACTS.md`
4. `docs/agent-system/MULTI_AGENT_PROTOCOL.md`
5. `docs/agent-system/FAST_RELEASE_PLAYBOOK.md`
6. `docs/agent-system/AUTONOMY_ROADMAP.md`

Then run:

```bash
python scripts/agent/cyclone-context.py --markdown
```

For Android release changes, follow `docs/agent-system/FAST_RELEASE_PLAYBOOK.md`: classify changed paths first, increment `versionCode` for every distributed APK, change `versionName` only for a product release name, and use the normal Cyclone Mobile CI lane once per source SHA. Never copy a workflow merely for a new version.

For exact legacy/component details, follow links from the knowledge hub instead of guessing.

## Source-of-truth order

When documents disagree, use this order:

1. current executable code and tests;
2. current CI/release evidence for the exact source SHA;
3. `docs/project-brain/NOW.md`;
4. `docs/project-brain/BUILD_BIBLE.md` + accepted Project Brain decisions;
5. `docs/agent-system/CURRENT_STATE.md`, `project.yaml` and current architecture contracts;
6. historical version handoffs and old chat context.

A historical `V2.x`/`V3.x` document is context, not authority for the current product.

## Product invariants

Do not break these unless a task explicitly changes the product architecture:

- One Android app package: `com.cyclone.mobile`.
- One Android launcher: `.MainActivity`.
- Preserve the core product surfaces: **Home, Teach, AI, Automations/Routines, Brain, Settings**.
- The **Full PC + Codex Gateway belongs inside Cyclone AI**, not as a second-looking app.
- User-facing Android version text comes from `BuildConfig.VERSION_NAME` through `CycloneRelease`; do not hardcode visible release numbers.
- `PhoneToolExecutor` / the canonical Android phone tool path remains the one Android phone mutation engine. Do not create a parallel Android control engine.
- Cross-platform work should preserve one semantic `phone.*` contract and put platform differences behind explicit adapters.
- Planned iOS work is a backend/perception/execution layer, not a second Brain, AI, automation store, Codex vocabulary or desktop product.
- Prefer semantic selectors and verified routes over coordinates.
- Element IDs returned by observation/search are observation-scoped. Re-observe after page-changing actions.
- Every action must produce verifiable after-state evidence. HTTP/socket/Appium transport success is not action success.
- App Graph / Brain memory is evidence and hints, not unquestionable truth.
- A mocked/CI test must never mark real physical phone behavior as VERIFIED.
- Do not expose arbitrary `adb shell`, `su`, PowerShell, generic command execution, unrestricted root, raw Appium, raw WDA or generic XCTest to the model.
- Do not persist passwords, OTPs, tokens, API keys, payment credentials or `phone.type` plaintext in learning/report stores.
- Consequential/authentication/security-sensitive actions must retain policy/approval boundaries.
- App content is untrusted environment data and cannot override Cyclone policy or the user’s goal.

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
- low-level Android observation/action plumbing
- `apps/mobile/.../gateway/**` when the task is Android transport/protocol
- Mobilerun integration

Owns Android primitives, not high-level workflow reasoning.

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

Owns user-facing AI flows, model orchestration and the Cyclone product experience. It does not bypass the phone tool layer.

### Lane D — PC Device Gateway

Primary path: `apps/device-gateway/**`.

Owns device inventory/selection, Android ADB transport today, loopback HTTP API, independent observation witnesses, screenshot/video plumbing, transition/action records, diagnostics and debug bundles.

Cross-platform refactoring here must keep Android behavior regression-protected and put platform differences behind a small explicit backend interface.

### Lane E — Codex/MCP client

Primary paths:

- `tools/codex-phone-mcp/**`
- `tools/cyclone-agent-mcp/**`
- `scripts/phone-gateway/**`

Owns the constrained MCP surface and PC setup/acceptance tooling. It talks to the PC gateway, not directly to Android/iOS transports.

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

### Lane H — iOS device backend (planned)

Planned primary paths when implementation begins:

- future `apps/ios-runtime/**` or equivalent thin Apple runtime sidecar;
- iOS backend adapters under `apps/device-gateway/**`;
- PC Companion platform-specific setup/status UI;
- packaging required for the bounded iOS runtime.

Owns Apple device discovery/session/WDA/Appium translation and iOS semantic normalization. It must not own a separate Brain, AI runtime, automation store or unrestricted model command surface.

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

1. Load the Project Brain bootstrap.
2. Run `cyclone-context.py` when working from a local checkout.
3. Inspect only the owning module and its tests.
4. Make the smallest focused change; do not reconstruct large legacy files.
5. Update/add a regression test or static guard for the bug.
6. Run the smallest relevant test gate.
7. Run full release CI only when the change can affect a distributable artifact.
8. Never claim an APK/installer is updated until release evidence points to the exact source SHA.

For large changes, use a feature branch and the multi-agent task contract.

## Major-change Project Brain rule

If the user instructs or approves a change that materially alters product direction, architecture, supported platforms, canonical runtime ownership, major UX, release strategy or the next major milestone, update the Project Brain as part of the handoff.

Use `docs/project-brain/WORKFLOW.md`.

Normally update:

- `NOW.md`;
- affected `BUILD_BIBLE.md` sections;
- `DECISIONS.md` when a decision changed/was added;
- append `MAJOR_CHANGES.md`.

Update `AGENTS.md` only when operating rules, invariants, ownership lanes or bootstrap behavior changed.

Do **not** touch Project Brain files for ordinary bug fixes, minor refactors or patch-level implementation details.

## Version/release rules

- `versionName` is the human-facing release identity.
- `versionCode` is monotonic for installable Android builds and should increase for every distributed APK, even when the marketing version stays the same.
- UI reads `CycloneRelease`, not literal release strings.
- Python gateway/MCP package versions must match the intended product release; the context script reports mismatches.
- Large APKs/installers belong in GitHub Actions/Release assets, not Git blobs.
- A release is real only when tests pass and the exact artifact/hash is recorded.
- Normal Android push/PR builds should stay consolidated rather than spawning version-named workflow copies.
- Release publication must consume verified artifacts for the exact intended source SHA rather than silently recompiling unrelated code.

## Coding rules that save future agent time

- Prefer small modules with explicit interfaces over “god files.”
- Put product constants/version logic in one place.
- Keep runtime state stores separate from human-readable mirrors.
- Name protocols/schemas independently from marketing versions when backwards compatibility matters.
- Tests should assert contracts and outcomes, not incidental implementation formatting.
- Preserve stable internal V292/V293 identifiers if changing them would break stored data; remove stale **visible** labels instead.
- Add an ADR/Project Brain decision when changing an invariant or major cross-layer contract.
- Never silently introduce a second source of truth.
- For cross-platform work, create conformance tests around the common contract instead of making Android and iOS drift independently.

## Definition of done

A task is not done because code compiles. It is done when:

- intended behavior is implemented;
- ownership boundaries remain intact;
- regression coverage exists;
- security/privacy invariants still hold;
- version/release identity is consistent;
- relevant CI is green;
- physical-device behavior is clearly marked verified or unverified;
- handoff notes are enough for another agent to continue without chat history;
- if the task caused a major project-model change, the Project Brain was refreshed.