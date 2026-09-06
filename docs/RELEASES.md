# Releases

Cyclone release artifacts are produced by GitHub Actions and tied to an exact source SHA.

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

Cyclone One Windows betas are documented in [`RELEASE_CYCLONE_ONE_0.2.md`](RELEASE_CYCLONE_ONE_0.2.md). The Windows installer artifact is `Cyclone-One-0.2.0-Beta-1-Setup.exe`.

Old release manifests and one-off version workflows are intentionally not kept in the active tree. GitHub Releases and Git history are the historical archive.
