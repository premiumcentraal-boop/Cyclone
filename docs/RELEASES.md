# Releases

Cyclone release artifacts are produced by GitHub Actions and tied to an exact source SHA.

## Next mobile cut: Cyclone Mobile 4.1.0 (operator-after-CI)

Tag `v4.1.0` is the **next** mobile **4.1.0** / `versionCode` **80** cut. Do not claim the GitHub tag already exists until Full Release creates it. `pc_companion` stays **1.0.0** on this tree (One/PC 1.1.0 is A5, out of scope). Physical Pixel 8 remains **UNVERIFIED**. Protocol-fix tip `d249f2a` Mobile CI **SUCCESS**: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34172841433.

Do not add a one-off `mobile-publish-v410.yml`. Match the successful **v4.0.4** path (`mobile-publish-v3910.yml` on `release/cyclone-mobile-v*`):

1. Merge PRs **#65 → #67 → #69 → #71**, then this B5 PR (`grok/mobile-4.1-s5-release`). Do not rewrite stack history.
2. After the stack lands, push `release/cyclone-mobile-v4.1.0` from the merged SHA (`publication_authorized=true`) so **Cyclone Mobile CI** and **Cyclone Mobile Full Release** (`mobile-publish-v3910.yml`) both fire.
3. Full Release waits for Mobile CI **push** success on that SHA, recovers the historical 3.9.0/4.0.4-compatible keystore, signs `Cyclone-4.1.0.apk`, verifies cert equality vs `v4.0.4`, and `gh release create v4.1.0`. Do **not** also create the tag locally. Do **not** run `cut_v41_release.py --execute`.
4. `android_signing` = `LEGACY_UPDATE_COMPATIBLE_DEV_KEY`. If the signer does not match a device's 4.0.4 install, documented wipe; do not claim update succeeded.
5. Do **not** dispatch `pc-companion-release.yml` as part of this mobile 4.1.0 cut. Pairing: full Layer 2 MCP needs One ≥ 1.1.0; 4.0.4/4.1.0 phone + One 1.0.0 remains foreground-capable.
6. `mobile-release.yml` (rotated-key secrets) is the blocked fallback unless `mobile-release-approval` has the five `CYCLONE_ANDROID_*` secrets. Do not invent a keystore.

Never force-push, delete tags, or overwrite a GitHub Release. Never replace an existing `v4.1.0`.

Operator cut path detail: [`docs/MOBILE_4.1_STAGE5_RELEASE.md`](MOBILE_4.1_STAGE5_RELEASE.md). Product notes: [`docs/RELEASE_4.1.md`](RELEASE_4.1.md).

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
