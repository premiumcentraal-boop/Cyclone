# Teamwork Sniper V1 release status

Release branch: `release/teamwork-sniper-v1`

Product version: **V1 Beta 2** (versionCode 8)  
Canonical Android package: `com.cyclone.teamworksniper`  
Clean-install package: `com.cyclone.teamworksniper.v1`

## Installability verification

Verification candidate: this Beta 2 publish.

Result: **PENDING GitHub Actions**

Verified gates on Beta 1 (kept as the previous installable):

- Teamwork Sniper metadata: PASS
- semantic/safety guard: PASS
- JVM unit tests: PASS
- canonical Android release assembly: PASS
- clean-install Android release assembly: PASS
- APK signature verification: PASS
- manifest/package verification: PASS
- zip alignment verification: PASS
- canonical APK emulator install: PASS
- canonical MainActivity launch: PASS
- clean-install APK emulator install: PASS
- clean-install MainActivity launch: PASS
- release artifact packaging: PASS

## Beta packaging

The beta publishes two APKs:

- `Teamwork-Sniper-V1.apk` — canonical package `com.cyclone.teamworksniper`
- `Teamwork-Sniper-V1-Clean.apk` — fresh package `com.cyclone.teamworksniper.v1`

Use the **Clean** APK first when an earlier Teamwork Sniper build produced Android's generic **App not installed** message. The clean package avoids collisions with stale package/signature state from previous builds.

## Verification boundary

The APK packaging and Android install/launch path are CI-verified.

**Physical-device visual acceptance and real Teamwork claiming remain pending.** A successful emulator install does not prove live Teamwork claim behavior.
