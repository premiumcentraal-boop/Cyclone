# Teamwork Sniper V1 release status

Release branch: `release/teamwork-sniper-v1`

Product version: **V1 Beta 3** (versionCode 9)  
Canonical Android package: `com.cyclone.teamworksniper`  
Clean-install package: `com.cyclone.teamworksniper.v1`

## Installability verification

Verification candidate: this Beta 3 publish.

Result: **PENDING GitHub Actions**

Verified gates on Beta 2 (kept as the previous installable):

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

## What's new in Beta 3

- Swipeable week bar; days listed vertically with M1, M2, S1, S2, S3
- Three shift states only: Open, Sniping, Claimed
- Header Off / Armed control
- Setup connections: phone calendar, Teamwork 24h pull, standby claims
- Standby claims are gated when the screen is locked unless the option is On

## Beta packaging

The beta publishes two APKs:

- `Teamwork-Sniper-V1.apk` — canonical package `com.cyclone.teamworksniper`
- `Teamwork-Sniper-V1-Clean.apk` — fresh package `com.cyclone.teamworksniper.v1`

Use the **Clean** APK first when an earlier Teamwork Sniper build produced Android's generic **App not installed** message.

## Verification boundary

The APK packaging and Android install/launch path are CI-verified.

**Physical-device visual acceptance and real Teamwork claiming remain pending.**
