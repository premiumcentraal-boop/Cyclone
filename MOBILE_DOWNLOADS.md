# Cyclone Mobile APK Downloads

Use **GitHub Releases** as the permanent download shelf for Cyclone Mobile builds. You no longer need to open an Actions run, download a ZIP, extract `app-debug.apk`, and guess which build is newest.

## Current beta

### Cyclone Mobile 2.9.5 Beta — repository-verified shelf record

- Release page: https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v2.9.5
- Direct APK: https://github.com/premiumcentraal-boop/Cyclone/releases/download/v2.9.5/Cyclone-Mobile-2.9.5-Original-UI-Full-Gateway.apk
- Recorded SHA-256: `b6ddfe9b67d16c322536d92ce8468a35cf3f311975b97948d5b9fa815d73300c`
- Repository proof: `releases/2.9.5/BUILD_VERIFIED.json`

This shelf entry describes the previously verified 2.9.5 beta. It does **not** claim that a local
Infrastructure V3 build has been uploaded, published or physically tested.

## All mobile versions

Open the repository Releases page and choose the Cyclone Mobile version you want:

https://github.com/premiumcentraal-boop/Cyclone/releases

Mobile release tags should use one documented format per channel, for example:

```text
v2.9.5
mobile-v3.0.0-beta
```

APK filenames use this format:

```text
Cyclone-Mobile-2.9.5-Original-UI-Full-Gateway.apk
Cyclone-Mobile-3.0.0-beta.1.apk
```

## Publishing future updates

`.github/workflows/mobile-ci.yml` is the only automatic Android lane. It calls the reusable
`_mobile-build.yml`, which validates metadata, tests and assembles once, and stores one APK with its
checksum, exact source SHA, run ID and metadata.

For a new mobile release:

1. Bump `versionCode` and `versionName` in `apps/mobile/app/build.gradle.kts`.
2. Push the mobile change or open a pull request and require a successful `Cyclone Mobile CI` run.
3. Review the run's APK, source SHA, run ID, metadata and checksum.
4. Run `mobile-release.yml` manually with that run ID and artifact name. It verifies and reuses the
   authoritative artifact; it never recompiles it.
5. Follow `docs/agent-system/FAST_RELEASE_PLAYBOOK.md` for the release evidence and approval gates.

Publication is intentionally disabled. Version code `17`, debug-signed beta output, protected release
signing and physical-device acceptance are explicit blockers before a new downloadable release can be
claimed. Do not update this shelf from a local build alone.

## Build verification

Each release includes a `.sha256` file. To verify a downloaded APK on Windows PowerShell:

```powershell
Get-FileHash .\Cyclone-Mobile-2.9.5-Original-UI-Full-Gateway.apk -Algorithm SHA256
```

Compare that value with the release checksum file.
