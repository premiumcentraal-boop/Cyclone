# Cyclone Mobile 4.1 Stage B5 — release lane

**Identity:** mobile `4.1.0` / versionCode `80`; Cyclone One / `pc_companion` stays `1.0.0` on this tree  
**Base:** B4 tip `f1e0239` / PR #71 (Fast Path named VD on Glass + Contract + Sticky + published `v4.0.4`)  
**Physical Pixel 8:** **UNVERIFIED** — this lane packages the operator cut path. Do not treat a green CI build as device evidence.

Stage B5 is the Mobile 4.1.0 packaging handoff. It does **not** rewrite B1–B4, cut Magisk, invent a physical pass, or bump Cyclone One. Product notes live in [`RELEASE_4.1.md`](RELEASE_4.1.md); this file is merge order + operator commands only.

`publication_authorized` is flipped **true** on the cut commit so `.github/workflows/mobile-publish-v3910.yml` (Cyclone Mobile Full Release) can sign and publish the same way **v4.0.4** did. Physical Pixel 8 remains **UNVERIFIED**. Do not claim the GitHub tag `v4.1.0` already exists until Full Release creates it.

Protocol-fix tip `d249f2a` Mobile CI **SUCCESS**: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34172841433 (pull_request run on `grok/mobile-4.1-s5-release`). That is unit-test / lint / assemble evidence, not a physical Pixel pass and not the Full Release push run.

## Merge order

Do not rewrite stack history. Merge in this order:

1. **#65** Sticky (`grok/mobile-4.1-s1-sticky`) base `release/cyclone-mobile-v4.0.4` / `v4.0.4`
2. **#67** Contract (`grok/mobile-4.1-s2-contract`)
3. **#69** Glass (`grok/mobile-4.1-s3-glass`)
4. **#71** Fast Path named VD (`grok/mobile-4.1-s4-fastpath-bg`)
5. **this B5 PR** (`grok/mobile-4.1-s5-release` base `grok/mobile-4.1-s4-fastpath-bg`)

After the stack lands, cut `v4.1.0` from the merged SHA. This PR does not create the tag.

## Operator cut path

Match the successful **v4.0.4** path (not `mobile-release.yml` rotated secrets):

1. After Mobile CI is green on the protocol-fix tip, set `publication_authorized=true` on the cut commit (Pixel remains UNVERIFIED).
2. Create and push `release/cyclone-mobile-v4.1.0` from that SHA so Mobile CI **push** and Cyclone Mobile Full Release (`mobile-publish-v3910.yml`) both fire (`on.push.branches` includes `release/cyclone-mobile-v*`).
3. Wait for [`.github/workflows/mobile-ci.yml`](../.github/workflows/mobile-ci.yml) success on that **push** run (not a pull_request run). Full Release waits for the same SHA, recovers the historical 3.9.0/4.0.4-compatible keystore, signs `Cyclone-4.1.0.apk`, verifies cert equality vs `v4.0.4`, and `gh release create v4.1.0`. Do **not** also create the tag locally.
4. `android_signing` = `LEGACY_UPDATE_COMPATIBLE_DEV_KEY` — same update-compatible dev signer as 4.0.4 so in-place upgrade from versionCode 75→80 works. If the signer does not match a device's 4.0.4 install, documented wipe; do not claim update succeeded.
5. Do **not** dispatch [`.github/workflows/pc-companion-release.yml`](../.github/workflows/pc-companion-release.yml) as part of this mobile 4.1.0 cut. One/PC 1.1.0 is A5, out of scope. Pairing: full Layer 2 MCP needs One ≥ 1.1.0; 4.0.4/4.1.0 phone + One 1.0.0 remains foreground-capable.
6. `mobile-release.yml` (rotated-key secrets) is the B5-documented fallback and is **blocked** unless `mobile-release-approval` has the five `CYCLONE_ANDROID_*` secrets. Do not invent a keystore.

Never force-push. Never replace an existing `v4.1.0`.

The helper refuses leftover `4.1.0-alpha.4` / versionCode `79`. `--execute` is not used when Full Release publishes the tag.

## Physical Pixel 8

Physical Pixel 8 remains **UNVERIFIED**. Use the checklist in [`RELEASE_4.1.md`](RELEASE_4.1.md). Do not check those boxes from CI.

## What B5 does not do

- Restart or rewrite B1–B4
- Magisk / root / a second mutation engine
- Merge other PRs
- Invent device results or mark Pixel 8 verified
- Claim the GitHub tag `v4.1.0` already exists before Full Release creates it
- Dispatch `pc-companion-release.yml` or invent a new keystore
- Cut One/PC A5 (1.1.0)
