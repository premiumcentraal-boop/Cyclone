# Cyclone Mobile APK Downloads

Use **GitHub Releases** as the download shelf. Do not treat Actions ZIPs or `app-debug.apk` as the product.

## Current release — Cyclone 3.6.0

- Release page: https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v3.6.0
- Android APK: `Cyclone-3.6.0.apk` (`versionName` 3.6.0, `versionCode` 43, package `com.cyclone.mobile`)
- Windows: `Cyclone-PC-Companion-3.6.0-Setup.exe`
- Separate Picnic app: `Teamwork-Sniper-3.5.3.3.apk` (different package; not an upgrade of Cyclone Mobile)
- Notes: [`docs/release-notes/v3.6.0.md`](docs/release-notes/v3.6.0.md)
- Repo identity: [`release/version.toml`](release/version.toml)

Physical Pixel 8 UI slices are still **UNVERIFIED**. Do not mark phone behavior VERIFIED from CI alone.

### Pixel install

1. Settings → Apps → search **Cyclone**. If it exists (including Disabled / Private Space), uninstall it. A leftover 3.5.x signed with another key blocks this package.
2. Download `Cyclone-3.6.0.apk` on the phone. Wait until Files shows tens of megabytes, not a 1 KB HTML page.
3. Open the APK → Install. Play Protect: More details → Install anyway.
4. Do **not** install `Cyclone-3.6.0-beta.apk` or `Cyclone-3.6.0-beta.2.apk`.

`3.6.0-beta` was `assembleDebug` (`testOnly`). `3.6.0-beta.2` was a large debug-cert APK. This 3.6.0 build is a v2-signed release APK, arm64-only, not `testOnly`, signed with the pinned Cyclone release keystore.

## Older shelves (do not install for current work)

Historical tags such as `v3.5.3`, `v3.5.1`, `v2.9.5` remain on the Releases page for archaeology. They are not the working product.

## Publishing rules

- Bump `versionCode` for every distributed APK. Change `versionName` only for a product identity change.
- Combined Release CI publishes from `release/beta/**` or `release/stable/**` when `release/version.toml` authorizes publication.
- Follow [`docs/agent-system/FAST_RELEASE_PLAYBOOK.md`](docs/agent-system/FAST_RELEASE_PLAYBOOK.md).
- Never claim an APK is updated until the GitHub Release asset matches the source SHA.
