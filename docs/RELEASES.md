# Releases

Cyclone release artifacts are produced by GitHub Actions and tied to an exact source SHA.

## Current product: Cyclone 4.0.0 + One 1.0.0

Tag `v4.0.0` is the mobile **4.0.0** / `versionCode` **71** cut, paired with Cyclone One **1.0.0** (`Cyclone-PC-Companion-1.0.0-Setup.exe`).

Do not add a one-off `mobile-publish-v400.yml`. Cut the pair through the existing lanes plus `scripts/ci/cut_v4_release.py`:

1. Merge PRs **#59 → #60 → #61 → #62**, then Stage 5 (`grok/cyclone-v4-s5-release` into `grok/cyclone-v4-s4-one` / then `main` or `release/cyclone-mobile-v4.0.0`).
2. Push `release/cyclone-mobile-v4.0.0` from that SHA (or merge to `main`) so **Cyclone Mobile CI** (`mobile-ci.yml`) records an unsigned candidate on a **push** run.
3. Wait for Mobile CI success on that SHA. Copy `build_run_id` and the exact `artifact_name` from that run (typically `Cyclone-Android-4.0.0` if `versionName` is `4.0.0` — do not invent the name).
4. Dispatch **Cyclone Mobile Release Signing** (`mobile-release.yml`) with those inputs. Signing is `release/cyclone-mobile-v*` push-run only; that workflow must not run `gh release`.
5. Dispatch **Cyclone PC Companion Release Candidate** (`pc-companion-release.yml`) for the One 1.0.0 NSIS installer.
6. `python scripts/ci/cut_v4_release.py` prints the exact commands (dry-run, no network). `--execute` runs `gh release create v4.0.0 --title "Cyclone 4.0.0 + One 1.0.0" --notes-file docs/RELEASE_V4.md` and attaches the signed APK and One installer if present. It refuses a dirty tree, SHA mismatch, an existing tag/release, missing notes, and UNVERIFIED physical unless `--allow-unverified-physical` is passed honestly.

Never force-push, delete tags, or overwrite a GitHub Release.

## Version sources

- Android identity: `apps/mobile/app/build.gradle.kts`
- Product/component metadata: `release/version.toml`

`versionName` is the human-facing mobile release. `versionCode` must monotonically increase for distributable Android builds.

## Release expectations

A release candidate should have:

- relevant unit/integration tests passing;
- product/version guards passing;
- an APK produced from the exact candidate SHA;
- checksum and source-SHA provenance;
- physical-device status stated honestly.

Old release manifests and one-off version workflows are intentionally not kept in the active tree. GitHub Releases and Git history are the historical archive.
