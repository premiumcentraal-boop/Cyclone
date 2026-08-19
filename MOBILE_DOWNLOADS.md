# Cyclone Mobile APK Downloads

Use **GitHub Releases** as the permanent download shelf for Cyclone Mobile builds. You no longer need to open an Actions run, download a ZIP, extract `app-debug.apk`, and guess which build is newest.

## Current beta

### Cyclone Mobile 2.9.2 Beta

- Release page: https://github.com/premiumcentraal-boop/Cyclone/releases/tag/mobile-v2.9.2-beta
- Direct APK: https://github.com/premiumcentraal-boop/Cyclone/releases/download/mobile-v2.9.2-beta/Cyclone-Mobile-2.9.2-Beta.apk
- Checksum: https://github.com/premiumcentraal-boop/Cyclone/releases/download/mobile-v2.9.2-beta/Cyclone-Mobile-2.9.2-Beta.apk.sha256

## All mobile versions

Open the repository Releases page and choose the Cyclone Mobile version you want:

https://github.com/premiumcentraal-boop/Cyclone/releases

Mobile release tags use this format:

```text
mobile-v2.9.2-beta
mobile-v2.9.3-beta
mobile-v3.0.0-beta
```

APK filenames use this format:

```text
Cyclone-Mobile-2.9.2-Beta.apk
Cyclone-Mobile-2.9.3-Beta.apk
Cyclone-Mobile-3.0.0-Beta.apk
```

## Publishing future updates

The single `.github/workflows/android-mobile.yml` pipeline now reads the Android `versionName` from `apps/mobile/app/build.gradle.kts` automatically.

For a new mobile release:

1. Bump `versionCode` and `versionName` in `apps/mobile/app/build.gradle.kts`.
2. Work on a branch beginning with `release/cyclone-mobile-v`.
3. Push the mobile changes.
4. GitHub Actions runs tests and assembles the APK.
5. The APK is copied to a clean versioned filename.
6. Actions stores a 90-day artifact as a backup.
7. The workflow creates or updates the matching GitHub prerelease and uploads both the APK and SHA-256 file.

This makes GitHub Releases the stable download location while Actions remains the build/diagnostic history.

## Build verification

Each release includes a `.sha256` file. To verify a downloaded APK on Windows PowerShell:

```powershell
Get-FileHash .\Cyclone-Mobile-2.9.2-Beta.apk -Algorithm SHA256
```

Compare that value with the release checksum file.
