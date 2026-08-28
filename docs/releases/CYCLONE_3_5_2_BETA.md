# Cyclone 3.5.2-beta release candidate

This is the durable Agent 3 integration record for the Cyclone 3.5.2-beta + Teamwork Sniper sprint.

## Identity

- Preserved baseline SHA: `9957eea21016476e8b004121d553e80ad0f7c136`
- Agent 3 branch: `agent/352-release-integration`
- Cyclone package: `com.cyclone.mobile`
- Cyclone version: `3.5.2-beta`
- Cyclone versionCode: `38`
- Teamwork Sniper package: `com.cyclone.teamworksniper`
- Teamwork Sniper expected version: `3.5.2-beta`
- Teamwork Sniper expected versionCode: `1`
- Target Teamwork package: `tech.picnic.workapp`
- Pixel 8 target: `3B171FDJH0061G`

Cyclone 3.5.2-beta is a mobile beta layered on the exact preserved 3.5.1 source. PC Companion, Device Gateway and MCP retain their preserved 3.5.1 component identities unless separately released.

## Product shape

Cyclone remains the full Cyclone product: Home, Teach, AI, Routines / Automations, Brain, Settings, PC Gateway and the existing runtime/services remain present. Settings adds one lightweight companion card only. It opens Teamwork Sniper when installed and otherwise explains that the sniper is a separate APK; no sniper rules or duplicate dashboard are added to Cyclone.

Teamwork Sniper is a separate minimal APK. Its runtime must use Teamwork notifications plus Android Accessibility semantic state, require an explicit armed state before a claim, and never claim a shift outside configured rules. Production shift reading/claiming must not depend on screenshots, OCR/image analysis, MediaProjection, screencap or hardcoded claim coordinates.

## Release output contract

A combined successful CI artifact must contain:

- `Cyclone-3.5.2-beta.apk`
- `Cyclone-3.5.2-beta.apk.sha256`
- `Teamwork-Sniper-3.5.2-beta.apk`
- `Teamwork-Sniper-3.5.2-beta.apk.sha256`
- `mobile-metadata.json`
- `teamwork-sniper-metadata.json`
- `source-sha.txt`
- `run-id.txt`

APK binaries stay in Actions / Release assets, not Git history. Publication remains disabled until protected/manual release verification is deliberately completed.

## Static release gates

- `scripts/ci/mobile_product_guard.py` locks the active Cyclone shell to Home, Teach, AI, Routines, Brain, Settings, PC Gateway, the canonical launcher, accessibility service and notification listener.
- `scripts/ci/teamwork_sniper_guard.py` scans `apps/teamwork-sniper/app/src/main/**` and rejects screenshot/OCR/image-analysis references plus obvious hardcoded tap/swipe/path coordinate execution. Diagnostic Accessibility/XML bounds are not prohibited.
- `scripts/ci/teamwork_sniper_metadata.py` requires the standalone package identity and release version once the app is integrated.

## Requirement matrix

| Requirement | Status | Evidence |
| --- | --- | --- |
| Exact 3.5.1 baseline preserved | PASS | Agent 3 branch created directly from `9957eea21016476e8b004121d553e80ad0f7c136` |
| Cyclone version 3.5.2-beta / 38 | PASS (source) | Gradle + `release/version.toml` |
| Home / Teach / AI / Routines / Brain / Settings retained | PASS (static) | Existing V32 shell + product guard |
| PC Gateway retained | PASS (static) | Existing Gateway Settings + AI gateway card guarded |
| Cyclone accessibility + notification services retained | PASS (static) | Manifest invariants guarded |
| Cyclone companion entry | PASS (source) | Settings-only Teamwork Sniper card |
| Standalone sniper APK | NOT VERIFIED | Agent 1 exact head SHA not supplied/integrated |
| Notification trigger | NOT RUN | Requires integrated sniper + live Teamwork |
| Accessibility reading | NOT RUN | Requires integrated sniper + physical device |
| Screenshot-free sniper | NOT RUN | Guard prepared; Agent 1 source not integrated |
| Rule engine | NOT RUN | Agent 1-owned runtime not integrated |
| Armed safeguard | NOT RUN | Agent 1-owned runtime not integrated |
| Real claim | NOT EXECUTED | No integrated sniper/device/live safe-match evidence |
| Pixel 8 install | NOT RUN | Requires built APKs and adb/device access |
| APK SHA-256 | NOT AVAILABLE | No authoritative build artifact for this Agent 3 lane |

## Exact final-integration sequence

Do not merge by branch name. Substitute only exact handoff SHAs supplied by Agent 2 and Agent 1.

```bash
git switch agent/352-release-integration
git status --short
git merge --no-ff <AGENT_2_EXACT_HEAD_SHA>
git merge --no-ff <AGENT_1_EXACT_HEAD_SHA>

python scripts/ci/release_versions.py --check
python scripts/ci/mobile_metadata.py
python scripts/ci/mobile_product_guard.py
python scripts/ci/teamwork_sniper_metadata.py --require-app
python scripts/ci/teamwork_sniper_guard.py --require-app
python -m unittest discover -s scripts/ci/tests -v

./apps/mobile/gradlew -p apps/mobile :app:testDebugUnitTest :app:assembleDebug --stacktrace
./apps/teamwork-sniper/gradlew -p apps/teamwork-sniper :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

Then run the existing `Cyclone Mobile CI` once for the exact final source SHA. Reuse that artifact in `mobile-release.yml`; do not rebuild it in release verification.

## Physical-device acceptance

On Pixel 8 `3B171FDJH0061G`, install both final CI APKs and confirm all three packages:

```bash
adb -s 3B171FDJH0061G install -r Cyclone-3.5.2-beta.apk
adb -s 3B171FDJH0061G install -r Teamwork-Sniper-3.5.2-beta.apk
adb -s 3B171FDJH0061G shell pm list packages | grep -E 'com.cyclone.mobile|com.cyclone.teamworksniper|tech.picnic.workapp'
adb -s 3B171FDJH0061G shell dumpsys accessibility
```

Also verify Notification Access using Android Settings/service diagnostics appropriate to the device build. Do not infer permission state from unit tests.

Cyclone acceptance must cover launch, Home, Teach, AI, Routines, Brain, Settings, PC Gateway, existing Accessibility connection and existing Notification Listener connection.

## End-to-end Teamwork gate

After exact Agent 1 + Agent 2 integration:

1. Open Teamwork Sniper and configure at least one shift rule.
2. Confirm enabled state and explicit armed state.
3. Confirm Notification Access and Accessibility.
4. Observe a genuine `tech.picnic.workapp` notification when possible.
5. Record notification → Teamwork open → semantic Accessibility tree read → normalized open shifts → rule comparison → match/miss → claim only if safe.
6. If a desired shift is genuinely open, perform and verify one real claim.
7. If no desired safe target exists, record exactly: **END-TO-END READ + COMPARE VERIFIED — CLAIM NOT EXECUTED — NO SAFE MATCH AVAILABLE**.

Never fabricate a match or call a dry run a claim.

## Known blockers

- Agent 1 exact head SHA has not been supplied, so the standalone app/runtime is not integrated.
- Agent 2 exact head SHA has not been supplied, so live probe fixtures/contracts are not integrated.
- This connector-only Agent 3 lane has no Gradle/Android/adb execution or physical Pixel 8 evidence.
- No authoritative CI APKs or SHA-256 values exist for the combined release yet.

**Release readiness: NOT READY.** The exact Agent 1/2 heads, final CI artifact, Pixel 8 acceptance and live Teamwork evidence remain required.
