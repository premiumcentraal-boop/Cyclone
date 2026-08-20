# Fast Release Playbook

The release system should optimize for two things at the same time: **fast feedback** and **artifact truth**.

## 1. Classify the change first

### Docs/knowledge only

No Android/PC build should be required once CI path filters are organized appropriately.

### UI-only

Run static product invariants + Android unit/compile gate. Do not rerun unrelated PC gateway tests unless shared contracts changed.

### Android runtime/control/learning

Run Android unit tests + assemble. Add gateway tests if Android gateway contracts changed.

### PC gateway only

Run gateway tests/build. Android APK build is unnecessary unless the Android contract changed.

### MCP only

Run MCP unit/protocol/mock acceptance. No APK build unless a contract dependency changed.

### Cross-layer/release candidate

Run Android and PC/MCP lanes in parallel, then package once both are green.

## 2. Versioning

### Current rule

Android user-visible release identity comes from `BuildConfig.VERSION_NAME` via `CycloneRelease`.

### Best-practice target

Add one repo release metadata source (for example `release/version.toml`) containing:

- product version;
- Android versionCode/build revision;
- release channel.

Generate/synchronize Gradle + Python package versions and artifact names from it.

Until that migration is complete, `scripts/agent/cyclone-context.py` should be used to expose mismatches.

### Same marketing version rebuild

If a polished rebuild is still called `2.9.5`, keep the marketing `versionName` if desired but increment Android `versionCode` so the new APK has a clear install ordering. Record a unique source SHA/run ID/hash in release metadata.

## 3. Do not duplicate release workflows per version forever

The desired end state is a generic workflow such as:

```text
mobile-check.yml
mobile-release.yml
physical-acceptance.md / manual gate
```

The workflow should derive release names from canonical version metadata rather than copying `cyclone-v2.9.5-...yml` into `v2.9.6`, `v2.9.7`, etc.

Version-specific workflows can remain as history until the generic workflow is proven.

## 4. CI structure

Recommended release graph:

```text
                  ┌─ Android tests + assemble ─┐
source SHA ─ guards                            ├─ package/release ─ verified marker
                  └─ PC gateway + MCP tests ──┘
```

Use caching and concurrency cancellation so obsolete branch pushes do not continue consuming build resources.

## 5. Artifact truth

Never say “the APK is ready” based only on source code or a tag.

A release is verified only when the evidence identifies:

- exact source SHA;
- successful CI run;
- artifact filename;
- byte size;
- SHA-256;
- release/download location;
- signing mode;
- physical-device status if relevant.

`BUILD_VERIFIED.json` is the preferred small Git-tracked proof. The APK itself belongs in Actions/GitHub Releases because it is too large for normal Git history.

## 6. Avoid bot-trigger loops

A workflow that commits a source change with `GITHUB_TOKEN` and expects that push to recursively trigger another workflow is fragile. Apply source changes from a normal authorized commit before the release run, or keep generated changes within one workflow/run.

## 7. Fast static guards

Before expensive compilation, fail quickly on invariants such as:

- one Android launcher;
- package identity;
- version source present;
- stale visible release strings absent;
- required Home/Teach/AI/Automations/Brain surfaces present;
- Gateway present inside AI;
- forbidden shell/root MCP tools absent;
- allowed action schemas unchanged unexpectedly.

## 8. Reproducible artifacts

Prefer pinned major toolchain versions, dependency lockfiles where supported, and explicit artifact naming.

Long term, use protected release signing. Debug keys on ephemeral CI runners can prevent smooth upgrades even when package/version are correct.

## 9. Mobile download shelf

`MOBILE_DOWNLOADS.md` should point to the latest verified release and retain older known-good builds. Agents should update it only after artifact verification.

## 10. Release handoff

A release agent should report:

```text
VersionName:
VersionCode:
Source SHA:
CI run:
APK name:
APK SHA-256:
Bundle SHA-256:
Signing:
One-launcher check:
UI invariant check:
Gateway/MCP tests:
Physical-device acceptance:
Known limitations:
```
