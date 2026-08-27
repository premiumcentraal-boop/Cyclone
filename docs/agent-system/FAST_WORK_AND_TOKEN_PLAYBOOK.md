# Fast Work and Token Playbook

This is the default operating path for new Cyclone coding agents. Its goal is to shorten routine
update rounds without weakening artifact truth, security boundaries, or physical-device claims.

Use this document before the longer architecture set. Expand context only when the changed paths or
a failing contract require it.

## Measured baseline

The August 27, 2026 Cyclone 3.3 releases provide a useful baseline:

| Evidence | Windows critical path | Android lane | Manual post-CI publication | Result |
|---|---:|---:|---:|---|
| [3.3.0 Beta 1 run 33080472172](https://github.com/premiumcentraal-boop/Cyclone/actions/runs/33080472172) | 8m 53s | 3m 43s | about 9m | released |
| [3.3.0 Beta 2 run 33089372883](https://github.com/premiumcentraal-boop/Cyclone/actions/runs/33089372883) | 9m 33s | 4m 25s | 9m 48s | released |

Beta 2's Windows installer compile took 5m 52s, or about 61% of the Windows job. Frozen Python
sidecars took 1m 22s and Python setup took 55s. Android tests plus assembly took 2m 55s. The
combined GitHub build was therefore under ten minutes; a 30–50 minute agent round is not explained
by compilation alone.

The repeated extra time came from:

- broad repository/context reading before the change was classified;
- running full local suites and then running the same coverage again in CI;
- rebuilding an unchanged source SHA instead of reusing its artifact;
- manual workflow dispatch skipping the release job;
- downloading roughly 170 MB of artifacts to the agent PC, hashing them, then uploading them back
  to GitHub;
- serial polling and progress narration while CI or uploads were already running;
- multi-agent setup/handoff overhead for changes with only one real ownership lane;
- stale release/current-state text that made new agents rediscover what was authoritative.

## Target service levels

These are engineering targets, not reasons to skip a required gate:

| Work | Target |
|---|---:|
|---|---:|
| New-agent orientation before first useful inspection | 2 minutes |
| Focused diagnosis before first edit | 10 minutes |
| Focused local verification for a routine patch | 5 minutes |
| Warm-cache combined GitHub build | 6–7 minutes |
| Automatic verified publication after green jobs | under 2 minutes |
| Routine code-to-beta after push | under 10 minutes |
| Routine hotfix from report to published beta | under 20 minutes |

Record actual numbers after workflow changes. Never advertise a target as achieved without measured
GitHub run evidence.

## Ninety-second start for every agent

1. Read root `AGENTS.md` and this file.
2. Run `python scripts/agent/cyclone-context.py --markdown`.
3. Run `git status --short` and preserve unrelated work.
4. Classify the task using the matrix below.
5. Name the owner lane, changed paths, focused first test, and distributable impact before editing.
6. Read only the owner-lane guide and directly referenced contract. Do not preload the whole
   knowledge package.

Read `ARCHITECTURE_AND_CONTRACTS.md`, `MULTI_AGENT_PROTOCOL.md`, and broad historical handoffs only
for cross-layer architecture, contract changes, or genuinely parallel implementation. Search with
`rg` before opening large files.

## Change classifier and minimum gates

| Change class | Typical paths | First local gate | GitHub/release action |
|---|---|---|---|
| Docs/agent guidance | `docs/**`, `AGENTS.md`, templates | links, YAML/Markdown sanity, `git diff --check` | no APK/installer build |
| PC UI only | `apps/pc-companion/src/**` | focused UI test, then UI build | PC verification; package only if distributed |
| Gateway logic | `apps/device-gateway/**` | nearest regression test | gateway CI; package only if distributed |
| Gateway packaging/dependency | PyInstaller specs, sidecar locks/scripts | focused packaging test plus exact packaged-runtime smoke | Windows installer required |
| MCP only | `tools/*mcp/**` | focused MCP tests | MCP verification; no APK |
| Android UI/runtime | `apps/mobile/**` | nearest JVM test; assemble only after focus passes | one Android CI artifact per SHA |
| Shared Android/PC contract | gateway schema/trust/media contract | producer and consumer contract fixtures | Android and PC lanes in parallel |
| Release metadata only | canonical version files | version-coherence guard | reuse already-built artifact when source bytes are unchanged; otherwise package once |

If a changed path is not in the planned class, stop and reclassify before adding more tests or
agents.

## Token discipline

### Progressive context

- Default reading set: root `AGENTS.md`, this playbook, generated context, one owner-lane document.
- Do not read the long-form knowledge package, every historical release file, or every agent prompt
  before a focused patch.
- Prefer exact searches and small line windows over dumping whole large files.
- Treat previous handoff claims as an index. Inspect the referenced commit/diff instead of replaying
  the entire prior chat.

### One evidence pass

- Capture the failing symptom once with the smallest probe that distinguishes the failing plane.
- Write the regression test before or with the fix.
- Run the focused gate until it passes.
- Run the full relevant gate once on the final candidate SHA. Do not rerun an unchanged green lane.
- CI evidence for the exact SHA supersedes repeating the same full suite locally, unless the task is
  specifically diagnosing CI/packaging differences.

### Compact communication

- Send progress at milestones: cause found, fix verified, CI started, release published.
- Do not stream unchanged polling output or repeat long test logs in chat.
- Handoffs contain facts and pointers: base/head SHA, changed paths, contract delta, tests, CI,
  physical status, limitations. Do not retell the whole investigation.

### Agent count

- Default to one implementation agent for one ownership lane.
- Add agents only for two or more independent lanes with frozen inputs/outputs.
- Do not create a review agent until there is a stable diff to review.
- The coordinator alone owns shared version files, release workflows, merge order, and publication.

Parallelism saves time only when work can proceed without overlapping files or waiting on an
unfrozen contract.

## Fast diagnostic pattern

For installed PC/mobile failures:

1. Reproduce against the exact installed artifact/version, not only source mode.
2. Probe discovery, transport, trust, media, render, and action planes independently.
3. Identify the first failed stage and last successful stage.
4. Compare the installed package contents/dependencies with the development environment.
5. Make one bounded correction and add a packaged-path regression guard.

The Beta 2 startup hotfix is the model: HTTP readiness passed, the WebSocket route returned 404,
and the installed frozen sidecar lacked `websockets`. That plane-specific probe was more useful than
another broad reconnect rewrite.

## Fast release path

1. Finish the candidate and focused tests locally.
2. Update canonical versions only if a new artifact will be distributed.
3. Commit once and record the exact SHA.
4. Push one candidate branch and start one authoritative run.
5. Let Android and Windows lanes run in parallel.
6. Publish directly inside the successful GitHub run from its own artifacts.
7. Verify the release tag, target SHA, asset names, checksums, and prerelease state through the
   GitHub API.
8. Download a large artifact back to the agent machine only when local installation/physical
   acceptance is actually required.

Never rebuild the same SHA to obtain another copy of the same artifact. Never manually download and
re-upload artifacts when the running workflow can publish them directly.

## Workflow optimization implementation plan

### Phase 0 — agent operating path (implemented by this documentation update)

Owner: repository guidance / integration.

- Make this playbook the second document new agents read.
- Change task and PR templates to require change class, minimal gate, distributable impact, and a
  reason for every additional agent/full-suite run.
- Keep docs-only changes outside build path filters.

Acceptance:

- a fresh agent can identify the owner lane and first gate without reading all canonical docs;
- docs-only changes do not start Android or Windows packaging;
- handoffs explicitly list skipped gates and why.

### Phase 1 — remove the manual release round-trip (highest impact)

Owner paths: `.github/workflows/pc-companion-ci.yml`, release metadata/tests.

- Add an explicit boolean `publish_beta` input to `workflow_dispatch`.
- Permit publication only when the input is true, the ref is an approved `release/beta/**` branch,
  both build jobs succeeded, and release versions are coherent.
- Reuse artifacts from that same run; do not download them to a workstation.
- Keep pull requests and ordinary manual verification non-publishing by default.
- Replace an existing tag only under an explicit, separately named republish input; normal runs
  must fail closed if the tag already exists.

Measured target: reduce successful-run-to-release latency from about nine minutes to under two.

### Phase 2 — cache the critical Windows toolchains

Owner paths: `.github/workflows/pc-companion-ci.yml`, packaging tests.

- Enable `setup-node` npm caching using `apps/pc-companion/package-lock.json`.
- Enable `setup-python` pip download caching keyed by gateway/MCP project files and the sidecar lock.
- Add official `actions/cache` coverage for Cargo registry/git and
  `apps/pc-companion/src-tauri/target`, keyed by runner, Rust/toolchain inputs, and `Cargo.lock`.
- Keep PyInstaller `--clean`; cache downloads/build inputs, never a stale Analysis graph.
- Record cache hit/miss in the job summary.

GitHub documents native setup-action caching for npm/pip and Cargo cache paths. Cache entries must
not contain secrets, signing material, or mutable release outputs.

Measured target: reduce the 5m 52s installer build and repeated setup work enough for a warm Windows
job of 6–7 minutes or less.

### Phase 3 — parallelize verification and packaging

Owner paths: combined workflow plus reusable verification workflow(s).

- Split fast Python/UI/contract verification from Windows packaging.
- Start verification, Android build, and Windows packaging concurrently after cheap metadata guards.
- Publish only when all required jobs pass.
- Run the complete gateway/MCP contract suite once per SHA; remove overlap between the reusable
  Android build and Windows verification without reducing coverage.
- Add workflow-level concurrency keyed by workflow and ref. Cancel superseded feature/PR runs;
  never cancel an in-progress publishing release.

Measured target: keep test work off the installer critical path and avoid stale runs consuming
Actions minutes.

### Phase 4 — changed-path gate selection and one-command release

Owner paths: `scripts/agent/**`, `scripts/ci/**`, `scripts/release/**`, tests.

- Extend `cyclone-context.py` with a compact `--scope <lane>`/changed-path classification output.
- Add a dependency-free gate selector that prints and optionally runs the minimal commands for the
  current diff.
- Add one release command that dispatches the approved workflow, waits by run ID, and returns the
  release/asset URLs; it must not rebuild or transfer assets locally.
- Emit machine-readable timing/provenance summaries for later audits.

Acceptance: a new chat needs one bootstrap command and one release command, with no manual artifact
plumbing.

### Phase 5 — measure before considering dedicated runners

Collect at least ten warm/cold runs after Phases 1–4. Consider a secured dedicated Windows runner
only if hosted-runner variance or Rust/NSIS setup still dominates. Do not use a self-hosted release
runner until isolation, patching, secret scope, cleanup, and reproducibility are documented.

## Required metrics after each CI optimization

Record in the PR or plan update:

```text
Baseline run ID:
Candidate run ID:
Windows total:
Android total:
Installer step:
Sidecar step:
Android test/assemble step:
Cache state:
Publish latency:
Behavior/artifact equivalence:
```

An optimization is accepted only if required tests/artifacts remain equivalent and the measured
result improves or simplifies the critical path. Revert complexity that does not produce a real
gain.

## External implementation references

- [GitHub dependency caching](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)
- [GitHub Rust build and cache guidance](https://docs.github.com/en/actions/tutorials/build-and-test-code/rust)
- [GitHub workflow concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
